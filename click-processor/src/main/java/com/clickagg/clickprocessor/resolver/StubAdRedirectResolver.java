package com.clickagg.clickprocessor.resolver;

import org.springframework.stereotype.Component;

/**
 * Stub resolver for Phase 1 development.
 * Returns a placeholder URL that includes the adId for traceability.
 *
 * Will be replaced by CachedAdServiceRedirectResolver in the Ad Service phase,
 * which calls Ad Service HTTP API with a Redis cache (TTL 60s).
 */
@Component
public class StubAdRedirectResolver implements AdRedirectResolver {

    @Override
    public String resolve(String adId) {
        return "https://example.com?ad_id=" + adId + "&source=clickagg";
    }
}
