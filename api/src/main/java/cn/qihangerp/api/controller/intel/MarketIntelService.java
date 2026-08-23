package cn.qihangerp.api.controller.intel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class MarketIntelService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int MAX_KEYWORDS = 50;
    private static final BigDecimal ALERT_RATIO = new BigDecimal("1.8");

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final TransactionTemplate transactionTemplate;
    private final MarketIntelPlatformRegistry platforms;
    private final MarketIntelTokenCipher tokenCipher;
    private final SecureRandom secureRandom = new SecureRandom();

    public MarketIntelService(JdbcTemplate jdbc,
                              @Qualifier("marketIntelRestTemplate") RestTemplate restTemplate,
                              TransactionTemplate transactionTemplate,
                              MarketIntelPlatformRegistry platforms,
                              MarketIntelTokenCipher tokenCipher) {
        this.jdbc = jdbc;
        this.restTemplate = restTemplate;
        this.transactionTemplate = transactionTemplate;
        this.platforms = platforms;
        this.tokenCipher = tokenCipher;
    }

    @Value("${market-intel.sidecar-url:http://127.0.0.1:18080}")
    private String sidecarUrl;
    @Value("${market-intel.shared-token:}")
    private String sharedToken;

    public List<Map<String, Object>> keywords(long merchantId, String providerValue, String accountValue) {
        String provider = platforms.provider(providerValue);
        String accountId = platforms.account(accountValue);
        return jdbc.queryForList("SELECT id,provider,account_id,keyword,enabled,sort_type,create_time,update_time FROM mi_keyword WHERE merchant_id=? AND provider=? AND account_id=? ORDER BY id", merchantId, provider, accountId);
    }

    public synchronized long addKeyword(long merchantId, String providerValue, String accountValue, String username, MarketIntelDtos.KeywordRequest request) {
        String provider = platforms.provider(providerValue);
        String accountId = platforms.account(accountValue);
        String keyword = normalizeKeyword(request.keyword());
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM mi_keyword WHERE merchant_id=? AND provider=? AND account_id=?", Integer.class, merchantId, provider, accountId);
        if (count != null && count >= MAX_KEYWORDS) throw new IllegalArgumentException("每个商户最多配置50个关键词");
        try {
            jdbc.update("INSERT INTO mi_keyword(merchant_id,provider,account_id,keyword,enabled,sort_type,create_by) VALUES(?,?,?,?,?,?,?)",
                    merchantId, provider, accountId, keyword, request.enabled() == null || request.enabled() ? 1 : 0,
                    request.sortType() == null ? 2 : request.sortType(), username);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("该关键词已存在");
        }
        return Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM mi_keyword WHERE merchant_id=? AND provider=? AND account_id=? AND keyword=?", Long.class, merchantId, provider, accountId, keyword));
    }

    public void updateKeyword(long merchantId, String providerValue, String accountValue, long id, MarketIntelDtos.KeywordRequest request) {
        String provider = platforms.provider(providerValue);
        String accountId = platforms.account(accountValue);
        String keyword = normalizeKeyword(request.keyword());
        int changed;
        try {
            changed = jdbc.update("UPDATE mi_keyword SET keyword=?,enabled=?,sort_type=? WHERE id=? AND merchant_id=? AND provider=? AND account_id=?",
                    keyword, request.enabled() == null || request.enabled() ? 1 : 0,
                    request.sortType() == null ? 2 : request.sortType(), id, merchantId, provider, accountId);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("该关键词已存在");
        }
        if (changed == 0) throw new IllegalArgumentException("关键词不存在");
    }

    public void deleteKeyword(long merchantId, String providerValue, String accountValue, long id) {
        String provider = platforms.provider(providerValue);
        String accountId = platforms.account(accountValue);
        if (jdbc.update("DELETE FROM mi_keyword WHERE id=? AND merchant_id=? AND provider=? AND account_id=?", id, merchantId, provider, accountId) == 0) {
            throw new IllegalArgumentException("关键词不存在");
        }
    }

    public List<Map<String, Object>> rank(long merchantId, String providerValue, String accountValue, String keyword, LocalDate date) {
        String provider = platforms.provider(providerValue);
        String accountId = platforms.account(accountValue);
        String sql = """
                SELECT r.rank_no AS `rank`,r.keyword,r.note_id,r.liked_count,r.collected_count,r.comment_count,
                       n.title,n.nickname,n.user_id,n.note_url,n.cover_url,n.published_at
                FROM mi_rank_daily r JOIN mi_note_raw n
                  ON n.merchant_id=r.merchant_id AND n.provider=r.provider AND n.account_id=r.account_id AND n.crawl_run_id=r.crawl_run_id
                 AND n.note_id=r.note_id AND n.source='search'
                WHERE r.merchant_id=? AND r.provider=? AND r.account_id=? AND r.stat_date=? AND (? IS NULL OR r.keyword=?)
                ORDER BY r.keyword,r.rank_no
                """;
        return jdbc.queryForList(sql, merchantId, provider, accountId, Date.valueOf(date), blankToNull(keyword), blankToNull(keyword));
    }

    public List<Map<String, Object>> trend(long merchantId, String providerValue, String accountValue, String keyword, int days) {
        String provider = platforms.provider(providerValue);
        String accountId = platforms.account(accountValue);
        int safeDays = Math.max(1, Math.min(days, 90));
        return jdbc.queryForList("SELECT keyword,stat_date,note_count,like_sum,heat_score FROM mi_keyword_daily " +
                        "WHERE merchant_id=? AND provider=? AND account_id=? AND keyword=? AND stat_date>=? ORDER BY stat_date",
                merchantId, provider, accountId, normalizeKeyword(keyword), Date.valueOf(LocalDate.now(SHANGHAI).minusDays(safeDays - 1L)));
    }

    public List<Map<String, Object>> competitors(long merchantId, String providerValue, String accountValue) {
        String provider = platforms.provider(providerValue);
        String accountId = platforms.account(accountValue);
        return jdbc.queryForList("SELECT id,provider,account_id,user_id,profile_url,nickname,red_id,avatar_url,fans,follows,last_note_id,last_crawled_at,enabled " +
                "FROM mi_competitor WHERE merchant_id=? AND provider=? AND account_id=? ORDER BY id", merchantId, provider, accountId);
    }

    public List<Map<String, Object>> competitorNotes(long merchantId, String providerValue, String accountValue, long competitorId) {
        String provider = platforms.provider(providerValue);
        String accountId = platforms.account(accountValue);
        return jdbc.queryForList("""
                SELECT n.note_id,n.title,n.note_url,n.cover_url,n.nickname,n.liked_count,n.collected_count,n.comment_count,
                       n.published_at,n.crawled_at
                FROM mi_note_raw n JOIN mi_competitor c ON c.merchant_id=n.merchant_id AND c.provider=n.provider AND c.account_id=n.account_id AND c.user_id=n.user_id
                WHERE n.merchant_id=? AND n.provider=? AND n.account_id=? AND c.id=? AND n.source='user'
                ORDER BY COALESCE(n.published_at,n.crawled_at) DESC LIMIT 30
                """, merchantId, provider, accountId, competitorId);
    }

    public long addCompetitor(long merchantId, MarketIntelDtos.CompetitorRequest request) {
        String provider = platforms.provider(request.provider());
        String accountId = platforms.account(request.accountId());
        MarketIntelPlatformRegistry.ParsedProfile profile = platforms.parseProfile(provider, request.profileUrl());
        try {
            jdbc.update("INSERT INTO mi_competitor(merchant_id,provider,account_id,user_id,xsec_token,profile_url,nickname,red_id,avatar_url,fans,follows) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    merchantId, provider, accountId, profile.userId(), tokenCipher.encrypt(profile.accessToken()), profile.cleanUrl(), request.nickname(), request.redId(),
                    request.avatarUrl(), nonNegative(request.fans()), nonNegative(request.follows()));
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("该竞品账号已存在");
        }
        return Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM mi_competitor WHERE merchant_id=? AND provider=? AND account_id=? AND user_id=?", Long.class,
                merchantId, provider, accountId, profile.userId()));
    }

    public Map<String, Object> previewCompetitor(MarketIntelDtos.CompetitorRequest request) {
        String provider = platforms.provider(request.provider());
        String accountId = platforms.account(request.accountId());
        MarketIntelPlatformRegistry.ParsedProfile profile = platforms.parseProfile(provider, request.profileUrl());
        if (!StringUtils.hasText(sharedToken)) throw new IllegalArgumentException("未配置 MARKET_INTEL_TOKEN");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(sharedToken);
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(sidecarUrl + "/competitors/preview",
                    new HttpEntity<>(Map.of("user_id", profile.userId(), "profile_url", request.profileUrl().trim(),
                            "provider", provider, "account_id", accountId), headers), Map.class);
            Map body = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || body == null) throw new IllegalArgumentException("无法读取竞品资料");
            return body;
        } catch (Exception e) {
            throw new IllegalArgumentException("竞品资料预览失败，请检查 Cookie 或稍后重试");
        }
    }

    public void deleteCompetitor(long merchantId, String providerValue, String accountValue, long id) {
        String provider = platforms.provider(providerValue);
        String accountId = platforms.account(accountValue);
        if (jdbc.update("DELETE FROM mi_competitor WHERE id=? AND merchant_id=? AND provider=? AND account_id=?", id, merchantId, provider, accountId) == 0) {
            throw new IllegalArgumentException("竞品不存在");
        }
    }

    public List<Map<String, Object>> alerts(long merchantId, String providerValue, String accountValue) {
        String provider = platforms.provider(providerValue);
        String accountId = platforms.account(accountValue);
        return jdbc.queryForList("SELECT id,keyword,stat_date,pct_change,heat_today,heat_yesterday,status,create_time,read_time " +
                "FROM mi_alert WHERE merchant_id=? AND provider=? AND account_id=? ORDER BY stat_date DESC,create_time DESC", merchantId, provider, accountId);
    }

    public void readAlert(long merchantId, String providerValue, String accountValue, long id) {
        String provider = platforms.provider(providerValue);
        String accountId = platforms.account(accountValue);
        if (jdbc.update("UPDATE mi_alert SET status='read',read_time=NOW() WHERE id=? AND merchant_id=? AND provider=? AND account_id=?", id, merchantId, provider, accountId) == 0) {
            throw new IllegalArgumentException("告警不存在");
        }
    }

    public Map<String, Object> status(long merchantId, String providerValue, String accountValue) {
        String provider = platforms.provider(providerValue);
        String accountId = platforms.account(accountValue);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("sidecar", sidecarExchange("/health?provider=" + encode(provider) + "&account_id=" + encode(accountId), HttpMethod.GET, null));
        } catch (Exception e) {
            result.put("sidecar", Map.of("ok", false, "cookie_ok", false, "message", "sidecar不可用"));
        }
        List<Map<String, Object>> jobs = jdbc.queryForList("SELECT id,status,stat_date,item_count,error_count,error_msg,started_at,finished_at,create_time " +
                "FROM mi_job_run WHERE merchant_id=? AND provider=? AND account_id=? ORDER BY id DESC LIMIT 10", merchantId, provider, accountId);
        result.put("jobs", jobs);
        return result;
    }

    public Map<String, Object> startQrLogin(String providerValue, String accountValue) {
        String query = scopeQuery(providerValue, accountValue);
        return sidecarExchange("/auth/qrcode/start?" + query, HttpMethod.POST, null);
    }

    public Map<String, Object> qrLoginStatus(String providerValue, String accountValue, String sessionId) {
        if (!StringUtils.hasText(sessionId)) throw new IllegalArgumentException("缺少登录会话");
        return sidecarExchange("/auth/qrcode/status?" + scopeQuery(providerValue, accountValue) + "&session_id=" + encode(sessionId), HttpMethod.GET, null);
    }

    public void logoutCollector(String providerValue, String accountValue) {
        sidecarExchange("/auth?" + scopeQuery(providerValue, accountValue), HttpMethod.DELETE, null);
    }

    private Map<String, Object> sidecarExchange(String path, HttpMethod method, Object body) {
        if (!StringUtils.hasText(sharedToken)) throw new IllegalArgumentException("未配置 MARKET_INTEL_TOKEN");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(sharedToken);
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<Map> response = restTemplate.exchange(sidecarUrl + path, method,
                    new HttpEntity<>(body, headers), Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalArgumentException("sidecar请求失败");
            }
            return response.getBody();
        } catch (Exception e) {
            throw new IllegalArgumentException("采集服务不可用，请确认 sidecar 已启动");
        }
    }

    public Map<String, Object> createJob(long merchantId, String providerValue, String accountValue) {
        String provider = platforms.provider(providerValue);
        String accountId = platforms.account(accountValue);
        List<Map<String, Object>> active = jdbc.queryForList("SELECT id,status FROM mi_job_run WHERE merchant_id=? AND provider=? AND account_id=? AND job_type='crawl' " +
                "AND status IN ('pending','running') ORDER BY id DESC LIMIT 1", merchantId, provider, accountId);
        if (!active.isEmpty()) return Map.of("job_id", active.get(0).get("id"), "status", active.get(0).get("status"), "existing", true);

        String requestToken = randomToken();
        LocalDate statDate = LocalDate.now(SHANGHAI);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                var statement = connection.prepareStatement(
                        "INSERT INTO mi_job_run(merchant_id,provider,account_id,job_type,status,request_token,stat_date) VALUES(?,?,?,'crawl','pending',?,?)",
                        new String[]{"id"});
                statement.setLong(1, merchantId);
                statement.setString(2, provider);
                statement.setString(3, accountId);
                statement.setString(4, requestToken);
                statement.setDate(5, Date.valueOf(statDate));
                return statement;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            Map<String, Object> existing = jdbc.queryForMap("SELECT id,status FROM mi_job_run WHERE merchant_id=? AND provider=? AND account_id=? AND job_type='crawl' AND status IN ('pending','running') LIMIT 1", merchantId, provider, accountId);
            return Map.of("job_id", existing.get("id"), "status", existing.get("status"), "existing", true);
        }
        long jobId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        dispatch(jobId, merchantId, provider, accountId, requestToken);
        return Map.of("job_id", jobId, "status", jobStatus(jobId), "existing", false);
    }

    public void createJobsForAllMerchants() {
        List<Map<String, Object>> scopes = jdbc.queryForList("SELECT merchant_id,provider,account_id FROM mi_keyword WHERE enabled=1 " +
                "UNION SELECT merchant_id,provider,account_id FROM mi_competitor WHERE enabled=1");
        for (Map<String, Object> scope : scopes) {
            long merchantId = ((Number) scope.get("merchant_id")).longValue();
            String provider = String.valueOf(scope.get("provider"));
            String accountId = String.valueOf(scope.get("account_id"));
            try { createJob(merchantId, provider, accountId); }
            catch (Exception e) { log.error("创建市场情报任务失败 merchant={} provider={} account={}: {}", merchantId, provider, accountId, sanitizeMessage(e.getMessage())); }
        }
    }

    private void dispatch(long jobId, long merchantId, String provider, String accountId, String requestToken) {
        if (!StringUtils.hasText(sharedToken)) {
            failJob(jobId, "未配置 MARKET_INTEL_TOKEN");
            return;
        }
        List<Map<String, Object>> keywords = jdbc.queryForList("SELECT keyword,sort_type FROM mi_keyword WHERE merchant_id=? AND provider=? AND account_id=? AND enabled=1 ORDER BY id LIMIT 50", merchantId, provider, accountId);
        List<Map<String, Object>> storedCompetitors = jdbc.queryForList("SELECT id,user_id,profile_url,xsec_token FROM mi_competitor WHERE merchant_id=? AND provider=? AND account_id=? AND enabled=1 ORDER BY id", merchantId, provider, accountId);
        List<Map<String, Object>> competitors;
        try {
            competitors = storedCompetitors.stream().map(row -> Map.<String, Object>of(
                    "id", row.get("id"), "user_id", row.get("user_id"),
                    "profile_url", platforms.dispatchProfileUrl(provider, String.valueOf(row.get("profile_url")),
                            tokenCipher.decrypt(String.valueOf(row.get("xsec_token")))))).toList();
        } catch (RuntimeException e) {
            failJob(jobId, shortMessage(e.getMessage()));
            return;
        }
        if (keywords.isEmpty() && competitors.isEmpty()) {
            failJob(jobId, "没有启用的关键词或竞品");
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job_id", jobId);
        body.put("keywords", keywords);
        body.put("competitors", competitors);
        body.put("request_token", requestToken);
        body.put("provider", provider);
        body.put("account_id", accountId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(sharedToken);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(sidecarUrl + "/jobs/run", new HttpEntity<>(body, headers), String.class);
            if (response.getStatusCode() != HttpStatus.ACCEPTED) {
                failJob(jobId, "sidecar返回" + response.getStatusCode().value());
                return;
            }
            jdbc.update("UPDATE mi_job_run SET status='running',started_at=NOW() WHERE id=? AND status='pending'", jobId);
        } catch (Exception e) {
            failJob(jobId, shortMessage(e.getMessage()));
        }
    }

    @Transactional
    public void acceptResult(long jobId, MarketIntelDtos.JobResult result) {
        List<Map<String, Object>> jobs = jdbc.queryForList("SELECT merchant_id,provider,account_id,status,request_token FROM mi_job_run WHERE id=? FOR UPDATE", jobId);
        if (jobs.isEmpty()) throw new IllegalArgumentException("job不存在");
        Map<String, Object> job = jobs.get(0);
        if (!Objects.equals(job.get("request_token"), result.requestToken())) throw new SecurityException("job token不匹配");
        if (!List.of("running", "pending").contains(String.valueOf(job.get("status")))) return;
        long merchantId = ((Number) job.get("merchant_id")).longValue();
        String provider = String.valueOf(job.get("provider"));
        String accountId = String.valueOf(job.get("account_id"));
        if (!provider.equals(platforms.provider(result.provider())) || !accountId.equals(platforms.account(result.accountId()))) {
            failJob(jobId, "回传任务范围不匹配");
            return;
        }
        int saved = 0;
        int errors = result.errorCount() == null ? 0 : Math.max(0, result.errorCount());
        for (MarketIntelDtos.NoteResult note : safe(result.notes())) {
            try {
                validateNote(note);
                upsertNote(merchantId, provider, accountId, jobId, note);
                saved++;
            } catch (RuntimeException e) {
                errors++;
                log.warn("忽略无效情报结果 job={} note={}: {}", jobId, note == null ? null : note.noteId(), sanitizeMessage(e.getMessage()));
            }
        }
        for (MarketIntelDtos.CompetitorResult competitor : safe(result.competitors())) {
            try {
                if (competitor == null || !StringUtils.hasText(competitor.userId())) {
                    throw new IllegalArgumentException("competitor user_id必填");
                }
                jdbc.update("UPDATE mi_competitor SET nickname=?,red_id=?,avatar_url=?,fans=?,follows=?,last_note_id=?,last_crawled_at=NOW() " +
                                "WHERE merchant_id=? AND provider=? AND account_id=? AND user_id=?",
                        competitor.nickname(), competitor.redId(), competitor.avatarUrl(), nonNegative(competitor.fans()),
                        nonNegative(competitor.follows()), competitor.lastNoteId(), merchantId, provider, accountId, competitor.userId());
            } catch (RuntimeException e) {
                errors++;
                log.warn("忽略无效竞品结果 job={}: {}", jobId, sanitizeMessage(e.getMessage()));
            }
        }
        String finalStatus = "success".equalsIgnoreCase(result.status()) ? "success" : "fail";
        jdbc.update("UPDATE mi_job_run SET status=?,item_count=?,error_count=?,error_msg=?,finished_at=NOW() " +
                        "WHERE id=? AND status IN ('pending','running')",
                finalStatus, saved, errors, shortMessage(result.errorMsg()), jobId);
    }

    private void upsertNote(long merchantId, String provider, String accountId, long jobId, MarketIntelDtos.NoteResult note) {
        String keyword = safe(note.keywords()).stream().filter(StringUtils::hasText).findFirst().orElse(null);
        jdbc.update("""
                INSERT INTO mi_note_raw(merchant_id,provider,account_id,crawl_run_id,note_id,source,keyword,title,note_url,cover_url,user_id,nickname,
                  liked_count,collected_count,comment_count,published_at,crawled_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
                ON DUPLICATE KEY UPDATE keyword=VALUES(keyword),title=VALUES(title),note_url=VALUES(note_url),cover_url=VALUES(cover_url),
                  user_id=VALUES(user_id),nickname=VALUES(nickname),liked_count=VALUES(liked_count),
                  collected_count=VALUES(collected_count),comment_count=VALUES(comment_count),published_at=VALUES(published_at),crawled_at=NOW()
                """, merchantId, provider, accountId, jobId, note.noteId(), note.source(), keyword, note.title(), platforms.sanitizePublicUrl(note.noteUrl()), note.coverUrl(),
                note.userId(), note.nickname(), nonNegative(note.likedCount()), nonNegative(note.collectedCount()),
                nonNegative(note.commentCount()), note.publishedAt() == null ? null : Timestamp.valueOf(note.publishedAt()));
        if ("search".equals(note.source())) {
            for (String word : safe(note.keywords())) {
                if (!StringUtils.hasText(word)) continue;
                jdbc.update("INSERT INTO mi_note_keyword(merchant_id,provider,account_id,crawl_run_id,note_id,keyword) VALUES(?,?,?,?,?,?) " +
                                "ON DUPLICATE KEY UPDATE keyword=VALUES(keyword)",
                        merchantId, provider, accountId, jobId, note.noteId(), word.trim());
            }
        }
    }

    public void aggregatePending() {
        expireStaleJobs();
        List<Long> jobs = jdbc.queryForList("SELECT id FROM mi_job_run WHERE job_type='crawl' AND status='success' AND aggregated_at IS NULL ORDER BY id", Long.class);
        for (Long jobId : jobs) {
            try { aggregateJob(jobId); } catch (Exception e) { log.error("聚合市场情报任务失败 job={}", jobId, e); }
        }
    }

    @Scheduled(fixedDelayString = "${market-intel.watchdog-delay-ms:300000}")
    public void expireStaleJobs() {
        jdbc.update("UPDATE mi_job_run SET status='timeout',finished_at=NOW(),request_token=NULL,error_msg='超过60分钟未完成' " +
                "WHERE job_type='crawl' AND status IN ('pending','running') AND update_time<DATE_SUB(NOW(),INTERVAL 60 MINUTE)");
    }

    public void aggregateJob(long jobId) {
        transactionTemplate.executeWithoutResult(status -> aggregateJobInTransaction(jobId));
    }

    private void aggregateJobInTransaction(long jobId) {
        Map<String, Object> job = jdbc.queryForMap("SELECT merchant_id,provider,account_id,stat_date,status,aggregated_at FROM mi_job_run WHERE id=? FOR UPDATE", jobId);
        if (!"success".equals(job.get("status")) || job.get("aggregated_at") != null) return;
        long merchantId = ((Number) job.get("merchant_id")).longValue();
        String provider = String.valueOf(job.get("provider"));
        String accountId = String.valueOf(job.get("account_id"));
        Date statDate = (Date) job.get("stat_date");
        jdbc.update("DELETE FROM mi_rank_daily WHERE merchant_id=? AND provider=? AND account_id=? AND stat_date=?", merchantId, provider, accountId, statDate);
        jdbc.update("""
                INSERT INTO mi_rank_daily(merchant_id,provider,account_id,keyword,stat_date,rank_no,note_id,crawl_run_id,liked_count,collected_count,comment_count)
                SELECT merchant_id,provider,account_id,keyword,?,rank_no,note_id,?,liked_count,collected_count,comment_count FROM (
                  SELECT nk.merchant_id,nk.provider,nk.account_id,nk.keyword,n.note_id,n.liked_count,n.collected_count,n.comment_count,
                    ROW_NUMBER() OVER(PARTITION BY nk.keyword ORDER BY n.liked_count DESC,n.collected_count DESC,n.note_id) rank_no
                  FROM mi_note_keyword nk JOIN mi_note_raw n ON n.merchant_id=nk.merchant_id AND n.provider=nk.provider AND n.account_id=nk.account_id AND n.crawl_run_id=nk.crawl_run_id
                    AND n.note_id=nk.note_id AND n.source='search'
                  WHERE nk.merchant_id=? AND nk.provider=? AND nk.account_id=? AND nk.crawl_run_id=?
                ) ranked WHERE rank_no<=20
                """, statDate, jobId, merchantId, provider, accountId, jobId);
        jdbc.update("""
                INSERT INTO mi_keyword_daily(merchant_id,provider,account_id,keyword,stat_date,crawl_run_id,note_count,like_sum,heat_score)
                SELECT nk.merchant_id,nk.provider,nk.account_id,nk.keyword,?,?,COUNT(*),SUM(n.liked_count),
                  SUM(LOG(1+n.liked_count+n.collected_count*2+n.comment_count))
                FROM mi_note_keyword nk JOIN mi_note_raw n ON n.merchant_id=nk.merchant_id AND n.provider=nk.provider AND n.account_id=nk.account_id AND n.crawl_run_id=nk.crawl_run_id
                  AND n.note_id=nk.note_id AND n.source='search'
                WHERE nk.merchant_id=? AND nk.provider=? AND nk.account_id=? AND nk.crawl_run_id=? GROUP BY nk.merchant_id,nk.provider,nk.account_id,nk.keyword
                ON DUPLICATE KEY UPDATE crawl_run_id=VALUES(crawl_run_id),note_count=VALUES(note_count),
                  like_sum=VALUES(like_sum),heat_score=VALUES(heat_score),update_time=NOW()
                """, statDate, jobId, merchantId, provider, accountId, jobId);
        jdbc.update("""
                INSERT INTO mi_alert(merchant_id,provider,account_id,keyword,stat_date,pct_change,heat_today,heat_yesterday,status)
                SELECT t.merchant_id,t.provider,t.account_id,t.keyword,t.stat_date,(t.heat_score-y.heat_score)/y.heat_score*100,
                       t.heat_score,y.heat_score,'unread'
                FROM mi_keyword_daily t JOIN mi_keyword_daily y ON y.merchant_id=t.merchant_id AND y.provider=t.provider AND y.account_id=t.account_id AND y.keyword=t.keyword
                  AND y.stat_date=DATE_SUB(t.stat_date,INTERVAL 1 DAY)
                WHERE t.merchant_id=? AND t.provider=? AND t.account_id=? AND t.stat_date=? AND y.heat_score>0 AND t.heat_score/y.heat_score>=?
                ON DUPLICATE KEY UPDATE pct_change=VALUES(pct_change),heat_today=VALUES(heat_today),heat_yesterday=VALUES(heat_yesterday)
                """, merchantId, provider, accountId, statDate, ALERT_RATIO);
        jdbc.update("UPDATE mi_job_run SET aggregated_at=NOW() WHERE id=?", jobId);
    }

    public boolean validSharedToken(String authorization) {
        return StringUtils.hasText(sharedToken) && Objects.equals("Bearer " + sharedToken, authorization);
    }

    private String jobStatus(long jobId) {
        return jdbc.queryForObject("SELECT status FROM mi_job_run WHERE id=?", String.class, jobId);
    }

    private void failJob(long jobId, String message) {
        jdbc.update("UPDATE mi_job_run SET status='fail',error_msg=?,finished_at=NOW(),request_token=NULL WHERE id=? AND status IN ('pending','running')", message, jobId);
    }

    private void validateNote(MarketIntelDtos.NoteResult note) {
        if (note == null || !StringUtils.hasText(note.noteId()) || !StringUtils.hasText(note.userId()) || !StringUtils.hasText(note.nickname())) {
            throw new IllegalArgumentException("note_id/user_id/nickname必填");
        }
        if (!List.of("search", "user").contains(note.source())) throw new IllegalArgumentException("source无效");
        if ("search".equals(note.source()) && safe(note.keywords()).isEmpty()) throw new IllegalArgumentException("搜索结果缺少关键词");
    }

    private String normalizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) throw new IllegalArgumentException("关键词不能为空");
        String value = keyword.trim();
        if (value.length() > 100) throw new IllegalArgumentException("关键词最多100字");
        return value;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private int nonNegative(Integer value) { return value == null ? 0 : Math.max(0, value); }
    private String blankToNull(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private String shortMessage(String value) {
        String sanitized = sanitizeMessage(value);
        return sanitized == null ? null : sanitized.substring(0, Math.min(sanitized.length(), 1000));
    }
    private String sanitizeMessage(String value) {
        if (value == null) return null;
        return value
                .replaceAll("(?i)(cookie|authorization|web_session|xsec_token|access[_-]?token)\\s*[:=]\\s*[^\\s,;]+", "$1=[redacted]")
                .replaceAll("(?i)([?&](?:xsec_token|access_token|token)=)[^&\\s]+", "$1[redacted]");
    }
    private String scopeQuery(String providerValue, String accountValue) {
        return "provider=" + encode(platforms.provider(providerValue)) + "&account_id=" + encode(platforms.account(accountValue));
    }
    private String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
    private <T> List<T> safe(List<T> value) { return value == null ? List.of() : value; }
}
