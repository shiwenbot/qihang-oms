package cn.qihangerp.api.controller.intel;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Component
class XiaohongshuPlatformAdapter implements MarketIntelPlatformAdapter {
    @Override
    public String provider() {
        return "xiaohongshu";
    }

    @Override
    public MarketIntelPlatformRegistry.ParsedProfile parseProfile(String rawUrl) {
        if (!StringUtils.hasText(rawUrl)) throw new IllegalArgumentException("请粘贴竞品主页链接");
        try {
            URI uri = URI.create(rawUrl.trim());
            if (!("www.xiaohongshu.com".equalsIgnoreCase(uri.getHost()) || "xiaohongshu.com".equalsIgnoreCase(uri.getHost()))) {
                throw new IllegalArgumentException("只支持小红书主页链接");
            }
            String[] parts = uri.getPath().split("/");
            if (parts.length < 4 || !"user".equals(parts[1]) || !"profile".equals(parts[2]) || !StringUtils.hasText(parts[3])) {
                throw new IllegalArgumentException("链接必须是 user/profile/{user_id} 格式");
            }
            String accessToken = query(uri.getRawQuery()).get("xsec_token");
            if (!StringUtils.hasText(accessToken)) throw new IllegalArgumentException("主页链接缺少 xsec_token");
            String cleanUrl = "https://www.xiaohongshu.com/user/profile/" + URLEncoder.encode(parts[3], StandardCharsets.UTF_8);
            return new MarketIntelPlatformRegistry.ParsedProfile(parts[3], accessToken, cleanUrl);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("竞品主页链接无效");
        }
    }

    @Override
    public String dispatchProfileUrl(String cleanUrl, String accessToken) {
        return cleanUrl + "?xsec_token=" + URLEncoder.encode(accessToken, StandardCharsets.UTF_8) + "&xsec_source=pc_search";
    }

    private Map<String, String> query(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null) return result;
        for (String part : rawQuery.split("&")) {
            String[] pair = part.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }
}
