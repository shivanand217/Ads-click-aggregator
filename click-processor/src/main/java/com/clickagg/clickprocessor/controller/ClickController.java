package com.clickagg.clickprocessor.controller;

import com.clickagg.clickprocessor.model.ClickRequest;
import com.clickagg.clickprocessor.model.DeviceType;
import com.clickagg.clickprocessor.service.ClickProcessorService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Entry point for browser click events.
 *
 * GET /v1/click/{adId}?clickId=...&placementId=...&sessionId=...
 *
 * Flow:
 *  1. Build ClickRequest from path/query params + HTTP headers
 *  2. Delegate to ClickProcessorService chain (Logging → GeoIP → Anon → Base)
 *  3. Return 302 to the resolved advertiser URL
 *
 * Why GET, not POST?
 *  Browser ad clicks are standard anchor href navigations — GET is semantically
 *  correct and works without JavaScript. POST would require JS interception.
 */
@RestController
@RequestMapping("/v1/click")
@RequiredArgsConstructor
public class ClickController {

    private final ClickProcessorService clickProcessorService;

    @GetMapping("/{adId}")
    public ResponseEntity<Void> handleClick(
            @PathVariable String adId,
            @RequestParam(required = false) String clickId,
            @RequestParam(required = false) String placementId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String advertiserId,
            HttpServletRequest request) {

        String resolvedClickId = resolveClickId(clickId, adId, placementId, sessionId);
        String rawIp = extractClientIp(request);
        String userAgent = request.getHeader("User-Agent");

        ClickRequest clickRequest = ClickRequest.builder()
                .clickId(resolvedClickId)
                .adId(adId)
                .advertiserId(advertiserId)
                .placementId(placementId)
                .sessionId(sessionId)
                .rawIpAddress(rawIp)
                .userAgent(userAgent)
                .deviceType(DeviceType.fromUserAgent(userAgent))
                .timestampMs(Instant.now().toEpochMilli())
                .build();

        String redirectUrl = clickProcessorService.processClick(clickRequest);

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, redirectUrl)
                .header("X-Click-Id", resolvedClickId)  // for client-side dedup confirmation
                .build();
    }

    /**
     * If the client provides a clickId (from the ad SDK), use it.
     * Otherwise, generate a deterministic ID from adId+placement+session+5s-bucket.
     * The 5-second window collapses rapid re-clicks from the same session on the same ad.
     */
    private String resolveClickId(String clickId, String adId, String placementId, String sessionId) {
        if (clickId != null && !clickId.isBlank()) {
            return clickId;
        }
        long fiveSecondBucket = Instant.now().toEpochMilli() / 5000;
        String input = adId + ":" + placementId + ":" + sessionId + ":" + fiveSecondBucket;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(hash);
            // Format as UUID for consistency with client-generated IDs
            return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" +
                   hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32);
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString();
        }
    }

    /**
     * Extracts the real client IP, respecting common reverse-proxy headers.
     * X-Forwarded-For is used by NGINX/AWS ALB/GCP LB.
     */
    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // XFF can be a comma-separated list; first entry is the real client IP
            return xff.split(",")[0].strip();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.strip();
        }
        return request.getRemoteAddr();
    }
}
