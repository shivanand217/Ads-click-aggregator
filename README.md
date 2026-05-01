# Ad Click Aggregator

Real-time ad click tracking and analytics system. Advertisers can query click metrics with 1-minute granularity within ~70 seconds of a click occurring.

---

## System Overview

```
Browser ──GET /v1/click/{adId}──► Click Processor ──► Kafka (raw-clicks)
                                       │                      │
                                  302 redirect            Flink Job
                                       │                      │
                              Advertiser Website     ClickHouse (OLAP)
                                                               │
                                                    Analytics Service ◄── Advertiser
```

---

## Build Progress

| Phase | Module | Status | Tested |
|-------|--------|--------|--------|
| 1 | Click Ingestion (`click-processor`) | ✅ Complete | ✅ Yes |
| 2 | Stream Processing (`flink-aggregation`) | 🔧 In Progress | — |
| 3 | Analytics API (`analytics-service`) | Planned | — |
| 4 | Ad Service, API Gateway, Config Service | Planned | — |

---

## Phase 1: Click Ingestion ✅

### What it does
1. Browser clicks an ad → `GET /v1/click/{adId}?clickId=...&sessionId=...`
2. Server resolves a `click_id` (client-provided UUID or deterministic 5-second-bucket hash)
3. Decorator chain enriches the request:
   - `LoggingDecorator` — times full chain, structured logs
   - `GeoIpDecorator` — resolves country from raw IP (stub: returns "US" / "LOCAL")
   - `AnonymizationDecorator` — SHA-256 hashes raw IP before Kafka, raw IP never stored
4. Redis idempotency check: `SET click:idem:{click_id} NX EX 86400`
   - If duplicate → skip Kafka publish, still issue 302
   - If new → publish `ClickEvent` (Avro) to `raw-clicks` Kafka topic
5. Resolve redirect URL (stub: `https://example.com?ad_id={adId}`)
6. Return `302 Location: <redirect_url>` + `X-Click-Id` header

### Verified Behaviours
- 302 redirect works for all adIds
- `X-Click-Id` header returned on every response
- Same `adId` always routes to the same Kafka partition (murmur2 hash)
- Duplicate `clickId` → Redis `SET NX` returns false → Kafka publish skipped
- Partition distribution is hash-based (not round-robin) — even spread only at scale

### Idempotency — 3 Layers

| Layer | Mechanism | Scope |
|-------|-----------|-------|
| Redis `SET NX` | Blocks duplicate Kafka publish | 24-hour window, fail-open |
| Flink `HashSet` in accumulator | Secondary dedup within 1-min window | Per window |
| ClickHouse `ReplacingMergeTree` | Overwrites re-emitted aggregates | Permanent |

### Decorator Chain

```
ClickController (builds ClickRequest from HTTP)
  └── LoggingClickProcessorDecorator       ← times + logs full chain
        └── GeoIpClickProcessorDecorator   ← adds country (stub: "US")
              └── AnonymizationDecorator   ← SHA-256 hashes raw IP
                    └── BaseClickProcessorService
                          ├── RedisIdempotencyChecker  (Circuit Breaker, fail-open)
                          ├── ClickEventProducer       → Kafka raw-clicks
                          └── AdRedirectResolver       → 302 URL (stub)
```

Wired explicitly in `ClickProcessorBeanConfig` — no `@Order` magic.

### ClickEvent Avro Schema

```
click_id        string    — UUID, idempotency key across all 3 layers
ad_id           string    — Kafka partition key; same ad → same partition always
advertiser_id   string    — denormalized to avoid Flink joins downstream
placement_id    string?
session_id      string?   — used by Flink HyperLogLog for unique session count
hashed_ip       string    — SHA-256 of raw IP, raw IP never leaves the service
user_agent      string
country         string?   — ISO 3166-1 alpha-2, null if unresolvable
device_type     enum      — DESKTOP | MOBILE | TABLET | UNKNOWN
timestamp_ms    long      — Flink event-time watermark source
```

Schema registered with Confluent Schema Registry on first publish. Topic: `raw-clicks`.

---

## Phase 2: Stream Processing 🔧

### Architecture

