package com.clickagg.clickprocessor.config;

import com.clickagg.clickprocessor.geolocation.GeoIpResolver;
import com.clickagg.clickprocessor.service.BaseClickProcessorService;
import com.clickagg.clickprocessor.service.ClickProcessorService;
import com.clickagg.clickprocessor.service.decorator.AnonymizationClickProcessorDecorator;
import com.clickagg.clickprocessor.service.decorator.GeoIpClickProcessorDecorator;
import com.clickagg.clickprocessor.service.decorator.LoggingClickProcessorDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the Decorator chain explicitly so the order is visible in one place.
 *
 * Chain (outer → inner):
 *   LoggingDecorator
 *     → GeoIpDecorator        (adds country from raw IP)
 *       → AnonymizationDecorator (hashes raw IP → SHA-256)
 *         → BaseClickProcessorService (idempotency check, Kafka publish, redirect)
 *
 * Why explicit wiring instead of @Component on each decorator?
 * Decorators injected via @Component would need @Order or @Qualifier hacks.
 * Explicit construction here is readable, refactor-safe, and testable
 * (the chain can be built directly in unit tests without a Spring context).
 *
 * @Primary ensures ClickController gets the full chain when injecting ClickProcessorService.
 * BaseClickProcessorService is still available as a bean for direct injection in this config.
 */
@Configuration
public class ClickProcessorBeanConfig {

    @Bean
    @Primary
    public ClickProcessorService clickProcessorService(
            BaseClickProcessorService base,
            GeoIpResolver geoIpResolver) {

        return new LoggingClickProcessorDecorator(
                new GeoIpClickProcessorDecorator(
                        new AnonymizationClickProcessorDecorator(base),
                        geoIpResolver));
    }
}
