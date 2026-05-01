# Known Risks

- The project was generated incrementally across phases, so compile-time reconciliation is still required.
- Some Spring AI advisor and vector-memory combinations may need tuning for streamed responses.
- The Flyway Kubernetes Job currently uses a placeholder mounted migration pattern and should be aligned with your real migration ownership model.
- Kafka, Redis, and Ollama settings are optimized for local development first, not for hardened production clusters.
