# syntax=docker/dockerfile:1.7

# Build with Java 21 and the Maven wrapper so the container uses the same Maven version as the project.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy build metadata first so Docker can cache dependency downloads between source-only changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -DskipTests dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -DskipTests package

FROM build AS extract
WORKDIR /app
RUN java -Djarmode=layertools -jar target/*.jar extract

# Run on a smaller JRE image; the compiler, Maven, and source files stay behind in the build stages.
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --create-home spring
USER spring

# Copy Spring Boot layers separately so dependency layers stay cached when only application code changes.
COPY --from=extract /app/dependencies/ ./
COPY --from=extract /app/spring-boot-loader/ ./
COPY --from=extract /app/snapshot-dependencies/ ./
COPY --from=extract /app/application/ ./

EXPOSE 8081

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
