# --- Build ------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B clean package -DskipTests

# --- Runtime ----------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN apk add --no-cache wget \
    && addgroup -S fraudguard && adduser -S fraudguard -G fraudguard
COPY --from=build /build/target/*.jar app.jar
RUN chown fraudguard:fraudguard app.jar
USER fraudguard

EXPOSE 8080
ENV JAVA_OPTS="-Xms128m -Xmx256m -XX:+UseG1GC -Djava.net.preferIPv4Stack=true"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
