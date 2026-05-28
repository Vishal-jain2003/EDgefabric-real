# STAGE 1: Build the Java JAR file
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Install parent POM first (consistent-hashing depends on it)
COPY pom.xml /app/parent-pom.xml
RUN mvn -B -N -f /app/parent-pom.xml install

# Install consistent-hashing (internal dep — not on Maven Central)
COPY consistent-hashing/ /app/consistent-hashing/
RUN mvn -B -f /app/consistent-hashing/pom.xml install -DskipTests

# Build caching module
COPY caching/pom.xml /app/pom.xml
COPY caching/src ./src

RUN mvn clean package -DskipTests


# STAGE 2: Runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# curl is needed by start.sh to fetch EC2 instance metadata (NODE_IP)
RUN apk add --no-cache curl

COPY --from=build /app/target/*.jar app.jar
COPY caching/start.sh start.sh
RUN chmod +x start.sh

EXPOSE 8082
EXPOSE 7946/udp
EXPOSE 7000/udp

ENTRYPOINT ["./start.sh"]
