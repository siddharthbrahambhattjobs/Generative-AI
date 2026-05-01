# Final Review Notes

## Cleanup focus
- Replace placeholder secrets and image names.
- Remove duplicate dependency declarations before final production packaging.
- Validate advisor ordering under real streamed chat load.
- Decide whether Flyway runs at app startup, via Kubernetes Job, or both by environment.
- Add tests for upload idempotency, resume supersession, and metadata-filtered retrieval.

## Recommended post-generation review
- Verify Java 25 compatibility across local toolchain and CI image.
- Confirm Angular build output path matches the Nginx image copy path.
- Validate Ollama model availability for both chat and embeddings.
- Smoke-test Docker Compose and local Kubernetes end to end.
