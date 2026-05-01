package com.clickagg.flink.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Output of a 1-minute tumbling window aggregation.
 * Written to ClickHouse clicks_1min table and the aggregated-clicks Kafka topic.
 *
 * windowStartMs / windowEndMs are set by WindowMetadataFunction after aggregation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AggregatedClick implements Serializable {

    private String adId;
    private String advertiserId;
    private String country;
    private String deviceType;

    /** Deduplicated click count for the window (duplicates removed in ClickAccumulator) */
    private long clickCount;

    /**
     * Approximate unique session count using HyperLogLog (~2% error).
     * Exact count would require storing all session IDs — impractical at scale.
     */
    private long uniqueSessions;

    /** Inclusive start of the tumbling window (event time, epoch ms) */
    private long windowStartMs;

    /** Exclusive end of the tumbling window (event time, epoch ms) */
    private long windowEndMs;
}
