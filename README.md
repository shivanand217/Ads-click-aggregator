# Ad Click Aggregator

Real-time ad click tracking and analytics system. Advertisers can query click metrics with 1-minute granularity within ~70 seconds of a click occurring.

---

## System Overview

```
Browser ──GET /v1/click/{adId}──► Click Processor ──► Kafka (raw-clicks)
                                       │                      │
                                  302 redirect            Flink Job
                                       │                      │
                              Advertiser Website        ClickHouse (OLAP)
                                                               │
                                                    Analytics Service ◄── Advertiser
```

---

## Build Order

| Phase | Module | Status |
|-------|--------|--------|
| 1 | Click Ingestion (`click-processor`) | ✅ In Progress |
| 2 | Stream Processing (`flink-aggregation`) | Planned |
| 3 | Analytics API (`analytics-service`) | Planned |
| 4 | Ad Service, API Gateway, Config Service | Planned |

---

## Phase 1: Click Ingestion — Current State

### What it does
1. Browser clicks an ad → `GET /v1/click/{adId}?clickId=...&sessionId=...`
2. Server resolves a `click_id` (client-provided UUID or deterministic hash)
3. Decorator chain enriches the request:
   - `LoggingDecorator` — timing and structured logs
   - `GeoIpDecorator` — resolves country from raw IP (stub: always "US")
   - `AnonymizationDecorator` — SHA-256 hashes raw IP before Kafka
4. Redis idempotency check: `SET click:idem:{click_id} NX EX 86400`
   - If duplicate → skip Kafka, still issue 302
   - If new → publish `ClickEvent` (Avro) to `raw-clicks` Kafka topic
5. Resolve redirect URL (stub: returns `https://example.com?ad_id={adId}`)
6. Return `302 Location: <redirect_url>`

### Idempotency — 3 Layers

| Layer | Mechanism | Scope |
|-------|-----------|-------|
| Redis `SET NX` | Prevents duplicate Kafka publish | 24-hour window |
| Flink `MapState` | Deduplicates within aggregation window | Per 1-minute window |
| ClickHouse `ReplacingMergeTree` | Overwrites re-emitted aggregates on Flink recovery | Permanent |

Redis is **fail-open**: if Redis is down (circuit open), the click still publishes to Kafka. The lower two layers are the safety net.

### Decorator Chain

```
ClickController (builds ClickRequest from HTTP)
  └── LoggingClickProcessorDecorator       ← times + logs
        └── GeoIpClickProcessorDecorator   ← adds country
              └── AnonymizationDecorator   ← hashes IP
                    └── BaseClickProcessorService
                          ├── RedisIdempotencyChecker (Circuit Breaker)
                          ├── ClickEventProducer → Kafka
                          └── AdRedirectResolver → 302 URL
```

Wired explicitly in `ClickProcessorBeanConfig` — no `@Order` magic, chain is readable.

### ClickEvent Avro Schema

```
click_id        string    — UUID, idempotency key across all 3 layers
ad_id           string    — Kafka partition key; all clicks for same ad → same partition
advertiser_id   string    — denormalized to avoid Flink joins
placement_id    string?   — ad slot identifier
session_id      string?   — used by Flink HyperLogLog for unique session count
hashed_ip       string    — SHA-256 of raw IP; raw IP never leaves the service
user_agent      string
country         string?   — ISO 3166-1 alpha-2, null if unresolvable
device_type     enum      — DESKTOP | MOBILE | TABLET | UNKNOWN
timestamp_ms    long      — Flink event-time watermark source
```

Schema registered with Confluent Schema Registry. Topic: `raw-clicks`.

---

## Infrastructure (Local Dev)

```bash
# Start Kafka (KRaft), Schema Registry, Redis, Kafka UI
docker-compose up -d

# Access points
Kafka broker:       localhost:9092
Schema Registry:    http://localhost:8081
Redis:              localhost:6379
Kafka UI:           http://localhost:8090
Click Processor:    http://localhost:8080
```

### Run the service

```bash
cd click-processor
mvn spring-boot:run
```

### Test a click

```bash
curl -v "http://localhost:8080/v1/click/ad-123?sessionId=sess-abc"
# Expect: HTTP/1.1 302 Found
#         Location: https://example.com?ad_id=ad-123&source=clickagg
#         X-Click-Id: <generated-uuid>
```

### Metrics

