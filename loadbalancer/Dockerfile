# Simple Dockerfile - uses pre-built JAR
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy the pre-built JAR file
COPY target/*.jar app.jar

# Expose port 8080 (Spring Boot's default)
EXPOSE 8080

# JVM tuning for low-latency performance
# - G1GC with 20ms max pause time target (P95 <= 10ms requires minimal GC pauses)
# - Explicit heap sizing for predictable memory usage
# - Container-aware JVM settings (requires Docker memory limit)
# - Heap dump on OOM for debugging
ENTRYPOINT ["java", \
    "-XX:+UseG1GC", \
    "-XX:MaxGCPauseMillis=20", \
    "-XX:+ParallelRefProcEnabled", \
    "-XX:+UseStringDeduplication", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Xms512M", \
    "-Xmx512M", \
    "-XX:+HeapDumpOnOutOfMemoryError", \
    "-XX:HeapDumpPath=/tmp/heapdump.hprof", \
    "-jar", "app.jar"]
