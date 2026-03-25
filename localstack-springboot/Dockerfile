FROM gradle:8.14.3-jdk17 AS build
WORKDIR /workspace
COPY build.gradle settings.gradle ./
COPY src ./src
COPY platform ./platform
RUN gradle --no-daemon clean bootJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/localstack-springboot-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

