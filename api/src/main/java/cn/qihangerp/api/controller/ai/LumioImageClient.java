package cn.qihangerp.api.controller.ai;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 生图服务客户端（gpt-image-2 中转）
 * 业务规则：
 * 1. 无参考图          -> POST /images/generations (JSON)
 * 2. 参考图为网络URL    -> POST /images/generations + reference_images (JSON)
 * 3. 参考图为上传文件   -> POST /images/edits (multipart)
 * 三种场景不可混用，由本客户端自动路由。
 * 模型按尺寸自动升档：最长边>=3000 用 4k，>1024 用 2k，否则标准。
 * 上游偶发 502/503，自动重试 3 次（退避 15s/30s）。
 */
@Component
public class LumioImageClient {

    private static final Logger log = LoggerFactory.getLogger(LumioImageClient.class);

    private static final String MODEL_STANDARD = "gpt-image-2";
    private static final String MODEL_2K = "gpt-image-2-2k";
    private static final String MODEL_4K = "gpt-image-2-4k";

    private final String apiKey;
    private final String baseUrl;
    private final OkHttpClient http;

    public LumioImageClient() {
        // 与 config.properties 读取约定一致：环境变量优先，其次 config.properties
        java.util.Properties props = new java.util.Properties();
        try {
            org.springframework.core.io.support.PropertiesLoaderUtils
                    .loadAllProperties("config.properties").forEach(props::putIfAbsent);
        } catch (Exception ignored) {
        }
        String key = System.getenv("LUMIO_API_KEY");
        if (key == null || key.isBlank()) {
            key = props.getProperty("lumio.api-key");
        }
        String url = System.getenv("LUMIO_BASE_URL");
        if (url == null || url.isBlank()) {
            url = props.getProperty("lumio.base-url");
        }
        this.apiKey = key == null ? "" : key.trim();
        this.baseUrl = (url == null || url.isBlank())
                ? "https://api.lumio.games/v1" : url.trim().replaceAll("/+$", "");
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(600, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    /**
     * 模型档位 -> 实际模型名。
     * auto: 按尺寸自动（>=3000 -> 4k, >1024 -> 2k, 否则标准）
     */
    public String resolveModel(String tier, String size) {
        if (tier == null || tier.isBlank() || "auto".equalsIgnoreCase(tier)) {
            return pickModelBySize(size);
        }
        return switch (tier.toLowerCase()) {
            case "standard" -> MODEL_STANDARD;
            case "2k" -> MODEL_2K;
            case "4k" -> MODEL_4K;
            default -> pickModelBySize(size);
        };
    }

    private String pickModelBySize(String size) {
        try {
            String[] wh = size.toLowerCase().split("x");
            int max = Math.max(Integer.parseInt(wh[0].trim()), Integer.parseInt(wh[1].trim()));
            if (max >= 3000) return MODEL_4K;
            if (max > 1024) return MODEL_2K;
        } catch (Exception ignored) {
        }
        return MODEL_STANDARD;
    }

    /** 参考图描述：本地文件字节 */
    public record RefFile(String filename, String contentType, byte[] data) {
    }

    /**
     * 文生图（无参考图或参考图为 URL）。
     *
     * @param refUrls 网络参考图 URL，可为空列表
     * @return 生成图片的字节
     */
    public byte[] generate(String model, String prompt, String size, List<String> refUrls) {
        JSONObject payload = new JSONObject();
        payload.put("model", model);
        payload.put("prompt", prompt);
        payload.put("size", size);
        if (refUrls != null && !refUrls.isEmpty()) {
            JSONArray arr = new JSONArray();
            refUrls.forEach(arr::add);
            payload.put("reference_images", arr);
        }
        RequestBody body = RequestBody.create(payload.toJSONString(),
                MediaType.parse("application/json; charset=utf-8"));
        Request request = baseRequest("/images/generations").post(body).build();
        Response response = executeWithRetry(request, "/images/generations");
        return readImage(response);
    }

    /**
     * 图生图（本地上传的参考图）。
     */
    public byte[] edit(String model, String prompt, String size, List<RefFile> refFiles) {
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("size", size);
        for (RefFile f : refFiles) {
            builder.addFormDataPart("image[]", f.filename(),
                    RequestBody.create(f.data(), MediaType.parse(f.contentType())));
        }
        Request request = baseRequest("/images/edits").post(builder.build()).build();
        Response response = executeWithRetry(request, "/images/edits");
        return readImage(response);
    }

    private Request.Builder baseRequest(String path) {
        return new Request.Builder()
                .url(baseUrl + path)
                .header("Authorization", "Bearer " + apiKey);
    }

    private Response executeWithRetry(Request request, String route) {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Response response = http.newCall(request).execute();
                int code = response.code();
                if (code == 502 || code == 503 || code == 504) {
                    String bodyStr = peekBody(response);
                    log.warn("生图服务 HTTP {} via {} (attempt {}/3): {}", code, route, attempt, bodyStr);
                    if (attempt < 3) {
                        sleep(15L * attempt);
                        continue;
                    }
                    throw new RuntimeException("生图服务暂时不可用（HTTP " + code + "），请稍后重试");
                }
                if (!response.isSuccessful()) {
                    String bodyStr = peekBody(response);
                    log.error("生图服务 HTTP {} via {}: {}", code, route, bodyStr);
                    throw new RuntimeException(extractErrorMessage(bodyStr, code));
                }
                return response;
            } catch (IOException e) {
                last = e;
                log.warn("生图服务网络异常 via {} (attempt {}/3): {}", route, attempt, e.getMessage());
                if (attempt < 3) {
                    sleep(15L * attempt);
                }
            }
        }
        throw new RuntimeException("生图服务网络异常，请稍后重试" + (last != null ? "：" + last.getMessage() : ""), last);
    }

