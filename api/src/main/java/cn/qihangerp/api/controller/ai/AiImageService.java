package cn.qihangerp.api.controller.ai;

import cn.qihangerp.common.PageQuery;
import cn.qihangerp.common.PageResult;
import cn.qihangerp.model.entity.AiImageTask;
import cn.qihangerp.service.AiImageTaskService;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * AI生图业务服务：任务提交（异步）、状态查询、历史分页、产物存储。
 */
@AllArgsConstructor
@Service
public class AiImageService {

    private static final Logger log = LoggerFactory.getLogger(AiImageService.class);

    private static final int MAX_PROMPT_LENGTH = 2000;
    private static final int MAX_REF_COUNT = 4;
    private static final long MAX_REF_FILE_BYTES = 10L * 1024 * 1024;
    private static final List<String> ALLOWED_REF_TYPES = List.of("image/png", "image/jpeg", "image/webp");

    /** 本地兜底存储目录（七牛未配置时使用） */
    private static final String LOCAL_STORE_DIR = System.getProperty("user.home") + "/qihang-oms/ai-images";

    private final AiImageTaskService taskService;
    private final LumioImageClient lumioClient;

    public boolean isConfigured() {
        return lumioClient.isConfigured();
    }

    /**
     * 校验并落库任务，返回任务ID。校验失败抛 IllegalArgumentException。
     */
    public Long submit(String username, String prompt, String size, String tier,
                       List<String> refUrls, List<LumioImageClient.RefFile> refFiles) {
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalArgumentException("请输入提示词");
        }
        if (prompt.length() > MAX_PROMPT_LENGTH) {
            throw new IllegalArgumentException("提示词过长（最多 " + MAX_PROMPT_LENGTH + " 字）");
        }
        String normalizedSize = normalizeSize(size);
        String model = lumioClient.resolveModel(tier, normalizedSize);

        String refType = AiImageTask.REF_TYPE_TEXT;
        int refCount = 0;
        List<String> safeUrls = refUrls == null ? List.of() : refUrls;
        List<LumioImageClient.RefFile> safeFiles = refFiles == null ? List.of() : refFiles;
        if (!safeFiles.isEmpty()) {
            if (safeFiles.size() > MAX_REF_COUNT) {
                throw new IllegalArgumentException("参考图最多 " + MAX_REF_COUNT + " 张");
            }
            for (LumioImageClient.RefFile f : safeFiles) {
                if (f.data() == null || f.data().length == 0) {
                    throw new IllegalArgumentException("参考图内容为空");
                }
                if (f.data().length > MAX_REF_FILE_BYTES) {
                    throw new IllegalArgumentException("参考图单张不能超过 10MB：" + f.filename());
                }
                if (f.contentType() == null || !ALLOWED_REF_TYPES.contains(f.contentType().toLowerCase())) {
                    throw new IllegalArgumentException("参考图仅支持 PNG/JPEG/WebP：" + f.filename());
                }
            }
            refType = AiImageTask.REF_TYPE_FILE;
            refCount = safeFiles.size();
        } else if (!safeUrls.isEmpty()) {
            if (safeUrls.size() > MAX_REF_COUNT) {
                throw new IllegalArgumentException("参考图最多 " + MAX_REF_COUNT + " 张");
            }
            for (String u : safeUrls) {
                if (!u.toLowerCase().startsWith("http://") && !u.toLowerCase().startsWith("https://")) {
                    throw new IllegalArgumentException("参考图地址必须以 http(s):// 开头");
                }
            }
            refType = AiImageTask.REF_TYPE_URL;
            refCount = safeUrls.size();
        }

