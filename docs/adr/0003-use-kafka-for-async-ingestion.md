# Use Kafka for asynchronous ingestion and memory refresh

## Context and Problem Statement
Uploads, extraction, chunking, and summary refresh should not block synchronous user interactions.

## Decision Drivers
- Better user-perceived latency
- Retry and dead-letter support
- Future scale-out worker model

## Considered Options
- Kafka-based asynchronous workflow
- In-process async executor only
- Synchronous inline processing

## Decision Outcome
Chosen option: Kafka-based asynchronous workflow, because it supports retries, decoupling, and later worker specialization.

### Consequences
- Good, because upload APIs can respond quickly while work continues.
- Good, because failed events can be retried or routed to DLT.
- Bad, because infrastructure and operational complexity increase.