```bash
curl http://localhost:8080/actuator/metrics/click.processor.published
curl http://localhost:8080/actuator/metrics/click.processor.duplicates
curl http://localhost:8080/actuator/metrics/kafka.publish.success
curl http://localhost:8080/actuator/prometheus
```

---

## Project Structure

```
ad-click-aggregator/
├── pom.xml                          Parent POM — version management
├── docker-compose.yml               Local infra: Kafka, Schema Registry, Redis
├── README.md                        This file
│
├── click-processor/                 Phase 1 — Click ingestion service
│   └── src/main/java/com/clickagg/clickprocessor/
│       ├── controller/
│       │   └── ClickController.java          GET /v1/click/{adId}
│       ├── model/
│       │   ├── ClickRequest.java             Immutable model flowing through chain
│       │   └── DeviceType.java               App-layer enum (mapped to Avro enum)
│       ├── service/
│       │   ├── ClickProcessorService.java    Interface
│       │   ├── BaseClickProcessorService.java Core logic (bottom of chain)
│       │   └── decorator/
│       │       ├── ClickProcessorDecorator.java      Abstract base
│       │       ├── LoggingClickProcessorDecorator.java
│       │       ├── GeoIpClickProcessorDecorator.java
│       │       └── AnonymizationClickProcessorDecorator.java
│       ├── idempotency/
│       │   └── RedisIdempotencyChecker.java  SET NX EX + Circuit Breaker
│       ├── kafka/
│       │   └── ClickEventProducer.java       Avro → raw-clicks topic
│       ├── geolocation/
│       │   ├── GeoIpResolver.java            Interface (replace stub with MaxMind)
│       │   └── StubGeoIpResolver.java
│       ├── resolver/
│       │   ├── AdRedirectResolver.java       Interface (replace stub with Ad Service call)
│       │   └── StubAdRedirectResolver.java
│       ├── config/
│       │   ├── KafkaConfig.java              Typed KafkaTemplate<String, ClickEvent>
│       │   ├── KafkaTopicConfig.java         Auto-creates raw-clicks topic at startup
│       │   └── ClickProcessorBeanConfig.java Wires the Decorator chain
│       └── resources/
│           ├── application.yml
│           └── avro/click_event.avsc         Source of truth for ClickEvent schema
│
├── flink-aggregation/               Phase 2 — Stream processing (TODO)
└── analytics-service/               Phase 3 — Analytics API (TODO)
```

---

## Design Patterns Used

| Pattern | Where | Why |
|---------|-------|-----|
| **Decorator** | `ClickProcessorService` chain | Add cross-cutting concerns (logging, GeoIP, anonymization) without modifying core logic |
| **Strategy** | `GeoIpResolver`, `AdRedirectResolver` (interfaces) | Swap stub → real implementation without touching callers |
| **Builder** | `ClickRequest` | Decorators create enriched copies via `toBuilder()` — immutable, thread-safe |
| **Circuit Breaker** | Redis idempotency check | Fail-open: Redis outage never blocks clicks or redirects |
| **Factory** | `ClickProcessorBeanConfig` | Constructs the full Decorator chain in one explicit, readable place |

---

## Kafka Partition Sizing Rationale

`raw-clicks` uses **64 partitions** in production (1 locally):

```
Peak throughput:           10,000 clicks/sec
Flink throughput/partition: ~500 events/sec (RocksDB state ops bound)
Min partitions needed:     10,000 / 500 = 20
× 3x burst headroom:       60
Rounded to power of 2:     64
```

More partitions = more Flink parallelism. Kafka partition count cannot be decreased without recreating the topic, so 64 builds in growth room.

---

## Security

- Raw IP is **never stored or logged** — SHA-256 hashed by `AnonymizationDecorator` before any downstream processing
- Confluent `commons-beanutils` dependency excluded (CVE-2025-48734, no stable upstream fix yet)
- Spring Boot 3.4.5 used (fixes Tomcat, Spring Framework, Netty, logback, jackson CVEs from 3.2.x)
- Avro 1.12.0 (fixes CVE-2024-47561 deserialization)

---

## What's Stubbed (Replace Before Production)

| Stub | File | Production replacement |
|------|------|------------------------|
| GeoIP country | `StubGeoIpResolver` | MaxMind GeoLite2 `DatabaseReader` |
| Redirect URL | `StubAdRedirectResolver` | HTTP call to Ad Service with Redis cache (TTL 60s) |
