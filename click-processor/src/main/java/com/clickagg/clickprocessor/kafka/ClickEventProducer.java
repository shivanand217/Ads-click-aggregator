package com.clickagg.clickprocessor.kafka;

import com.clickagg.schema.ClickEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes ClickEvent to the raw-clicks Kafka topic.
 *
 * Partition key = adId — guarantees all clicks for the same ad land on the
 * same partition, enabling ordered per-ad processing in Flink.
 *
 * Producer is configured as idempotent (enable.idempotence=true, acks=all)
 * to avoid duplicates at the Kafka layer even under retries.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClickEventProducer {

    private final KafkaTemplate<String, ClickEvent> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${clickprocessor.kafka.topic.raw-clicks}")
    private String rawClicksTopic;

    public void publish(ClickEvent event) {
        CompletableFuture<SendResult<String, ClickEvent>> future =
                kafkaTemplate.send(rawClicksTopic, event.getAdId(), event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish ClickEvent: clickId={} adId={} error={}",
                        event.getClickId(), event.getAdId(), ex.getMessage());
                meterRegistry.counter("kafka.publish.failure").increment();
            } else {
                log.debug("ClickEvent published: clickId={} adId={} partition={} offset={}",
                        event.getClickId(), event.getAdId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                meterRegistry.counter("kafka.publish.success").increment();
            }
        });
    }
}
