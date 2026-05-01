package com.clickagg.clickprocessor.service.decorator;

import com.clickagg.clickprocessor.geolocation.GeoIpResolver;
import com.clickagg.clickprocessor.model.ClickRequest;
import com.clickagg.clickprocessor.service.ClickProcessorService;
import lombok.extern.slf4j.Slf4j;

/**
 * Enriches the ClickRequest with ISO 3166-1 alpha-2 country code from raw IP.
 * Resolves BEFORE anonymization so the resolver receives the real IP.
 *
 * If resolution fails (unknown IP, resolver error), country is left null —
 * Flink handles null countries by grouping them as "UNKNOWN".
 */
@Slf4j
public class GeoIpClickProcessorDecorator extends ClickProcessorDecorator {

    private final GeoIpResolver geoIpResolver;

    public GeoIpClickProcessorDecorator(ClickProcessorService delegate, GeoIpResolver geoIpResolver) {
        super(delegate);
        this.geoIpResolver = geoIpResolver;
    }

    @Override
    public String processClick(ClickRequest request) {
        String country = null;
        try {
            country = geoIpResolver.resolve(request.getRawIpAddress());
        } catch (Exception e) {
            log.warn("GeoIP resolution failed for clickId={}: {}", request.getClickId(), e.getMessage());
        }

        ClickRequest enriched = request.toBuilder()
                .country(country)
                .build();

        return delegate.processClick(enriched);
    }
}
