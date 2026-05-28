FROM eclipse-temurin:21-jre-alpine
 
WORKDIR /app
 
# install curl for docker healthcheck
RUN apk add --no-cache curl
 
# Copy the pre-built JAR file
COPY target/*.jar app.jar
 
# Expose Spring Boot port
EXPOSE 9000
 
# Start application
ENTRYPOINT ["java","-jar","app.jar"]