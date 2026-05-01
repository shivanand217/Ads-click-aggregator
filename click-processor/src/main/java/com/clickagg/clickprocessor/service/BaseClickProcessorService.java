package com.clickagg.clickprocessor.service;

import com.clickagg.clickprocessor.idempotency.RedisIdempotencyChecker;
import com.clickagg.clickprocessor.kafka.ClickEventProducer;
import com.clickagg.clickprocessor.model.ClickRequest;
import com.clickagg.clickprocessor.resolver.AdRedirectResolver;
import com.clickagg.schema.ClickEvent;
import com.clickagg.schema.DeviceType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Core implementation — sits at the bottom of the Decorator chain.
 * Receives a fully enriched ClickRequest (country + hashedIp already set).
 *
 * Responsibilities:
 *  1. Idempotency check via Redis (Circuit Breaker wrapped)
 *  2. Publish ClickEvent to Kafka (only if not duplicate)
 *  3. Resolve + return the advertiser redirect URL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BaseClickProcessorService implements ClickProcessorService {

    private final RedisIdempotencyChecker idempotencyChecker;
    private final ClickEventProducer clickEventProducer;
    private final AdRedirectResolver adRedirectResolver;
    private final MeterRegistry meterRegistry;

    @Override
    public String processClick(ClickRequest request) {
        boolean isDuplicate = idempotencyChecker.isDuplicate(request.getClickId());

        if (isDuplicate) {
            log.debug("Duplicate click detected, skipping Kafka publish: clickId={}", request.getClickId());
            meterRegistry.counter("click.processor.duplicates").increment();
        } else {
            ClickEvent event = toClickEvent(request);
            clickEventProducer.publish(event);
            meterRegistry.counter("click.processor.published").increment();
        }

        return adRedirectResolver.resolve(request.getAdId());
    }

    private ClickEvent toClickEvent(ClickRequest req) {
        return ClickEvent.newBuilder()
                .setClickId(req.getClickId())
                .setAdId(req.getAdId())
                .setAdvertiserId(req.getAdvertiserId() != null ? req.getAdvertiserId() : "unknown")
                .setPlacementId(req.getPlacementId())
                .setSessionId(req.getSessionId())
                .setHashedIpAddress(req.getHashedIpAddress() != null ? req.getHashedIpAddress() : "")
                .setUserAgent(req.getUserAgent() != null ? req.getUserAgent() : "")
                .setCountry(req.getCountry())
                .setDeviceType(mapDeviceType(req.getDeviceType()))
                .setTimestampMs(req.getTimestampMs())
                .build();
    }

    private DeviceType mapDeviceType(com.clickagg.clickprocessor.model.DeviceType type) {
        if (type == null) return DeviceType.UNKNOWN;
        return switch (type) {
            case DESKTOP -> DeviceType.DESKTOP;
            case MOBILE  -> DeviceType.MOBILE;
            case TABLET  -> DeviceType.TABLET;
            default      -> DeviceType.UNKNOWN;
        };
    }
}
