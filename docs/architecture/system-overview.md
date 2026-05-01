# System Overview

## Services
- `springai-ui`: Angular 21 frontend for login, conversations, uploads, and streamed responses.
- `springai-chat-service`: Java 25 Spring Boot service for auth, chat orchestration, RAG, memory, and ingestion workflows.

## Infrastructure
- PostgreSQL + pgvector for transactional state and vector search.
- Redis for idempotency fast-path and future cache extensions.
- Kafka for asynchronous ingestion and summary refresh events.
- Ollama for local active LLM and embedding model support.
- Vertex AI Gemini kept as disabled configuration for future provider switching.

## Runtime flow
1. User logs in and opens a conversation.
2. UI streams chat tokens from backend.
3. Backend applies retrieval advisors and memory context.
4. Uploads are persisted, queued, extracted, chunked, and indexed.
5. Future prompts retrieve only active, user-scoped context.
