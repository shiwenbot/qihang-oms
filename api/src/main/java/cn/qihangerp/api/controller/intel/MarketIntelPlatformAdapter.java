package cn.qihangerp.api.controller.intel;

interface MarketIntelPlatformAdapter {
    String provider();

    MarketIntelPlatformRegistry.ParsedProfile parseProfile(String rawUrl);

    String dispatchProfileUrl(String cleanUrl, String accessToken);
}
