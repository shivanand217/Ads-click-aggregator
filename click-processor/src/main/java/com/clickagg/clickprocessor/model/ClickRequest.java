package com.clickagg.clickprocessor.model;

import lombok.Builder;
import lombok.Getter;

/**
 * Immutable model flowing through the Decorator chain.
 * Each decorator enriches it by calling toBuilder() and setting its field.
 *
 * Flow:
 *   Controller builds with raw fields →
 *   GeoIpDecorator adds country →
 *   AnonymizationDecorator adds hashedIpAddress →
 *   BaseService builds ClickEvent from the fully enriched request
 */
@Getter
@Builder(toBuilder = true)
public class ClickRequest {

    // --- Core identity ---
    private final String clickId;
    private final String adId;
    private final String advertiserId;   // optional; populated by Ad Service in future
    private final String placementId;
    private final String sessionId;

    // --- Network info ---
    private final String rawIpAddress;          // set by controller; not sent to Kafka
    private final String hashedIpAddress;       // set by AnonymizationDecorator

    // --- Device info ---
    private final String userAgent;
    private final DeviceType deviceType;        // derived from userAgent in controller

    // --- Enriched by decorators ---
    private final String country;               // set by GeoIpDecorator

    // --- Timing ---
    private final long timestampMs;
}
