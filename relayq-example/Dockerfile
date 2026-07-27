FROM maven:3.9.11-eclipse-temurin-21-alpine AS build

WORKDIR /workspace

COPY pom.xml pom.xml
COPY relayq-core/pom.xml relayq-core/pom.xml
COPY relayq-spring-boot-starter/pom.xml relayq-spring-boot-starter/pom.xml
COPY relayq-example/pom.xml relayq-example/pom.xml
COPY relayq-core/src relayq-core/src
COPY relayq-spring-boot-starter/src relayq-spring-boot-starter/src
COPY relayq-example/src relayq-example/src

RUN mvn -B -pl relayq-example -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S relayq && adduser -S relayq -G relayq

WORKDIR /app
COPY --from=build /workspace/relayq-example/target/relayq-example-0.0.1-SNAPSHOT.jar app.jar

USER relayq
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
