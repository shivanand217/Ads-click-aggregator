package com.clickagg.clickprocessor.service;

import com.clickagg.clickprocessor.idempotency.RedisIdempotencyChecker;
import com.clickagg.clickprocessor.kafka.ClickEventProducer;
import com.clickagg.clickprocessor.model.ClickRequest;
import com.clickagg.clickprocessor.model.DeviceType;
import com.clickagg.clickprocessor.resolver.AdRedirectResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BaseClickProcessorServiceTest {

    @Mock
    private RedisIdempotencyChecker idempotencyChecker;

    @Mock
    private ClickEventProducer clickEventProducer;

    @Mock
    private AdRedirectResolver adRedirectResolver;

    private MeterRegistry meterRegistry;
    private BaseClickProcessorService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new BaseClickProcessorService(idempotencyChecker, clickEventProducer, adRedirectResolver, meterRegistry);
    }

    @Test
    void processClick_newClick_publishesToKafkaAndReturnsRedirectUrl() {
        ClickRequest request = buildRequest("click-1", "ad-123");
        when(idempotencyChecker.isDuplicate("click-1")).thenReturn(false);
        when(adRedirectResolver.resolve("ad-123")).thenReturn("https://example.com");

        String result = service.processClick(request);

        assertThat(result).isEqualTo("https://example.com");
        verify(clickEventProducer).publish(any());
        verify(adRedirectResolver).resolve("ad-123");
    }

    @Test
    void processClick_duplicateClick_skipsKafkaPublishButStillReturnsRedirectUrl() {
        ClickRequest request = buildRequest("click-dup", "ad-123");
        when(idempotencyChecker.isDuplicate("click-dup")).thenReturn(true);
        when(adRedirectResolver.resolve("ad-123")).thenReturn("https://example.com");

        String result = service.processClick(request);

        assertThat(result).isEqualTo("https://example.com");
        verify(clickEventProducer, never()).publish(any());
    }

    @Test
    void processClick_newClick_incrementsPublishedCounter() {
        ClickRequest request = buildRequest("click-2", "ad-456");
        when(idempotencyChecker.isDuplicate("click-2")).thenReturn(false);
        when(adRedirectResolver.resolve("ad-456")).thenReturn("https://example.com");

        service.processClick(request);

        assertThat(meterRegistry.counter("click.processor.published").count()).isEqualTo(1.0);
    }

    @Test
    void processClick_duplicateClick_incrementsDuplicatesCounter() {
        ClickRequest request = buildRequest("click-dup2", "ad-456");
        when(idempotencyChecker.isDuplicate("click-dup2")).thenReturn(true);
        when(adRedirectResolver.resolve("ad-456")).thenReturn("https://example.com");

        service.processClick(request);

        assertThat(meterRegistry.counter("click.processor.duplicates").count()).isEqualTo(1.0);
    }

    private ClickRequest buildRequest(String clickId, String adId) {
        return ClickRequest.builder()
                .clickId(clickId)
                .adId(adId)
                .advertiserId("advertiser-1")
                .hashedIpAddress("abc123hashed")
                .userAgent("Mozilla/5.0")
                .deviceType(DeviceType.DESKTOP)
                .country("US")
                .timestampMs(System.currentTimeMillis())
                .build();
    }
}
