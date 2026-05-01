# CI/CD and Release Guidance

## Image tagging
Do not rely on the `latest` tag in real environments.
Use immutable image references such as:
- semantic version tags for releases,
- commit SHA tags for traceability,
- image digests for deployment immutability.

## Recommended pipeline stages
1. Build backend and frontend.
2. Run tests and static validation.
3. Build container images.
4. Tag images with commit SHA and release version.
5. Push images to a registry.
6. Update Kubernetes manifests or Helm values with immutable references.

## Deployment note
For higher-control environments, prefer a separate migration step or Kubernetes Job before rolling new application pods.
