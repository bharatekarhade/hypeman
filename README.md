# Hypeman

A Spring Boot API (`src/`) and its iOS app (`ios/`).

## Server

You need JDK 25 and Docker. Processing uploaded pack clips also needs `ffmpeg` and `ffprobe` on your PATH.

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

### API

Everything is under `/v1`. Errors come back as `{code, message, requestId}`.

| Endpoint | Who | What |
|---|---|---|
| `POST /auth/apple`, `/auth/google`, `/auth/refresh` | anyone | Sign in, and swap a refresh token for new tokens |
| `POST /auth/logout` | signed in | Sign this device out |
| `GET`, `PATCH`, `DELETE /me` | signed in | The account; deleting it deletes everything the server keeps for it |
| `GET /packs` | anyone | The store's catalog |
| `POST /packs/{id}/media` | signed in | Signed links to a pack's clips; a paid pack needs its purchase. Each call counts as a download |
| `GET`, `POST /purchases` | signed in | What the user bought; report a StoreKit purchase or restore |
| `POST /app-store/notifications` | Apple | Refunds and revocations |
| `GET /goals?since=`, `PUT /goals` | signed in | Goal sync: pull what changed since the last cursor, push up to 500 changes |
| `/admin/**` | admin token | Making packs, download numbers (`GET /admin/packs/downloads`), bans and the audit log |

## iOS

Open `ios/Hypeman.xcodeproj` in Xcode. Shared code is in the `ios/HypemanKit` Swift package, and
`ios/Hypeman.storekit` is the StoreKit configuration file for testing purchases.
