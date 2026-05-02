package com.clickagg.flink.sink;

import com.clickagg.flink.model.AggregatedClick;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;

import java.sql.Timestamp;

/**
 * Factory for the ClickHouse JDBC sink.
 *
 * Inserts into clicks_1min using batch mode for throughput:
 *   - batchSize=500: flush after 500 rows accumulate
 *   - batchIntervalMs=5000: flush every 5 seconds regardless of batch size
 *
 * Why at-least-once (not exactly-once)?
 *   ClickHouse doesn't support XA transactions (required for Flink exactly-once JDBC).
 *   Instead, we use ReplacingMergeTree — if Flink re-emits a window on recovery,
 *   the INSERT overwrites the existing row (same ORDER BY key) on next ClickHouse merge.
 *   For immediate dedup in queries, use: SELECT ... FROM clicks_1min FINAL
 *
 * INSERT statement:
 *   Uses positional parameters in the same column order as the table definition.
 *   window_start is inserted as DateTime (ClickHouse stores as Unix timestamp seconds).
 */
public class ClickHouseJdbcSink {

    private static final String INSERT_SQL =
            "INSERT INTO clickagg.clicks_1min " +
            "(window_start, ad_id, advertiser_id, country, device_type, click_count, unique_sessions) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)";

    public static SinkFunction<AggregatedClick> create(
            String clickHouseUrl,
            String user,
            String password) {

        return JdbcSink.sink(
                INSERT_SQL,
                (stmt, agg) -> {
                    stmt.setTimestamp(1, new Timestamp(agg.getWindowStartMs()));
                    stmt.setString(2, agg.getAdId());
                    stmt.setString(3, agg.getAdvertiserId());
                    stmt.setString(4, agg.getCountry() != null ? agg.getCountry() : "UNKNOWN");
                    stmt.setString(5, agg.getDeviceType() != null ? agg.getDeviceType() : "UNKNOWN");
                    stmt.setLong(6, agg.getClickCount());
                    stmt.setLong(7, agg.getUniqueSessions());
                },
                JdbcExecutionOptions.builder()
                        .withBatchSize(500)
                        .withBatchIntervalMs(5000)
                        .withMaxRetries(3)
                        .build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl(clickHouseUrl)
                        .withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                        .withUsername(user)
                        .withPassword(password)
                        .build()
        );
    }
}
