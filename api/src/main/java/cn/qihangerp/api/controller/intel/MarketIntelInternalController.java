package cn.qihangerp.api.controller.intel;

import cn.qihangerp.common.AjaxResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/intel/jobs")
public class MarketIntelInternalController {
    private final MarketIntelService service;

    @PostMapping("/{id}/result")
    public ResponseEntity<AjaxResult> result(@PathVariable long id,
                                             @RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestBody MarketIntelDtos.JobResult result,
                                             HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr()) || !service.validSharedToken(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(AjaxResult.error(401, "unauthorized"));
        }
        try {
            service.acceptResult(id, result);
            return ResponseEntity.ok(AjaxResult.success());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(AjaxResult.error(401, "unauthorized"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(AjaxResult.error(400, e.getMessage()));
        }
    }

    private boolean isLoopback(String address) {
        return "127.0.0.1".equals(address) || "0:0:0:0:0:0:0:1".equals(address) || "::1".equals(address);
    }
}
