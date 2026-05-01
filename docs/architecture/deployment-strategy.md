# Deployment Strategy

## Local development
Use Docker Compose from `deployment/docker` for a full local stack.

## Kubernetes
- Base manifests live under `deployment/k8s/base`.
- Environment overlays live under `deployment/k8s/overlays/dev` and `deployment/k8s/overlays/prod`.
- Replace placeholder secrets before real deployment.
- Prefer immutable image tags or digests in real clusters.

## Database migration
Two patterns are documented:
- Spring Boot application startup migration for simple environments.
- Flyway Kubernetes Job pattern for controlled rollout environments.