    /**
     * 解析响应中的图片：data[0].b64_json 优先，否则下载 data[0].url
     */
    private byte[] readImage(Response response) {
        try (response) {
            String text = response.body() != null ? response.body().string() : "";
            JSONObject data = JSONObject.parseObject(text);
            JSONArray items = data == null ? null : data.getJSONArray("data");
            if (items == null || items.isEmpty()) {
                throw new RuntimeException("生图服务返回为空");
            }
            JSONObject item = items.getJSONObject(0);
            String b64 = item.getString("b64_json");
            if (b64 != null && !b64.isBlank()) {
                return java.util.Base64.getDecoder().decode(b64);
            }
            String url = item.getString("url");
            if (url != null && !url.isBlank()) {
                return download(url);
            }
            throw new RuntimeException("生图服务响应中无图片数据");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析生图响应失败：" + e.getMessage(), e);
        }
    }

    public byte[] download(String url) {
        Request request = new Request.Builder().url(url).build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("下载生成图片失败 HTTP " + response.code());
            }
            return response.body().bytes();
        } catch (IOException e) {
            throw new RuntimeException("下载生成图片失败：" + e.getMessage(), e);
        }
    }

    private String peekBody(Response response) {
        try {
            return response.body() != null ? response.body().string() : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** 从上游错误 JSON 中提取可读信息，不暴露中转细节 */
    private String extractErrorMessage(String bodyStr, int code) {
        try {
            JSONObject json = JSONObject.parseObject(bodyStr);
            if (json != null) {
                for (String key : new String[]{"message", "error", "msg"}) {
                    Object v = json.get(key);
                    if (v instanceof String s && !s.isBlank()) {
                        return "生图失败（HTTP " + code + "）：" + s;
                    }
                    if (v instanceof JSONObject o && o.getString("message") != null) {
                        return "生图失败（HTTP " + code + "）：" + o.getString("message");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "生图失败（HTTP " + code + "），请检查参数后重试";
    }


    private void sleep(long seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
