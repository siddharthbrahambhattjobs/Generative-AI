# Deployment Guide - Phase 8

## Docker Compose

Run from `deployment/docker`:

```bash
docker compose up --build
```

The compose file uses health checks and `depends_on` with `condition: service_healthy` so the chat service waits for PostgreSQL, Redis, and Kafka readiness before startup.

## Kubernetes

Apply the base manifests:

```bash
kubectl apply -k deployment/k8s/base
```

### Included
- Namespace: `springai`
- ConfigMap + Secret for application configuration
- PostgreSQL, Redis, Kafka, and Ollama infrastructure services
- Deployments and Services for `springai-chat-service` and `springai-ui`
- Readiness and liveness probes
- HorizontalPodAutoscaler for chat service and UI
- PersistentVolumeClaims for PostgreSQL, Ollama model cache, and uploaded files
- Ingress for `springai.local`
- Default-deny style NetworkPolicies with explicit allow rules

### Notes
- Build and load `springai-chat-service:latest` and `springai-ui:latest` into your cluster runtime before applying the manifests.
- The NetworkPolicies assume your cluster CNI enforces policy.
- Secrets are placeholders and must be replaced for real environments.
