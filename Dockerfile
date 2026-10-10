FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --gid 10001 bank && useradd --uid 10001 --gid bank bank
COPY --from=build /build/target/eu-fin-0.1.0.jar app.jar
USER bank
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
