# Stage 1: Build UI
FROM node:20-alpine AS ui-build
WORKDIR /ui
COPY ui/package*.json ./
RUN npm ci
COPY ui/ .
RUN npm run build

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY agentic-ops/target/*.jar app.jar
COPY --from=ui-build /ui/dist/ ./static/

EXPOSE 8090

ENTRYPOINT ["java", \
    "-XX:+UseG1GC", \
    "-XX:MaxGCPauseMillis=20", \
    "-XX:+ParallelRefProcEnabled", \
    "-XX:+UseStringDeduplication", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Xms256M", \
    "-Xmx256M", \
    "-XX:+HeapDumpOnOutOfMemoryError", \
    "-XX:HeapDumpPath=/tmp/heapdump.hprof", \
    "-jar", "app.jar"]
