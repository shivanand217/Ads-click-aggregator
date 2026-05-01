package com.clickagg.flink.aggregation;

import com.clearspring.analytics.stream.cardinality.HyperLogLog;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

/**
 * Mutable state accumulated within a single 1-minute tumbling window per key.
 * Key = adId + "|" + country + "|" + deviceType
 *
 * Two types of state:
 *
 * 1. seenClickIds (HashSet)
 *    Tracks every clickId seen in this window. On add(), if the clickId is already
 *    in the set we skip incrementing clickCount — secondary dedup layer after Redis.
 *    Memory: bounded by (clicks/min per key) × (UUID size ~36 bytes).
 *    For most adIds this is tiny (1–10 clicks/min). High-traffic ads might see
 *    hundreds — still well within per-TaskManager memory budget.
 *
 * 2. uniqueSessionHll (HyperLogLog)
 *    Approximate distinct session count using HLL with precision 14 (~16KB, ~1.6% error).
 *    Storing exact session IDs would need the same memory as seenClickIds but for sessions,
 *    which can be much larger (sessions span multiple ads). HLL gives us cardinality
 *    in constant space — the classic stream analytics trade-off.
 *
 * HyperLogLog serialization:
 *    HyperLogLog from clearspring IS Serializable via Java serialization,
 *    and Flink's Kryo fallback handles it for checkpointing. In production,
 *    implement a custom TypeSerializer for better performance.
 */
@Slf4j
public class ClickAccumulator implements Serializable {

    // Window grouping metadata (set from first event seen in window)
    String adId;
    String advertiserId;
    String country;
    String deviceType;

    // Deduplication state
    Set<String> seenClickIds = new HashSet<>();

    // Count state
    long clickCount = 0L;

    // Unique session approximation
    HyperLogLog uniqueSessionHll;

    // HLL bytes — used during merge() to reconstruct the sketch
    // (HyperLogLog.merge() requires the same precision on both sides)
    private static final int HLL_PRECISION = 14; // 16KB, ~1.6% std error

    public ClickAccumulator() {
        this.uniqueSessionHll = new HyperLogLog(HLL_PRECISION);
    }

    /**
     * Merge another accumulator into this one (used for session windows and allowed lateness).
     */
    public void mergeFrom(ClickAccumulator other) {
        // Merge click counts (re-deduplicate across merged sets)
        for (String clickId : other.seenClickIds) {
            if (seenClickIds.add(clickId)) {
                clickCount++;
            }
        }

        // Merge HLL sketches
        try {
            byte[] otherBytes = other.uniqueSessionHll.getBytes();
            HyperLogLog otherHll = HyperLogLog.Builder.build(otherBytes);
            this.uniqueSessionHll.addAll(otherHll);
        } catch (IOException e) {
            log.warn("Failed to merge HyperLogLog sketches, unique session count may be slightly off: {}", e.getMessage());
        }

        // Use first non-null metadata
        if (this.adId == null && other.adId != null) {
            this.adId = other.adId;
            this.advertiserId = other.advertiserId;
            this.country = other.country;
            this.deviceType = other.deviceType;
        }
    }

    long getUniqueSessions() {
        return uniqueSessionHll.cardinality();
    }
}
