# Build stage (glibc-based image required for protoc binary)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Build shared libraries from source
RUN --mount=type=secret,id=GH_PAT \
    export TOKEN=$(cat /run/secrets/GH_PAT) && \
    apt-get update && apt-get install -y git && \
    git clone https://x-access-token:${TOKEN}@github.com/irpayng/tms-core-utils.git /tmp/core-utils && \
    mvn -f /tmp/core-utils/pom.xml install -q -B

COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
ARG CACHE_BUST=1
RUN mvn package -B -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
# Business timezone. Single knob, same value everywhere; override per-deployment
# with the TZ env var. Application code reads it via AppZone / the JVM default.
ENV TZ=Africa/Lagos
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
