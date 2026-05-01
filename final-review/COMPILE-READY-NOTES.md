# Compile Ready Notes

## Applied fixes
- Added Angular environment files for default and development builds.
- Replaced Angular CLI config with a minimal standalone-compatible `angular.json` using environment file replacement.
- Added `src/index.html` and minimal global styles.
- Added Spring AI vector-store advisors dependency.
- Simplified advisor integration to `QuestionAnswerAdvisor` and `VectorStoreChatMemoryAdvisor` default advisors for stronger compatibility.
- Corrected reactive JWT configuration to use `ReactiveJwtDecoder` in WebFlux security setup.

## Still validate locally
- Run Maven package using JDK 25 and confirm exact Spring AI 1.1.0 advisor APIs resolve in your environment.
- Run Angular build and verify generated `dist/springai-ui/browser` path.
- Reconcile any remaining entity/repository mismatches during first local compile.
- End-to-end test Docker Compose because compile-ready does not guarantee runtime-perfect integration.
