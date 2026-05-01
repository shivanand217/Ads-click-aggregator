package com.clickagg.clickprocessor.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares Kafka topics at startup.
 * Spring will create them if they don't exist (idempotent if they do).
 *
 * Local dev:  partitions=1,  replication-factor=1
 * Production: partitions=64, replication-factor=3  (set via env/config-server)
 */
@Configuration
public class KafkaTopicConfig {

    @Value("${clickprocessor.kafka.topic.raw-clicks}")
    private String rawClicksTopic;

    @Value("${clickprocessor.kafka.topic.partitions:1}")
    private int partitions;

    @Value("${clickprocessor.kafka.topic.replication-factor:1}")
    private short replicationFactor;

    @Bean
    public NewTopic rawClicksTopic() {
        return TopicBuilder.name(rawClicksTopic)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }
}
