# Base images: Eclipse Temurin on Ubuntu 24.04 ("noble"), the default Adoptium recommends and the one Spring's
# and Docker's Java guides use. Pinned to the Ubuntu release so a rebuild can't silently change the OS.
# (Alpine would be smaller but swaps glibc for musl; for a service like this the conventional glibc base wins.)

# ---- build stage: compile with the Maven wrapper (tests run in CI, not here, so they are not even compiled) ----
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q -Dmaven.test.skip=true package && cp target/ledger-system-*.jar /app.jar

# ---- runtime stage: JRE only, non-root ----
FROM eclipse-temurin:21-jre-noble
# Regular (non-system) user with a high uid, so it can never collide with a host account under Kubernetes runAsNonRoot.
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin ledger
USER ledger
COPY --from=build /app.jar /app/app.jar
EXPOSE 8080
# Credentials are supplied at run time (-e DB_USER / DB_PASSWORD), never baked into the image.
ENV DB_URL=jdbc:postgresql://postgres:5432/ledger
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
