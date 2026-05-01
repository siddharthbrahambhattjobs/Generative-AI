# Use PostgreSQL and pgvector

## Context and Problem Statement
The platform needs transactional persistence for users, chats, and attachments, and it also needs vector search for retrieval-augmented generation.

## Decision Drivers
- Reduce operational sprawl
- Support Spring AI vector-store integration
- Keep user and document ownership in one durable platform store

## Considered Options
- PostgreSQL + pgvector
- PostgreSQL + separate vector database
- MariaDB + vector capability

## Decision Outcome
Chosen option: PostgreSQL + pgvector, because it keeps transactional and vector data close while aligning with the Spring AI pgvector integration path.

### Consequences
- Good, because one operational datastore covers most platform persistence.
- Good, because metadata filtering fits user-scoped retrieval well.
- Bad, because vector and transactional load share one database footprint.
