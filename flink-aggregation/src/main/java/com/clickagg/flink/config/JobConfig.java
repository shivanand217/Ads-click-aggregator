package com.clickagg.flink.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.Properties;

/**
 * Immutable job configuration loaded from flink-job.properties
 * with environment variable overrides (FLINK_* prefix).
 *
 * Serializable so Flink can ship it to TaskManagers.
 */
@Getter
@Slf4j
public class JobConfig implements Serializable {

    private final String kafkaBootstrapServers;
    private final String rawClicksTopic;
    private final String aggregatedClicksTopic;
    private final String kafkaConsumerGroup;
    private final String schemaRegistryUrl;
    private final String clickHouseUrl;
    private final String clickHouseUser;
    private final String clickHousePassword;
    private final int windowSizeMinutes;
    private final int allowedLatenessSeconds;
    private final int checkpointIntervalMs;

    private JobConfig(Properties props) {
        this.kafkaBootstrapServers  = get(props, "kafka.bootstrap.servers",       "localhost:9092");
        this.rawClicksTopic         = get(props, "kafka.topic.raw-clicks",         "raw-clicks");
        this.aggregatedClicksTopic  = get(props, "kafka.topic.aggregated-clicks",  "aggregated-clicks");
        this.kafkaConsumerGroup     = get(props, "kafka.consumer.group",           "flink-click-aggregator");
        this.schemaRegistryUrl      = get(props, "schema.registry.url",            "http://localhost:8081");
        this.clickHouseUrl          = get(props, "clickhouse.url",                 "jdbc:ch://localhost:8123/clickagg");
        this.clickHouseUser         = get(props, "clickhouse.user",                "clickagg");
        this.clickHousePassword     = get(props, "clickhouse.password",            "clickagg");
        this.windowSizeMinutes      = getInt(props, "window.size.minutes",         1);
        this.allowedLatenessSeconds = getInt(props, "allowed.lateness.seconds",    30);
        this.checkpointIntervalMs   = getInt(props, "checkpoint.interval.ms",      30000);
    }

    public static JobConfig load() {
        Properties props = new Properties();

        // 1. Load defaults from classpath
        try (InputStream is = JobConfig.class.getClassLoader()
                .getResourceAsStream("flink-job.properties")) {
            if (is != null) {
                props.load(is);
                log.info("Loaded flink-job.properties from classpath");
            }
        } catch (IOException e) {
            log.warn("Could not load flink-job.properties, using defaults: {}", e.getMessage());
        }

        // 2. Override with FLINK_* environment variables
        // e.g. FLINK_KAFKA_BOOTSTRAP_SERVERS → kafka.bootstrap.servers
        System.getenv().forEach((key, value) -> {
            if (key.startsWith("FLINK_")) {
                String propKey = key.substring(6).toLowerCase().replace("_", ".");
                props.setProperty(propKey, value);
                log.debug("Config override from env: {}={}", propKey, value);
            }
        });

        return new JobConfig(props);
    }

    private static String get(Properties props, String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }

    private static int getInt(Properties props, String key, int defaultValue) {
        String val = props.getProperty(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid integer for {}: '{}', using default {}", key, val, defaultValue);
            return defaultValue;
        }
    }
}
