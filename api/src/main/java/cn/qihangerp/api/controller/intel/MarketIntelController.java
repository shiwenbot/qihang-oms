package cn.qihangerp.api.controller.intel;

import cn.qihangerp.common.AjaxResult;
import cn.qihangerp.enums.EnumUserType;
import cn.qihangerp.security.common.BaseController;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/intel")
public class MarketIntelController extends BaseController {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final MarketIntelService service;

    @GetMapping("/rank")
    @PreAuthorize("@ss.hasPermi('intel:rank:list')")
    public AjaxResult rank(@RequestParam(required = false) String keyword,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                           @RequestParam(required = false) Long merchantId,
                           @RequestParam(defaultValue = "xiaohongshu") String provider,
                           @RequestParam(defaultValue = "default") String accountId) {
        return success(service.rank(merchant(merchantId), provider, accountId, keyword, date == null ? LocalDate.now(SHANGHAI) : date));
    }

    @GetMapping("/trend")
    @PreAuthorize("@ss.hasPermi('intel:trend:list')")
    public AjaxResult trend(@RequestParam String keyword, @RequestParam(defaultValue = "14") int days,
                            @RequestParam(required = false) Long merchantId,
                            @RequestParam(defaultValue = "xiaohongshu") String provider,
                            @RequestParam(defaultValue = "default") String accountId) {
        return success(service.trend(merchant(merchantId), provider, accountId, keyword, days));
    }

    @GetMapping("/competitor")
    @PreAuthorize("@ss.hasPermi('intel:competitor:list')")
    public AjaxResult competitors(@RequestParam(required = false) Long merchantId,
                                  @RequestParam(defaultValue = "xiaohongshu") String provider,
                                  @RequestParam(defaultValue = "default") String accountId) {
        return success(service.competitors(merchant(merchantId), provider, accountId));
    }

    @GetMapping("/competitor/{id}/notes")
    @PreAuthorize("@ss.hasPermi('intel:competitor:list')")
    public AjaxResult competitorNotes(@PathVariable long id, @RequestParam(required = false) Long merchantId,
                                      @RequestParam(defaultValue = "xiaohongshu") String provider,
                                      @RequestParam(defaultValue = "default") String accountId) {
        return success(service.competitorNotes(merchant(merchantId), provider, accountId, id));
    }

