package cn.qihangerp.api.controller.intel;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
class MarketIntelPlatformRegistry {
    static final String DEFAULT_PROVIDER = "xiaohongshu";
    static final String DEFAULT_ACCOUNT = "default";
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9_-]{0,49}");
    private final Map<String, MarketIntelPlatformAdapter> adapters;

    MarketIntelPlatformRegistry(List<MarketIntelPlatformAdapter> adapters) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(
                MarketIntelPlatformAdapter::provider, Function.identity()));
    }

    String provider(String value) {
        String normalized = identifier(value, DEFAULT_PROVIDER, "provider");
        if (!adapters.containsKey(normalized)) throw new IllegalArgumentException("暂不支持该平台: " + normalized);
        return normalized;
    }

    String account(String value) {
        return identifier(value, DEFAULT_ACCOUNT, "account_id");
    }

    ParsedProfile parseProfile(String provider, String raw) {
        return adapter(provider).parseProfile(raw);
    }

    String dispatchProfileUrl(String provider, String cleanUrl, String accessToken) {
        return adapter(provider).dispatchProfileUrl(cleanUrl, accessToken);
    }

    String sanitizePublicUrl(String value) {
        if (!StringUtils.hasText(value)) return value;
        try {
            URI uri = URI.create(value.trim());
            if (uri.getRawQuery() == null) return value.trim();
            String filtered = java.util.Arrays.stream(uri.getRawQuery().split("&"))
                    .filter(part -> {
                        String key = java.net.URLDecoder.decode(part.split("=", 2)[0], java.nio.charset.StandardCharsets.UTF_8);
                        return !java.util.Set.of("xsec_token", "access_token", "token").contains(key.toLowerCase());
                    })
                    .collect(java.util.stream.Collectors.joining("&"));
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), filtered.isEmpty() ? null : filtered, uri.getFragment()).toString();
        } catch (Exception ignored) {
            return value.replaceAll("(?i)([?&](?:xsec_token|access_token|token)=)[^&\\s]+", "$1[redacted]");
        }
    }

    private String identifier(String value, String fallback, String field) {
        String normalized = StringUtils.hasText(value) ? value.trim().toLowerCase() : fallback;
        if (!IDENTIFIER.matcher(normalized).matches()) throw new IllegalArgumentException(field + "格式无效");
        return normalized;
    }

    private MarketIntelPlatformAdapter adapter(String provider) {
        return adapters.get(provider(provider));
    }

    record ParsedProfile(String userId, String accessToken, String cleanUrl) {}
}