        AiImageTask task = new AiImageTask();
        task.setPrompt(prompt);
        task.setSize(normalizedSize);
        task.setModel(tier == null || tier.isBlank() ? "auto" : tier);
        task.setRefType(refType);
        task.setRefCount(refCount);
        task.setStatus(AiImageTask.STATUS_PENDING);
        task.setCreateBy(username);
        task.setCreateTime(new Date());
        taskService.save(task);
        return task.getId();
    }

    /**
     * 异步执行生图任务（生图服务线程池并发受限）。
     */
    @Async("aiImageExecutor")
    public void execute(Long taskId, String prompt, String size, String tier,
                        List<String> refUrls, List<LumioImageClient.RefFile> refFiles) {
        AiImageTask task = taskService.getById(taskId);
        if (task == null) {
            log.warn("生图任务不存在: {}", taskId);
            return;
        }
        if (!AiImageTask.STATUS_PENDING.equals(task.getStatus())) {
            return;
        }
        String model = lumioClient.resolveModel(tier, task.getSize());
        long start = System.currentTimeMillis();
        task.setStatus(AiImageTask.STATUS_RUNNING);
        taskService.updateById(task);
        try {
            byte[] image;
            if (AiImageTask.REF_TYPE_FILE.equals(task.getRefType())) {
                image = lumioClient.edit(model, prompt, task.getSize(), refFiles);
            } else if (AiImageTask.REF_TYPE_URL.equals(task.getRefType())) {
                image = lumioClient.generate(model, prompt, task.getSize(), refUrls);
            } else {
                image = lumioClient.generate(model, prompt, task.getSize(), null);
            }
            String url = storeResult(task.getId(), image);
            task.setStatus(AiImageTask.STATUS_SUCCESS);
            task.setResultUrl(url);
            task.setErrorMsg(null);
            task.setCostSeconds((int) ((System.currentTimeMillis() - start) / 1000));
        } catch (Exception e) {
            log.error("生图任务 {} 失败: {}", taskId, e.getMessage(), e);
            String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
            task.setStatus(AiImageTask.STATUS_FAILED);
            task.setErrorMsg(msg.length() > 1000 ? msg.substring(0, 1000) : msg);
            task.setCostSeconds((int) ((System.currentTimeMillis() - start) / 1000));
        }
        taskService.updateById(task);
    }

    public AiImageTask getTask(Long id, String username) {
        AiImageTask task = taskService.getById(id);
        if (task == null) {
            return null;
        }
        // 仅任务所有者可查看
        if (username != null && !username.equals(task.getCreateBy())) {
            return null;
        }
        return task;
    }

    public PageResult<AiImageTask> history(String username, PageQuery pageQuery) {
        return taskService.queryUserPage(username, pageQuery);
    }

    /**
     * 校验并归一化尺寸为 "WxH"（64~4096 整数）。
     */
    private String normalizeSize(String size) {
        if (!StringUtils.hasText(size)) {
            return "1024x1024";
        }
        String[] wh = size.toLowerCase().split("x");
        if (wh.length != 2) {
            throw new IllegalArgumentException("尺寸格式应为 宽x高，例如 1024x1024");
        }
        try {
            int w = Integer.parseInt(wh[0].trim());
            int h = Integer.parseInt(wh[1].trim());
            if (w < 64 || h < 64 || w > 4096 || h > 4096) {
                throw new IllegalArgumentException();
            }
            return w + "x" + h;
        } catch (Exception e) {
            throw new IllegalArgumentException("尺寸超出范围（每边 64~4096）");
        }
    }

    /**
     * 存储产物：优先七牛云，未配置则落本地磁盘并返回静态映射地址。
     */
    private String storeResult(Long taskId, byte[] image) {
        String ext = sniffImageExt(image);
        String filename = "ai_" + taskId + "_" +
                new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + "." + ext;
        try {
            String qiniuUrl = uploadToQiniu(filename, image);
            if (qiniuUrl != null) {
                return qiniuUrl;
            }
        } catch (Exception e) {
            log.warn("生图结果上传七牛失败，转本地存储: {}", e.getMessage());
        }
        return storeLocal(filename, image);
    }

    private String sniffImageExt(byte[] data) {
        if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return "png";
        }
        if (data.length >= 3 && data[0] == (byte) 0xFF && data[1] == (byte) 0xD8) {
            return "jpg";
        }
        if (data.length >= 12 && new String(data, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                && new String(data, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")) {
            return "webp";
        }
        return "png";
    }

    private String uploadToQiniu(String filename, byte[] data) throws Exception {
        java.util.Properties props = org.springframework.core.io.support.PropertiesLoaderUtils
                .loadAllProperties("config.properties");
        String domain = props.getProperty("qiniu_img_domain");
        String ak = props.getProperty("qiniu_access_key");
        String sk = props.getProperty("qiniu_secret_key");
        String bucket = props.getProperty("qiniu_bucket");
        if (!StringUtils.hasText(domain) || !StringUtils.hasText(ak)
                || !StringUtils.hasText(sk) || !StringUtils.hasText(bucket)) {
            return null;
        }
        com.qiniu.util.Auth auth = com.qiniu.util.Auth.create(ak, sk);
        String upToken = auth.uploadToken(bucket);
        com.qiniu.storage.Configuration cfg = new com.qiniu.storage.Configuration(com.qiniu.common.Zone.zone0());
        com.qiniu.storage.UploadManager uploadManager = new com.qiniu.storage.UploadManager(cfg);
        com.qiniu.http.Response response = uploadManager.put(
                new java.io.ByteArrayInputStream(data), "ai-image/" + filename,
                upToken, null, null);
        var res = com.alibaba.fastjson2.JSONObject.parseObject(response.bodyString());
        String key = res.getString("key");
        return domain.endsWith("/") ? domain + key : domain + "/" + key;
    }

    private String storeLocal(String filename, byte[] data) {
        try {
            Path dir = Paths.get(LOCAL_STORE_DIR);
            Files.createDirectories(dir);
            Path file = dir.resolve(filename);
            Files.write(file, data);
            return "/ai-images/" + filename;
        } catch (IOException e) {
            log.error("生图结果本地存储失败: {}", e.getMessage(), e);
            throw new RuntimeException("结果图片存储失败：" + e.getMessage(), e);
        }
    }
}
