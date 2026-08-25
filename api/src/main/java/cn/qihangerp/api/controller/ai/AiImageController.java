package cn.qihangerp.api.controller.ai;

import cn.qihangerp.common.AjaxResult;
import cn.qihangerp.common.PageQuery;
import cn.qihangerp.common.TableDataInfo;
import cn.qihangerp.model.entity.AiImageTask;
import cn.qihangerp.security.common.BaseController;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * AI生图接口
 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/erp-api/ai/image")
public class AiImageController extends BaseController {

    private final AiImageService aiImageService;

    /**
     * 提交生图任务（异步），立即返回 taskId
     *
     * @param prompt  提示词（必填）
     * @param size    尺寸 宽x高，默认 1024x1024
     * @param model   模型档位：auto/standard/2k/4k，默认 auto
     * @param files   本地参考图（可选；可与 refUrls 混用，混用时后端统一归一为文件型）
     * @param refUrls 参考图地址（可选，JSON数组字符串；支持 http(s):// 外链与 /ai-images/ 本地路径，总数最多4张）
     */
    @PostMapping("/generate")
    public AjaxResult generate(@RequestParam String prompt,
                               @RequestParam(required = false, defaultValue = "1024x1024") String size,
                               @RequestParam(required = false, defaultValue = "auto") String model,
                               @RequestParam(value = "files", required = false) List<MultipartFile> files,
                               @RequestParam(required = false) String refUrls) {
        if (!aiImageService.isConfigured()) {
            return error("生图服务未配置，请联系管理员");
        }
        try {
            List<LumioImageClient.RefFile> refFiles = new ArrayList<>();
            if (files != null && !files.isEmpty()) {
                for (MultipartFile f : files) {
                    if (f == null || f.isEmpty()) {
                        continue;
                    }
                    refFiles.add(new LumioImageClient.RefFile(
                            f.getOriginalFilename(),
                            f.getContentType(),
                            f.getBytes()));
                }
            }
            List<String> urlList = parseRefUrls(refUrls);
            AiImageService.RefInput refs = aiImageService.normalizeRefs(urlList, refFiles);
            Long taskId = aiImageService.submit(getUsername(), prompt, size, model, refs.remoteUrls(), refs.files());
            aiImageService.execute(taskId, prompt, size, model, refs.remoteUrls(), refs.files());
            return success(Map.of("taskId", taskId));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            logger.error("提交生图任务失败", e);
            return error("提交生图任务失败：" + e.getMessage());
        }
    }

    /**
     * 查询任务状态
     */
    @GetMapping("/task/{id}")
    public AjaxResult task(@PathVariable Long id) {
        AiImageTask task = aiImageService.getTask(id, getUsername());
        if (task == null) {
            return AjaxResult.error(404, "任务不存在");
        }
        return success(Map.of(
                "taskId", task.getId(),
                "status", task.getStatus(),
                "statusText", statusText(task.getStatus()),
                "resultUrl", task.getResultUrl() == null ? "" : task.getResultUrl(),
                "error", task.getErrorMsg() == null ? "" : task.getErrorMsg(),
                "costSeconds", task.getCostSeconds() == null ? 0 : task.getCostSeconds()));
    }

    /**
     * 历史记录分页（当前用户）
     */
    @GetMapping("/history")
    public TableDataInfo history(PageQuery pageQuery) {
        return getDataTable(aiImageService.history(getUsername(), pageQuery));
    }

    /**
     * 生图参数选项：尺寸预设、模型档位、服务配置状态
     */
    @GetMapping("/options")
    public AjaxResult options() {
        return success(Map.of(
                "configured", aiImageService.isConfigured(),
                "sizes", List.of(
                        Map.of("label", "1:1 方形 1024×1024", "value", "1024x1024"),
                        Map.of("label", "横版 1792×1024", "value", "1792x1024"),
                        Map.of("label", "横版 1536×1024", "value", "1536x1024"),
                        Map.of("label", "竖版 1024×1792", "value", "1024x1792"),
                        Map.of("label", "竖版 1024×1536", "value", "1024x1536"),
                        Map.of("label", "2K 2560×1440", "value", "2560x1440"),
                        Map.of("label", "4K 3840×2160", "value", "3840x2160")),
                "models", List.of(
                        Map.of("label", "自动（按尺寸选择，推荐）", "value", "auto"),
                        Map.of("label", "标准（最快）", "value", "standard"),
                        Map.of("label", "2K 高清", "value", "2k"),
                        Map.of("label", "4K 超清（慢，费用高）", "value", "4k")),
                "maxRefCount", 4));
    }

    private List<String> parseRefUrls(String refUrls) {
        if (refUrls == null || refUrls.isBlank()) {
            return List.of();
        }
        String json = refUrls.trim();
        if (json.startsWith("[") && json.endsWith("]")) {
            try {
                List<String> list = com.alibaba.fastjson2.JSON.parseArray(json, String.class);
                if (list != null) {
                    return list.stream().filter(s -> s != null && !s.isBlank()).toList();
                }
            } catch (Exception ignored) {
                // 非标准 JSON，退回朴素逗号分隔
            }
            String inner = json.substring(1, json.length() - 1);
            if (inner.isBlank()) {
                return List.of();
            }
            return Arrays.stream(inner.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        return Arrays.stream(json.split("[\\s,]+"))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private String statusText(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case 0 -> "排队中";
            case 1 -> "生成中";
            case 2 -> "已完成";
            case 3 -> "失败";
            default -> "未知";
        };
    }
}
