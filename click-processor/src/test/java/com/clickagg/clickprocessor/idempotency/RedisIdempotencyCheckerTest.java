package com.clickagg.clickprocessor.idempotency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisIdempotencyCheckerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private RedisIdempotencyChecker checker;

    @Test
    void isDuplicate_firstClick_returnsFalse() {
        ReflectionTestUtils.setField(checker, "ttlHours", 24);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // setIfAbsent returns true when key was newly set (first time seen)
        when(valueOps.setIfAbsent(eq("click:idem:click-1"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        boolean result = checker.isDuplicate("click-1");

        assertThat(result).isFalse();
    }

    @Test
    void isDuplicate_secondClick_returnsTrue() {
        ReflectionTestUtils.setField(checker, "ttlHours", 24);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // setIfAbsent returns false when key already existed (duplicate)
        when(valueOps.setIfAbsent(eq("click:idem:click-dup"), eq("1"), any(Duration.class)))
                .thenReturn(false);

        boolean result = checker.isDuplicate("click-dup");

        assertThat(result).isTrue();
    }

    @Test
    void isDuplicate_usesCorrectRedisKeyPrefix() {
        ReflectionTestUtils.setField(checker, "ttlHours", 24);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        checker.isDuplicate("abc-123");

        // Verify the Redis key has the correct prefix
        verify(valueOps).setIfAbsent(eq("click:idem:abc-123"), eq("1"), any(Duration.class));
    }

    @Test
    void isDuplicateFallback_whenRedisDown_returnsFalseToFailOpen() {
        // Fallback is called when circuit breaker opens or Redis throws.
        // Must return false (fail-open) so the click still gets published to Kafka.
        boolean result = checker.isDuplicateFallback("any-click-id", new RuntimeException("Redis unavailable"));

        assertThat(result).isFalse();
        // No interaction with Redis — this is the fallback path
        verifyNoInteractions(redisTemplate);
    }
}
