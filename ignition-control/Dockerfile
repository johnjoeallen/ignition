# Multi-stage: build the jar, then a JRE image that also carries the docker CLI
# + compose plugin + an ssh client — compose operations against local and
# ssh:// nodes are shelled out (see ../DESIGN.md "Docker engine access").

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B -e -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -B -e -DskipTests package && cp target/ignition-control.jar /ignition-control.jar

FROM eclipse-temurin:21-jre
ARG DOCKER_CLI_VERSION=27.3.1
ARG COMPOSE_VERSION=2.29.7
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl ca-certificates openssh-client; \
    rm -rf /var/lib/apt/lists/*; \
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in amd64) d=x86_64; c=x86_64;; arm64) d=aarch64; c=aarch64;; *) echo "arch $arch"; exit 1;; esac; \
    curl -fsSL "https://download.docker.com/linux/static/stable/${d}/docker-${DOCKER_CLI_VERSION}.tgz" \
      | tar -xz --strip-components=1 -C /usr/local/bin docker/docker; \
    mkdir -p /usr/local/lib/docker/cli-plugins; \
    curl -fsSL "https://github.com/docker/compose/releases/download/v${COMPOSE_VERSION}/docker-compose-linux-${c}" \
      -o /usr/local/lib/docker/cli-plugins/docker-compose; \
    chmod +x /usr/local/lib/docker/cli-plugins/docker-compose; \
    docker --version; docker compose version

WORKDIR /app
COPY --from=build /ignition-control.jar /app/ignition-control.jar

ENV IGN_WORK_DIR=/work IGN_CONTROL_ADDR=0.0.0.0 IGN_CONTROL_PORT=8790
EXPOSE 8790
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s \
  CMD curl -fsS http://localhost:8790/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/ignition-control.jar"]
