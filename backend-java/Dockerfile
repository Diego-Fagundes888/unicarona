FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build/backend-java
COPY backend-java/pom.xml ./pom.xml
RUN mvn -B -ntp dependency:go-offline
COPY backend-java/src/main ./src/main
COPY dist /build/dist
RUN mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10101 unicarona \
    && useradd --uid 10101 --gid unicarona --no-create-home --shell /usr/sbin/nologin unicarona \
    && mkdir -p /app/storage && chown unicarona:unicarona /app/storage
WORKDIR /app
COPY --from=build --chown=unicarona:unicarona /build/backend-java/target/unicarona-backend-1.0.0-SNAPSHOT.jar /app/app.jar
USER unicarona
ENV HOST=0.0.0.0 PORT=3000 UNICARONA_MODE=api STORAGE_PATH=/app/storage DEV_MAIL_PATH=/app/storage/mail
EXPOSE 3000
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
    CMD curl --fail --silent http://127.0.0.1:${PORT}/health/ready > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
