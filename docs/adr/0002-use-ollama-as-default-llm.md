# Use Ollama as the default active LLM

## Context and Problem Statement
The platform must run locally with fast iteration and should not require cloud model credentials for the primary development path.

## Decision Drivers
- Local-first development
- Lower cost
- Simpler onboarding
- Keep provider abstraction for future Vertex activation

## Considered Options
- Ollama as default with Vertex config disabled
- Vertex as default
- External hosted-only provider strategy

## Decision Outcome
Chosen option: Ollama as default with Vertex config disabled, because it satisfies local-first development while preserving multi-provider architecture.

### Consequences
- Good, because developers can run the stack offline or near-offline.
- Good, because provider switching can be added through configuration and strategy abstractions.
- Bad, because local model quality and throughput depend on workstation hardware.
