package com.clickagg.flink;

import com.clickagg.flink.aggregation.ClickAggregateFunction;
import com.clickagg.flink.aggregation.WindowMetadataFunction;
import com.clickagg.flink.config.JobConfig;
import com.clickagg.flink.model.AggregatedClick;
import com.clickagg.flink.sink.ClickHouseJdbcSink;
import com.clickagg.schema.ClickEvent;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.formats.avro.registry.confluent.ConfluentRegistryAvroDeserializationSchema;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.util.OutputTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Main Flink streaming job for ad click aggregation.
 *
 * Pipeline:
 *  Kafka (raw-clicks)
 *    → Avro deserialize (ClickEvent)
 *    → Watermark (BoundedOutOfOrderness 10s)
 *    → keyBy(adId + country + deviceType)
 *    → TumblingEventTimeWindow(1 minute)
 *    → ClickAggregateFunction (HashSet dedup + HLL unique sessions)
 *    → WindowMetadataFunction (attaches windowStartMs / windowEndMs)
 *    → [ClickHouse JDBC Sink]  clicks_1min table
 *    → [Kafka Sink]            aggregated-clicks topic
 *    → [Side output]           late-clicks topic (events > 30s late)
 *
 * To run locally:
 *   mvn package -pl flink-aggregation -am
 *   java -cp flink-aggregation/target/flink-aggregation-*.jar com.clickagg.flink.ClickAggregationJob
 *
 * To run on Flink cluster (Kubernetes):
 *   flink run -c com.clickagg.flink.ClickAggregationJob flink-aggregation-1.0.0-SNAPSHOT.jar
 */
public class ClickAggregationJob {

    private static final Logger log = LoggerFactory.getLogger(ClickAggregationJob.class);

    // Side output tag for events arriving after allowed lateness window
    static final OutputTag<ClickEvent> LATE_DATA_TAG =
            new OutputTag<>("late-clicks") {};

    public static void main(String[] args) throws Exception {
        JobConfig config = JobConfig.load();
        log.info("Starting ClickAggregationJob with config: kafka={} clickhouse={}",
                config.getKafkaBootstrapServers(), config.getClickHouseUrl());

        StreamExecutionEnvironment env = buildEnvironment(config);
        buildPipeline(env, config);
        env.execute("Click Aggregation Job");
    }

    static StreamExecutionEnvironment buildEnvironment(JobConfig config) {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Checkpointing for exactly-once Kafka offset commits + fault tolerance
        env.enableCheckpointing(config.getCheckpointIntervalMs(), CheckpointingMode.EXACTLY_ONCE);
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(5000);
        env.getCheckpointConfig().setCheckpointTimeout(60_000);
        env.getCheckpointConfig().setMaxConcurrentCheckpoints(1);

        return env;
    }

    static void buildPipeline(StreamExecutionEnvironment env, JobConfig config) {
        // ── 1. Kafka Source ────────────────────────────────────────────────────
        Map<String, String> schemaRegistryConfig = new HashMap<>();
        schemaRegistryConfig.put(
                KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, "true");

        KafkaSource<ClickEvent> kafkaSource = KafkaSource.<ClickEvent>builder()
                .setBootstrapServers(config.getKafkaBootstrapServers())
                .setTopics(config.getRawClicksTopic())
                .setGroupId(config.getKafkaConsumerGroup())
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setValueOnlyDeserializer(
                        ConfluentRegistryAvroDeserializationSchema.forSpecific(
                                ClickEvent.class,
                                config.getSchemaRegistryUrl(),
                                schemaRegistryConfig
                        )
                )
                .build();

        // ── 2. Watermark Strategy ──────────────────────────────────────────────
        // 10-second out-of-order tolerance handles network jitter between Click
        // Processor and Kafka without losing late events.
        // Window of 11:00:00–11:00:59 emits at watermark time 11:01:10 (wall clock ~+70s after click).
        WatermarkStrategy<ClickEvent> watermarkStrategy = WatermarkStrategy
                .<ClickEvent>forBoundedOutOfOrderness(Duration.ofSeconds(10))
                .withTimestampAssigner((event, recordTimestamp) -> event.getTimestampMs())
                .withIdleness(Duration.ofMinutes(5)); // handle idle partitions gracefully

        DataStream<ClickEvent> clickStream = env
                .fromSource(kafkaSource, watermarkStrategy, "Kafka raw-clicks source");

        // ── 3. Key → Window → Aggregate ────────────────────────────────────────
        // keyBy compound key ensures all events for the same (ad, country, device) combination
        // are processed by the same Flink subtask — no cross-task coordination needed.
        SingleOutputStreamOperator<AggregatedClick> aggregated = clickStream
                .keyBy(ClickAggregationJob::buildKey)
                .window(TumblingEventTimeWindows.of(Time.minutes(config.getWindowSizeMinutes())))
                .allowedLateness(Time.seconds(config.getAllowedLatenessSeconds()))
                .sideOutputTag(LATE_DATA_TAG)
                .aggregate(new ClickAggregateFunction(), new WindowMetadataFunction());

        // ── 4. Sinks ───────────────────────────────────────────────────────────

        // ClickHouse: primary analytics store
        aggregated.addSink(
                ClickHouseJdbcSink.create(
                        config.getClickHouseUrl(),
                        config.getClickHouseUser(),
                        config.getClickHousePassword()
                )
        ).name("ClickHouse clicks_1min sink");

        // Late events → separate Kafka topic for monitoring / manual reconciliation
        aggregated.getSideOutput(LATE_DATA_TAG)
                .map(event -> "LATE:" + event.getClickId() + " adId=" + event.getAdId())
                .sinkTo(buildLateEventsSink(config))
                .name("Late events sink");

        log.info("Pipeline built: window={}min lateness={}s checkpointInterval={}ms",
                config.getWindowSizeMinutes(),
                config.getAllowedLatenessSeconds(),
                config.getCheckpointIntervalMs());
    }

    /**
     * Compound key for keyBy.
     * Guarantees all clicks for the same (ad + country + device) combination
     * route to the same Flink subtask — each subtask aggregates independently.
     */
    private static String buildKey(ClickEvent event) {
        String country    = event.getCountry()    != null ? event.getCountry()    : "UNKNOWN";
        String deviceType = event.getDeviceType() != null ? event.getDeviceType().toString() : "UNKNOWN";
        return event.getAdId() + "|" + country + "|" + deviceType;
    }

    private static org.apache.flink.api.connector.sink2.Sink<String> buildLateEventsSink(JobConfig config) {
        return org.apache.flink.connector.kafka.sink.KafkaSink.<String>builder()
                .setBootstrapServers(config.getKafkaBootstrapServers())
                .setRecordSerializer(
                        org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema
                                .builder()
                                .setTopic("late-clicks")
                                .setValueSerializationSchema(new SimpleStringSchema())
                                .build()
                )
                .build();
    }
}
