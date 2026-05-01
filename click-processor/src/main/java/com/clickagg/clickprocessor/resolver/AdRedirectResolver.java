package com.clickagg.clickprocessor.resolver;

/**
 * Resolves the advertiser destination URL for a given adId.
 *
 * In Phase 1 (click ingestion only): StubAdRedirectResolver returns a placeholder.
 * In the full system: replaced by an implementation that calls Ad Service
 * (with Redis cache) to look up destination_url from the Ad DB.
 */
public interface AdRedirectResolver {

    /**
     * @param adId the ad that was clicked
     * @return the destination URL for the 302 redirect
     */
    String resolve(String adId);
}
