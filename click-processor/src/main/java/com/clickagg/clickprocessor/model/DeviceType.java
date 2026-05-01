package com.clickagg.clickprocessor.model;

/**
 * Application-layer device type enum.
 * Mapped to com.clickagg.schema.DeviceType (Avro-generated) when building ClickEvent.
 */
public enum DeviceType {
    DESKTOP, MOBILE, TABLET, UNKNOWN;

    public static DeviceType fromUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN;
        }
        String ua = userAgent.toLowerCase();
        if (ua.contains("ipad") || ua.contains("tablet") || ua.contains("kindle")) {
            return TABLET;
        }
        if (ua.contains("mobile") || ua.contains("android") || ua.contains("iphone")
                || ua.contains("windows phone") || ua.contains("blackberry")) {
            return MOBILE;
        }
        return DESKTOP;
    }
}
