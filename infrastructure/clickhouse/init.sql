-- ============================================================
-- ClickHouse schema for ad click aggregation
-- Engine: ReplacingMergeTree — idempotent writes from Flink.
-- If Flink re-emits a window aggregate on recovery,
-- ClickHouse replaces the old row instead of doubling the count.
-- ============================================================

CREATE DATABASE IF NOT EXISTS clickagg;

-- 1-minute granularity base table (Flink writes here)
CREATE TABLE IF NOT EXISTS clickagg.clicks_1min
(
    window_start     DateTime         COMMENT 'Start of the 1-minute tumbling window (event time)',
    ad_id            String           COMMENT 'The ad that received clicks',
    advertiser_id    String           COMMENT 'Advertiser who owns the ad',
    country          LowCardinality(String) DEFAULT 'UNKNOWN' COMMENT 'ISO 3166-1 alpha-2',
    device_type      LowCardinality(String) DEFAULT 'UNKNOWN' COMMENT 'DESKTOP|MOBILE|TABLET|UNKNOWN',
    click_count      UInt64           COMMENT 'Deduplicated click count for this window',
    unique_sessions  UInt64           COMMENT 'Approximate unique session count (HyperLogLog)'
)
ENGINE = ReplacingMergeTree(window_start)
PARTITION BY toYYYYMM(window_start)
ORDER BY (advertiser_id, ad_id, window_start, country, device_type)
TTL window_start + INTERVAL 2 YEAR
SETTINGS index_granularity = 8192;

-- Hourly rollup — materialized view over clicks_1min
-- Analytics Service uses this for hour/day granularity queries
CREATE MATERIALIZED VIEW IF NOT EXISTS clickagg.clicks_hourly
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(window_hour)
ORDER BY (advertiser_id, ad_id, window_hour, country, device_type)
TTL window_hour + INTERVAL 2 YEAR
AS
SELECT
    toStartOfHour(window_start)  AS window_hour,
    ad_id,
    advertiser_id,
    country,
    device_type,
    sum(click_count)             AS click_count,
    sum(unique_sessions)         AS unique_sessions
FROM clickagg.clicks_1min
GROUP BY window_hour, ad_id, advertiser_id, country, device_type;

-- Daily rollup — materialized view over clicks_hourly
CREATE MATERIALIZED VIEW IF NOT EXISTS clickagg.clicks_daily
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(window_day)
ORDER BY (advertiser_id, ad_id, window_day, country, device_type)
TTL window_day + INTERVAL 2 YEAR
AS
SELECT
    toStartOfDay(window_hour)    AS window_day,
    ad_id,
    advertiser_id,
    country,
    device_type,
    sum(click_count)             AS click_count,
    sum(unique_sessions)         AS unique_sessions
FROM clickagg.clicks_hourly
GROUP BY window_day, ad_id, advertiser_id, country, device_type;