    @PostMapping("/competitor")
    @PreAuthorize("@ss.hasPermi('intel:competitor:add')")
    public AjaxResult addCompetitor(@RequestBody MarketIntelDtos.CompetitorRequest request,
                                    @RequestParam(required = false) Long merchantId) {
        try { return success(Map.of("id", service.addCompetitor(merchant(merchantId), request))); }
        catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    @PostMapping("/competitor/preview")
    @PreAuthorize("@ss.hasPermi('intel:competitor:add')")
    public AjaxResult previewCompetitor(@RequestBody MarketIntelDtos.CompetitorRequest request) {
        requireIntelAccess();
        try { return success(service.previewCompetitor(request)); }
        catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    @DeleteMapping("/competitor/{id}")
    @PreAuthorize("@ss.hasPermi('intel:competitor:remove')")
    public AjaxResult deleteCompetitor(@PathVariable long id, @RequestParam(required = false) Long merchantId,
                                       @RequestParam(defaultValue = "xiaohongshu") String provider,
                                       @RequestParam(defaultValue = "default") String accountId) {
        try { service.deleteCompetitor(merchant(merchantId), provider, accountId, id); return success(); }
        catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    @GetMapping("/alert")
    @PreAuthorize("@ss.hasPermi('intel:alert:list')")
    public AjaxResult alerts(@RequestParam(required = false) Long merchantId,
                             @RequestParam(defaultValue = "xiaohongshu") String provider,
                             @RequestParam(defaultValue = "default") String accountId) {
        return success(service.alerts(merchant(merchantId), provider, accountId));
    }

    @PutMapping("/alert/{id}/read")
    @PreAuthorize("@ss.hasPermi('intel:alert:edit')")
    public AjaxResult readAlert(@PathVariable long id, @RequestParam(required = false) Long merchantId,
                                @RequestParam(defaultValue = "xiaohongshu") String provider,
                                @RequestParam(defaultValue = "default") String accountId) {
        try { service.readAlert(merchant(merchantId), provider, accountId, id); return success(); }
        catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    @GetMapping("/keyword")
    @PreAuthorize("@ss.hasPermi('intel:config:list')")
    public AjaxResult keywords(@RequestParam(required = false) Long merchantId,
                               @RequestParam(defaultValue = "xiaohongshu") String provider,
                               @RequestParam(defaultValue = "default") String accountId) {
        return success(service.keywords(merchant(merchantId), provider, accountId));
    }

    @PostMapping("/keyword")
    @PreAuthorize("@ss.hasPermi('intel:config:add')")
    public AjaxResult addKeyword(@RequestBody MarketIntelDtos.KeywordRequest request,
                                 @RequestParam(required = false) Long merchantId,
                                 @RequestParam(defaultValue = "xiaohongshu") String provider,
                                 @RequestParam(defaultValue = "default") String accountId) {
        try { return success(Map.of("id", service.addKeyword(merchant(merchantId), provider, accountId, getUsername(), request))); }
        catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    @PutMapping("/keyword/{id}")
    @PreAuthorize("@ss.hasPermi('intel:config:edit')")
    public AjaxResult updateKeyword(@PathVariable long id, @RequestBody MarketIntelDtos.KeywordRequest request,
                                    @RequestParam(required = false) Long merchantId,
                                    @RequestParam(defaultValue = "xiaohongshu") String provider,
                                    @RequestParam(defaultValue = "default") String accountId) {
        try { service.updateKeyword(merchant(merchantId), provider, accountId, id, request); return success(); }
        catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    @DeleteMapping("/keyword/{id}")
    @PreAuthorize("@ss.hasPermi('intel:config:remove')")
    public AjaxResult deleteKeyword(@PathVariable long id, @RequestParam(required = false) Long merchantId,
                                    @RequestParam(defaultValue = "xiaohongshu") String provider,
                                    @RequestParam(defaultValue = "default") String accountId) {
        try { service.deleteKeyword(merchant(merchantId), provider, accountId, id); return success(); }
        catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    @GetMapping("/status")
    @PreAuthorize("@ss.hasPermi('intel:config:list')")
    public AjaxResult status(@RequestParam(required = false) Long merchantId,
                             @RequestParam(defaultValue = "xiaohongshu") String provider,
                             @RequestParam(defaultValue = "default") String accountId) {
        Map<String, Object> result = service.status(merchant(merchantId), provider, accountId);
        result.put("can_manage_auth", isSuper());
        return success(result);
    }

    @PostMapping("/auth/qrcode")
    @PreAuthorize("@ss.hasPermi('intel:config:auth')")
    public AjaxResult startQrLogin(@RequestParam(defaultValue = "xiaohongshu") String provider,
                                   @RequestParam(defaultValue = "default") String accountId) {
        requireSuper();
        try { return success(service.startQrLogin(provider, accountId)); }
        catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    @GetMapping("/auth/qrcode/status")
    @PreAuthorize("@ss.hasPermi('intel:config:auth')")
    public AjaxResult qrLoginStatus(@RequestParam String sessionId,
                                    @RequestParam(defaultValue = "xiaohongshu") String provider,
                                    @RequestParam(defaultValue = "default") String accountId) {
        requireSuper();
        try { return success(service.qrLoginStatus(provider, accountId, sessionId)); }
        catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    @DeleteMapping("/auth")
    @PreAuthorize("@ss.hasPermi('intel:config:auth')")
    public AjaxResult logoutCollector(@RequestParam(defaultValue = "xiaohongshu") String provider,
                                      @RequestParam(defaultValue = "default") String accountId) {
        requireSuper();
        try { service.logoutCollector(provider, accountId); return success(); }
        catch (IllegalArgumentException e) { return error(e.getMessage()); }
    }

    @PostMapping("/run")
    @PreAuthorize("@ss.hasPermi('intel:config:run')")
    public AjaxResult run(@RequestBody(required = false) MarketIntelDtos.RunRequest request) {
        return success(service.createJob(merchant(request == null ? null : request.merchantId()),
                request == null ? null : request.provider(), request == null ? null : request.accountId()));
    }

    private long merchant(Long explicitlySelected) {
        requireIntelAccess();
        if (isSuper()) {
            return explicitlySelected == null ? 0L : explicitlySelected;
        }
        Long current = getLoginUser().getUser().getMerchantId();
        if (current == null) throw new IllegalArgumentException("当前用户未绑定商户");
        return current;
    }

    private void requireIntelAccess() {
        Integer identity = getLoginUser().getUserIdentity();
        if (identity == null || (identity != EnumUserType.SUPPER.getIndex()
                && identity != EnumUserType.MERCHANT.getIndex())) {
            throw new AccessDeniedException("无权访问市场情报");
        }
    }

    private void requireSuper() {
        if (!isSuper()) throw new AccessDeniedException("仅总部管理员可管理采集账号");
    }

    private boolean isSuper() {
        Integer identity = getLoginUser().getUserIdentity();
        return identity != null && identity == EnumUserType.SUPPER.getIndex();
    }
}
