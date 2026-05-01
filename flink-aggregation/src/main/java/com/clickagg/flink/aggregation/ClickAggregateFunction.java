package com.clickagg.flink.aggregation;

import com.clickagg.flink.model.AggregatedClick;
import com.clickagg.schema.ClickEvent;
import org.apache.flink.api.common.functions.AggregateFunction;

/**
 * Flink AggregateFunction — core per-window aggregation logic.
 *
 * Called by Flink's window operator for every event in a tumbling window.
 * The accumulator is the live state; add() is called once per event.
 * getResult() is called once per window close to emit the final aggregate.
 *
 * Deduplication:
 *   add() checks seenClickIds before incrementing clickCount.
 *   This is the secondary dedup layer (Redis is primary).
 *   It catches duplicates that slipped through during a Redis circuit-open period.
 *
 * Note: windowStartMs/windowEndMs are NOT set here — they are attached by
 * WindowMetadataFunction which has access to the TimeWindow context.
 * The aggregate() + process() overload wires both together.
 */
public class ClickAggregateFunction
        implements AggregateFunction<ClickEvent, ClickAccumulator, AggregatedClick> {

    @Override
    public ClickAccumulator createAccumulator() {
        return new ClickAccumulator();
    }

    @Override
    public ClickAccumulator add(ClickEvent event, ClickAccumulator acc) {
        // Secondary dedup: only count clicks not already seen in this window
        boolean isNew = acc.seenClickIds.add(event.getClickId());
        if (isNew) {
            acc.clickCount++;
        }

        // Offer sessionId to HyperLogLog (HLL offer is naturally idempotent)
        if (event.getSessionId() != null) {
            acc.uniqueSessionHll.offer(event.getSessionId());
        }

        // Set window grouping metadata from the first event
        if (acc.adId == null) {
            acc.adId          = event.getAdId();
            acc.advertiserId  = event.getAdvertiserId();
            acc.country       = event.getCountry() != null ? event.getCountry() : "UNKNOWN";
            acc.deviceType    = event.getDeviceType() != null
                                    ? event.getDeviceType().toString()
                                    : "UNKNOWN";
        }

        return acc;
    }

    @Override
    public AggregatedClick getResult(ClickAccumulator acc) {
        // windowStartMs and windowEndMs are populated by WindowMetadataFunction
        return AggregatedClick.builder()
                .adId(acc.adId)
                .advertiserId(acc.advertiserId)
                .country(acc.country)
                .deviceType(acc.deviceType)
                .clickCount(acc.clickCount)
                .uniqueSessions(acc.getUniqueSessions())
                .build();
    }

    @Override
    public ClickAccumulator merge(ClickAccumulator a, ClickAccumulator b) {
        // Called during session windows and when merging partial results for allowed lateness
        a.mergeFrom(b);
        return a;
    }
}
