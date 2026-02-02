# Build stage
FROM gradle:9.3-jdk25 AS builder
WORKDIR /build
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src
RUN gradle shadowJar --no-daemon

# Runtime stage
FROM azul/zulu-openjdk-alpine:25-jre
WORKDIR /app

# Create non-root user
RUN addgroup -g 1000 minecraft && \
    adduser -u 1000 -G minecraft -D minecraft

COPY --from=builder /build/build/libs/lobby.jar app.jar

USER minecraft

EXPOSE 25565

CMD ["java", "-Xms128M", "-Xmx256M", "-jar", "app.jar"]
