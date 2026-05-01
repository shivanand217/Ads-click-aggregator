package com.clickagg.clickprocessor.idempotency;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis-backed idempotency check using SET NX EX (atomic set-if-not-exists).
 *
 * Returns true  → click_id already seen → duplicate, skip Kafka publish.
 * Returns false → click_id is new       → proceed with Kafka publish.
 *
 * Circuit Breaker (resilience-4j) wraps the Redis call.
 * Fallback: isDuplicateFallback → returns false (fail-open).
 *
 * Why fail-open? A Redis outage must NOT stop clicks from being tracked or
 * redirected. The Flink-level MapState dedup and ClickHouse ReplacingMergeTree
 * act as secondary nets, accepting that a short Redis outage may produce
 * a small number of counted duplicates within the affected window.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisIdempotencyChecker {

    private static final String KEY_PREFIX = "click:idem:";

    private final StringRedisTemplate redisTemplate;

    @Value("${clickprocessor.idempotency.ttl-hours:24}")
    private int ttlHours;

    @CircuitBreaker(name = "redis-idempotency", fallbackMethod = "isDuplicateFallback")
    public boolean isDuplicate(String clickId) {
        String key = KEY_PREFIX + clickId;
        // SET key "1" NX EX <ttl> — returns true if the key was SET (new click)
        Boolean wasSet = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", Duration.ofHours(ttlHours));
        // wasSet = true  → key was newly set → NOT a duplicate
        // wasSet = false → key already existed → IS a duplicate
        return Boolean.FALSE.equals(wasSet);
    }

    /**
     * Fallback when Redis is unavailable or Circuit Breaker is open.
     * Fail-open: treat as NOT duplicate so the click is still published to Kafka.
     */
    @SuppressWarnings("unused")
    public boolean isDuplicateFallback(String clickId, Exception ex) {
        log.warn("Redis idempotency check unavailable (circuit open or error), failing open: clickId={} reason={}",
                clickId, ex.getMessage());
        return false;
    }
}
