# ignition-control: a JRE image that also carries the docker CLI + compose
# plugin + an ssh client, because compose operations against local and ssh://
# nodes are shelled out (see DESIGN.md "Docker engine access").
FROM eclipse-temurin:21-jre

ARG DOCKER_CLI_VERSION=27.3.1
ARG COMPOSE_VERSION=2.29.7
ARG TARGETARCH=amd64

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl ca-certificates openssh-client; \
    rm -rf /var/lib/apt/lists/*; \
    case "$TARGETARCH" in amd64) DARCH=x86_64; CARCH=x86_64;; arm64) DARCH=aarch64; CARCH=aarch64;; *) exit 1;; esac; \
    curl -fsSL "https://download.docker.com/linux/static/stable/${DARCH}/docker-${DOCKER_CLI_VERSION}.tgz" \
      | tar -xz --strip-components=1 -C /usr/local/bin docker/docker; \
    mkdir -p /usr/local/lib/docker/cli-plugins; \
    curl -fsSL "https://github.com/docker/compose/releases/download/v${COMPOSE_VERSION}/docker-compose-linux-${CARCH}" \
      -o /usr/local/lib/docker/cli-plugins/docker-compose; \
    chmod +x /usr/local/lib/docker/cli-plugins/docker-compose; \
    docker --version; docker compose version

WORKDIR /app
COPY target/ignition-control.jar /app/ignition-control.jar

EXPOSE 8790
ENTRYPOINT ["java", "-jar", "/app/ignition-control.jar"]
