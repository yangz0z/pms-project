FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/pms-0.0.1-SNAPSHOT.jar app.jar
RUN useradd --system app
USER app
EXPOSE 8989
ENTRYPOINT ["java", "-jar", "app.jar"]
