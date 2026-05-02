package com.clickagg.flink.aggregation;

import com.clickagg.flink.model.AggregatedClick;
import com.clickagg.schema.ClickEvent;
import com.clickagg.schema.DeviceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ClickAggregateFunction — the core per-window aggregation logic.
 * Tests run without a Flink cluster (no MiniCluster needed) because AggregateFunction
 * is a plain interface — we just call add() / getResult() / merge() directly.
 */
class ClickAggregateFunctionTest {

    private ClickAggregateFunction fn;

    @BeforeEach
    void setUp() {
        // Fresh function instance per test — AggregateFunction is stateless, state lives in accumulator
        fn = new ClickAggregateFunction();
    }

    @Test
    void add_uniqueClicks_countedCorrectly() {
        // Verifies that distinct clickIds each increment the counter exactly once
        ClickAccumulator acc = fn.createAccumulator();

        fn.add(buildEvent("click-1", "ad-123", "sess-A"), acc);
        fn.add(buildEvent("click-2", "ad-123", "sess-B"), acc);
        fn.add(buildEvent("click-3", "ad-123", "sess-C"), acc);

        AggregatedClick result = fn.getResult(acc);
        assertThat(result.getClickCount()).isEqualTo(3);
    }

    @Test
    void add_duplicateClickId_countedOnce() {
        // Verifies secondary dedup layer: same clickId arriving multiple times in one window
        // is counted only once regardless of how many times add() is called
        ClickAccumulator acc = fn.createAccumulator();

        fn.add(buildEvent("click-dup", "ad-123", "sess-A"), acc);
        fn.add(buildEvent("click-dup", "ad-123", "sess-A"), acc); // Redis was down — this slipped through
        fn.add(buildEvent("click-dup", "ad-123", "sess-A"), acc); // third duplicate

        AggregatedClick result = fn.getResult(acc);
        assertThat(result.getClickCount()).isEqualTo(1); // HashSet in accumulator caught it
    }

    @Test
    void add_uniqueSessions_approximatedByHll() {
        // Verifies HyperLogLog tracks distinct sessionIds (not click count)
        // sess-A is offered twice but HLL cardinality should still return 3
        ClickAccumulator acc = fn.createAccumulator();

        fn.add(buildEvent("c1", "ad-1", "sess-A"), acc);
        fn.add(buildEvent("c2", "ad-1", "sess-B"), acc);
        fn.add(buildEvent("c3", "ad-1", "sess-C"), acc);
        fn.add(buildEvent("c4", "ad-1", "sess-A"), acc); // repeated session, different click

        AggregatedClick result = fn.getResult(acc);
        // HLL is approximate but exact for small cardinalities (no false positives below ~100 elements)
        assertThat(result.getUniqueSessions()).isEqualTo(3);
    }

    @Test
    void add_setsMetadataFromFirstEvent() {
        // Verifies window grouping metadata (adId, country, deviceType) is captured from
        // the first event and not overwritten by subsequent events in the same window
        ClickAccumulator acc = fn.createAccumulator();

        fn.add(buildEvent("c1", "ad-abc", "sess-1"), acc);
        fn.add(buildEvent("c2", "ad-abc", "sess-2"), acc);

        AggregatedClick result = fn.getResult(acc);
        assertThat(result.getAdId()).isEqualTo("ad-abc");
        assertThat(result.getAdvertiserId()).isEqualTo("adv-1");
        assertThat(result.getCountry()).isEqualTo("US");
        assertThat(result.getDeviceType()).isEqualTo("DESKTOP");
    }

    @Test
    void add_nullCountry_defaultsToUnknown() {
        // Verifies null-safe country handling — GeoIP may fail to resolve some IPs
        ClickAccumulator acc = fn.createAccumulator();
        ClickEvent event = buildEvent("c1", "ad-1", "sess-1");
        event.setCountry(null); // GeoIpDecorator returned null (unresolvable IP)

        fn.add(event, acc);

        AggregatedClick result = fn.getResult(acc);
        // Null country becomes "UNKNOWN" so ClickHouse row is valid (non-null String column)
        assertThat(result.getCountry()).isEqualTo("UNKNOWN");
    }

    @Test
    void merge_combinesCountsAndDeduplicates() {
        // Verifies merge() — called when Flink merges partial results during allowed lateness
        // re-processing or session window merging. Cross-accumulator dedup must still work.
        ClickAccumulator a = fn.createAccumulator();
        fn.add(buildEvent("click-1", "ad-1", "sess-A"), a);
        fn.add(buildEvent("click-2", "ad-1", "sess-B"), a);

        ClickAccumulator b = fn.createAccumulator();
        fn.add(buildEvent("click-2", "ad-1", "sess-B"), b); // click-2 also in accumulator a → should not double count
        fn.add(buildEvent("click-3", "ad-1", "sess-C"), b);

        // merge() unions the seenClickIds sets and re-counts — click-2 deduped across both
        ClickAccumulator merged = fn.merge(a, b);
        AggregatedClick result = fn.getResult(merged);

        assertThat(result.getClickCount()).isEqualTo(3); // click-1, click-2 (once), click-3
    }

    // ── test data builder ──────────────────────────────────────────────────────

    /**
     * Builds a minimal ClickEvent for testing.
     * adId, clickId, sessionId are the fields that drive aggregation logic.
     */
    private ClickEvent buildEvent(String clickId, String adId, String sessionId) {
        ClickEvent event = new ClickEvent();
        event.setClickId(clickId);
        event.setAdId(adId);
        event.setAdvertiserId("adv-1");
        event.setSessionId(sessionId);
        event.setCountry("US");
        event.setDeviceType(DeviceType.DESKTOP);
        event.setHashedIpAddress("abc123hashed");
        event.setUserAgent("Mozilla/5.0");
        event.setTimestampMs(System.currentTimeMillis());
        return event;
    }
}
