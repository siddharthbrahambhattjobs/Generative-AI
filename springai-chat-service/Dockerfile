# Stage 1: BUILDER
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

# ✅ FIXED: Copy source FIRST so changes invalidate cache
COPY springai-chat-service/src ./springai-chat-service/src
COPY pom.xml ./pom.xml
COPY springai-chat-service/pom.xml ./springai-chat-service/pom.xml

# Now mvn ALWAYS rebuilds when src changes
RUN mvn -pl springai-chat-service -am -DskipTests clean package && \
  java -Djarmode=layertools -jar /workspace/springai-chat-service/target/*.jar extract --destination /workspace/extracted

# Stage 2: RUNTIME (unchanged)
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN apk add --no-cache tini curl shadow tesseract-ocr tesseract-ocr-data-eng && \
  groupadd -r spring && useradd -r -g spring spring && \
  mkdir -p /app/data/uploads && \
  chown -R spring:spring /app

COPY --from=build --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/application/ ./

USER spring

# JVM Tuning for Container environments
ENV JAVA_TOOL_OPTIONS="\
  -XX:+UseContainerSupport \
  -XX:InitialRAMPercentage=50.0 \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -XX:+ExitOnOutOfMemoryError \
  -XX:MaxMetaspaceSize=256m \
  -Djava.net.preferIPv4Stack=true \
  -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["tini", "-g", "--"]
CMD ["sh", "-c", "exec java $JAVA_TOOL_OPTIONS org.springframework.boot.loader.launch.JarLauncher"]