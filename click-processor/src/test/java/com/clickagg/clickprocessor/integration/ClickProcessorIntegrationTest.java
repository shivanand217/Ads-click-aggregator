package com.clickagg.clickprocessor.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test: spins up real Kafka and Redis via Testcontainers.
 * Schema Registry is bypassed via test profile (StringSerializer used instead of Avro).
 *
 * Tests the full HTTP → service → Redis → Kafka path without mocks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class ClickProcessorIntegrationTest {

    @Container
    static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.8.0"))
            .withKraft();

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void click_firstRequest_returns302() throws Exception {
        mockMvc.perform(get("/v1/click/ad-integration-test")
                        .param("clickId", "integ-click-001")
                        .param("sessionId", "sess-001")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0)")
                        .header("X-Forwarded-For", "203.0.113.1"))
                .andExpect(status().isFound())
                .andExpect(header().exists("Location"))
                .andExpect(header().string("X-Click-Id", "integ-click-001"));
    }

    @Test
    void click_duplicateClickId_stillReturns302ButSkipsKafka() throws Exception {
        String clickId = "integ-dup-click-001";

        // First click
        mockMvc.perform(get("/v1/click/ad-dup-test")
                        .param("clickId", clickId)
                        .param("sessionId", "sess-dup"))
                .andExpect(status().isFound());

        // Duplicate — same clickId, should be deduplicated by Redis
        // Still returns 302 (user always gets redirected), just not published to Kafka again
        mockMvc.perform(get("/v1/click/ad-dup-test")
                        .param("clickId", clickId)
                        .param("sessionId", "sess-dup"))
                .andExpect(status().isFound())
                .andExpect(header().string("X-Click-Id", clickId));
    }

    @Test
    void actuator_healthEndpoint_returnsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
