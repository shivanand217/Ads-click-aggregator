package com.clickagg.clickprocessor.geolocation;

/**
 * Resolves an IP address to a ISO 3166-1 alpha-2 country code.
 * Stub implementation used now; replace with MaxMind GeoLite2 in production.
 */
public interface GeoIpResolver {

    /**
     * @param ipAddress raw IPv4 or IPv6 address
     * @return 2-letter country code (e.g. "US", "IN"), or null if unresolvable
     */
    String resolve(String ipAddress);
}
