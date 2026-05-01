package com.clickagg.clickprocessor.service;

import com.clickagg.clickprocessor.model.ClickRequest;

/**
 * Core service contract for processing a click event.
 * Returns the destination URL the browser should be redirected to (302).
 * Implementations form a Decorator chain:
 *   LoggingDecorator → GeoIpDecorator → AnonymizationDecorator → BaseClickProcessorService
 */
public interface ClickProcessorService {

    /**
     * Processes the click: idempotency check, Kafka publish, redirect URL resolution.
     *
     * @param request enriched click request (progressively filled by decorators)
     * @return the advertiser destination URL for the 302 redirect
     */
    String processClick(ClickRequest request);
}