```
Kafka (raw-clicks, 8 partitions)
    └── Flink KafkaSource
          └── WatermarkStrategy (BoundedOutOfOrderness 10s)
                └── keyBy(adId + "|" + country + "|" + deviceType)
                      └── TumblingEventTimeWindow(1 minute)
                            └── ClickAggregateFunction
                                  ├── HashSet dedup by clickId
                                  └── HyperLogLog for unique sessions
                            └── WindowMetadataFunction
                                  └── stamps windowStartMs / windowEndMs
                                        ├── ClickHouse JDBC Sink → clicks_1min
                                        └── Kafka Sink → aggregated-clicks topic
```

### Window Design

| Setting | Value | Why |
|---------|-------|-----|
| Window size | 1 minute tumbling | Minimum advertiser granularity requirement |
| Watermark lag | 10 seconds | Absorbs network jitter; window closes ~70s after click |
| Allowed lateness | 30 seconds | Re-emits window for late events; ClickHouse handles overwrite |
| Checkpoint interval | 30 seconds | RocksDB → S3; exactly-once recovery |

**Advertisers see data within ~70 seconds of a click.**

### Aggregation State (ClickAccumulator)

```java
Set<String>  seenClickIds       // dedup within window
long         clickCount         // incremented only for new clickIds
HyperLogLog  uniqueSessionHll   // precision=14, ~16KB, ~1.6% error
String       adId, advertiserId, country, deviceType  // window grouping key
```

Why HyperLogLog for unique sessions?
- Exact count needs to store all session IDs in memory per window
- At 10k clicks/sec × 1-minute window = potentially 600k session entries per TaskManager
- HLL gives approximate cardinality in 16KB constant space — classic stream analytics trade-off

### ClickHouse Schema

```sql
-- Base table: Flink writes here every ~70 seconds
clicks_1min  ENGINE = ReplacingMergeTree(window_start)
             ORDER BY (advertiser_id, ad_id, window_start, country, device_type)

-- Materialized views (auto-updated on insert)
clicks_hourly  SummingMergeTree  → rollup of clicks_1min by hour
clicks_daily   SummingMergeTree  → rollup of clicks_hourly by day
```

`ReplacingMergeTree`: if Flink re-emits a window on recovery, ClickHouse replaces the existing row rather than adding a duplicate. Dedup is async (on merge), so use `FINAL` in queries for exact results.

### Files Created (Phase 2 — partial)

| File | Purpose |
|------|---------|
| `flink-aggregation/pom.xml` | Flink module with shade plugin for fat JAR |
| `infrastructure/clickhouse/init.sql` | ClickHouse schema + materialized views |
| `config/JobConfig.java` | Properties + env var config, Serializable for Flink |
| `model/AggregatedClick.java` | Output POJO per window |
| `aggregation/ClickAccumulator.java` | Window state: HashSet dedup + HLL |
| `aggregation/ClickAggregateFunction.java` | Flink AggregateFunction |
| `aggregation/WindowMetadataFunction.java` | Attaches window timestamps (in progress) |
| `sink/ClickHouseJdbcSink.java` | JDBC sink factory (planned) |
| `ClickAggregationJob.java` | Main job entry point (planned) |

---

## Infrastructure (Local Dev)

```bash
# Start all services (Kafka, Schema Registry, Redis, ClickHouse, Kafka UI)
docker-compose up -d

# Health check
docker-compose ps

# Access points
Kafka broker:       localhost:9092
Schema Registry:    http://localhost:8081
Redis:              localhost:6379
ClickHouse HTTP:    http://localhost:8123
Kafka UI:           http://localhost:8090
Click Processor:    http://localhost:8080
```

### Test click ingestion

```bash
# Single click
curl -v "http://localhost:8080/v1/click/ad-123?sessionId=sess-1"

# Fire multiple adIds across partitions
for id in ad-001 ad-002 ad-003 ad-004 ad-005 ad-006 ad-007 ad-008; do
  curl -s "http://localhost:8080/v1/click/$id?sessionId=sess-1" -o /dev/null
done

# Watch Kafka messages (Avro decoded)
docker exec -it schema-registry kafka-avro-console-consumer \
  --bootstrap-server kafka:29092 \
  --topic raw-clicks \
  --from-beginning \
  --property print.partition=true \
  --property print.key=true \
  --property schema.registry.url=http://schema-registry:8081

# Check ClickHouse is up
curl "http://localhost:8123/ping"   # → Ok.

# Query ClickHouse (empty until Flink job runs)
curl "http://localhost:8123/" \
  --user "clickagg:clickagg" \
  --data "SELECT * FROM clickagg.clicks_1min LIMIT 10 FORMAT JSON"
```

