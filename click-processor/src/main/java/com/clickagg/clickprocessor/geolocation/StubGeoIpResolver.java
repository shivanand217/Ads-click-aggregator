package com.clickagg.clickprocessor.geolocation;

import org.springframework.stereotype.Component;

/**
 * Stub GeoIP resolver for local dev and testing.
 * Returns "LOCAL" for private/loopback ranges, "US" for everything else.
 *
 * Replace this bean with a MaxMind GeoLite2 implementation for production:
 *   - Add dependency: com.maxmind.geoip2:geoip2
 *   - Download GeoLite2-Country.mmdb from MaxMind
 *   - Implement GeoIpResolver using DatabaseReader
 *   - Register as @Primary @Profile("!stub")
 */
@Component
public class StubGeoIpResolver implements GeoIpResolver {

    @Override
    public String resolve(String ipAddress) {
        if (ipAddress == null) return null;
        if (ipAddress.startsWith("127.") || ipAddress.startsWith("10.")
                || ipAddress.startsWith("192.168.") || ipAddress.equals("::1")) {
            return "LOCAL";
        }
        return "US";
    }
}
