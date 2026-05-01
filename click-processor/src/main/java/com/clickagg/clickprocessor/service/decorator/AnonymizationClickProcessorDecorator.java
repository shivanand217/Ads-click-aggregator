package com.clickagg.clickprocessor.service.decorator;

import com.clickagg.clickprocessor.model.ClickRequest;
import com.clickagg.clickprocessor.service.ClickProcessorService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashes the raw IP address (SHA-256) before it reaches the base service.
 * After this decorator runs, rawIpAddress is NEVER used downstream —
 * only hashedIpAddress is passed to Kafka.
 *
 * Must run AFTER GeoIpDecorator (which needs the raw IP for country resolution).
 * The chain is wired in ClickProcessorBeanConfig to guarantee this order.
 */
public class AnonymizationClickProcessorDecorator extends ClickProcessorDecorator {

    public AnonymizationClickProcessorDecorator(ClickProcessorService delegate) {
        super(delegate);
    }

    @Override
    public String processClick(ClickRequest request) {
        String hashedIp = hashIp(request.getRawIpAddress());

        ClickRequest anonymized = request.toBuilder()
                .hashedIpAddress(hashedIp)
                .build();

        return delegate.processClick(anonymized);
    }

    private String hashIp(String rawIp) {
        if (rawIp == null || rawIp.isBlank()) {
            return "unknown";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawIp.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JVM spec — unreachable
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
