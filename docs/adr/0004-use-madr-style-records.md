# Use Markdown ADRs

## Context and Problem Statement
The project needs a lightweight way to preserve architectural rationale as the platform grows across many phases.

## Decision Drivers
- Human-readable documentation
- Low tooling overhead
- Easy review in Git

## Considered Options
- Markdown ADRs in repository
- Wiki-only architecture notes
- No explicit decision log

## Decision Outcome
Chosen option: Markdown ADRs in repository, because they are lightweight, versioned, and easy for contributors to maintain.

### Consequences
- Good, because architectural choices remain visible near the code.
- Good, because future contributors can understand tradeoffs faster.
- Bad, because ADRs must be actively maintained to stay useful.