---

## Project Structure

```
ad-click-aggregator/
├── pom.xml                              Parent POM — version management
├── docker-compose.yml                   Local infra: Kafka, Schema Registry, Redis, ClickHouse
├── README.md                            This file
├── infrastructure/
│   └── clickhouse/
│       └── init.sql                     ClickHouse schema + materialized views
│
├── click-processor/                     ✅ Phase 1 — Click ingestion (COMPLETE)
│   └── src/main/java/com/clickagg/clickprocessor/
│       ├── controller/ClickController.java
│       ├── model/{ClickRequest, DeviceType}.java
│       ├── service/{ClickProcessorService, BaseClickProcessorService}.java
│       ├── service/decorator/{Logging,GeoIp,Anonymization}ClickProcessorDecorator.java
│       ├── idempotency/RedisIdempotencyChecker.java
│       ├── kafka/ClickEventProducer.java
│       ├── geolocation/{GeoIpResolver, StubGeoIpResolver}.java
│       ├── resolver/{AdRedirectResolver, StubAdRedirectResolver}.java
│       └── config/{KafkaConfig, KafkaTopicConfig, ClickProcessorBeanConfig}.java
│
├── flink-aggregation/                   🔧 Phase 2 — Stream processing (IN PROGRESS)
│   └── src/main/java/com/clickagg/flink/
│       ├── ClickAggregationJob.java         Main Flink job (planned)
│       ├── config/JobConfig.java
│       ├── model/AggregatedClick.java
│       ├── aggregation/
│       │   ├── ClickAccumulator.java
│       │   ├── ClickAggregateFunction.java
│       │   └── WindowMetadataFunction.java  (in progress)
│       ├── sink/ClickHouseJdbcSink.java     (planned)
│       └── source/                          (planned)
│
└── analytics-service/                   📋 Phase 3 — Analytics API (PLANNED)
```

---

## Design Patterns Used

| Pattern | Where | Why |
|---------|-------|-----|
| **Decorator** | `ClickProcessorService` chain | Add cross-cutting concerns (logging, GeoIP, anonymization) without modifying core logic |
| **Strategy** | `GeoIpResolver`, `AdRedirectResolver` (interfaces) | Swap stub → real implementation without touching callers |
| **Builder** | `ClickRequest`, `AggregatedClick` | Decorators create enriched copies via `toBuilder()`; Flink model is easy to construct |
| **Circuit Breaker** | Redis idempotency check | Fail-open: Redis outage never blocks clicks or redirects |
| **Factory** | `ClickProcessorBeanConfig`, `ClickHouseJdbcSink` | Constructs chains/sinks in one explicit, reviewable place |
| **CQRS** | System-wide | Write path (Kafka→Flink→ClickHouse) fully decoupled from read path (Analytics→ClickHouse) |

---

## Kafka Partition Sizing Rationale

`raw-clicks` uses **8 partitions locally** (64 in production):

```
Peak throughput:            10,000 clicks/sec
Flink throughput/partition: ~500 events/sec (RocksDB state ops bound)
Min partitions:             10,000 / 500 = 20
× 3x burst headroom:        60
Rounded to power of 2:      64 (production) / 8 (local dev)
```

Same `adId` always → same partition (murmur2 hash). Even distribution only emerges at scale — with 8 test adIds and 8 partitions, hash collisions are expected (~99.7% of random key sets collide at least once).

---

## Security

- Raw IP **never stored or logged** — SHA-256 hashed by `AnonymizationDecorator` before Kafka
- Spring Boot 3.4.5 (fixes Tomcat, Spring, Netty, logback, Jackson CVEs from 3.2.5)
- Avro 1.12.0 (fixes CVE-2024-47561 deserialization)
- Confluent 7.8.0, commons-compress 1.27.1, commons-io 2.18.0
- `commons-beanutils` excluded from Confluent dep (CVE-2025-48734, no stable upstream fix)

---

## What's Stubbed (Replace Before Production)

| Stub | File | Production replacement |
|------|------|------------------------|
| GeoIP country | `StubGeoIpResolver` | MaxMind GeoLite2 `DatabaseReader` |
| Redirect URL | `StubAdRedirectResolver` | HTTP call to Ad Service with Redis cache (TTL 60s) |
