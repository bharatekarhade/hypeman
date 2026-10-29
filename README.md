# Hypeman

A Spring Boot API (`src/`) and its iOS app (`ios/`).

## Server

You need JDK 25 and Docker. Processing uploaded clips and yells also needs `ffmpeg` and `ffprobe` on your PATH.

```sh
cp .env.example .env    # then fill in the values
./mvnw spring-boot:run
```

This starts the Postgres service from `compose.yaml` on its own, on host port 5433.

To run the production image against that database instead:

```sh
docker compose --profile app up --build
```

The API listens on port 8080 either way.

### Tests

```sh
./mvnw test
```

The tests use Testcontainers, so Docker has to be running.

### Migrations

Flyway migrations live in `src/main/resources/db/migration`.
