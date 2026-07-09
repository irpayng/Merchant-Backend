# Merchant Dashboard Backend

Spring Boot backend for the **merchant self-service dashboard** — a portal where
individual merchants can log in, view their terminals, monitor
transaction activity, and access analytics scoped exclusively to their own
business. Unlike the platform-admin (`tms-report-java`) or bank portal
(`super-merchant`), this backend exposes no platform finance, KYC review,
dispute management, or multi-merchant administration features.

It maintains its own PostgreSQL database populated via logical replication from
the microservice cluster (transactions, users, terminals, products,
wallets, notifications, etc.), giving the dashboard fast read access without
loading the transactional services.

## Tech Stack

- Java 21 (Temurin)
- Spring Boot 4.0.5
- PostgreSQL 16 (PostGIS)
- Redis (caching / sessions)
- Apache Kafka (real-time event streaming / SSE)
- gRPC (inter-service communication)
- JWT (authentication)
- AWS S3 (file storage / presigned URLs)
- Apache POI (Excel/CSV export)
- Micrometer + Prometheus (metrics)

## Modules

| Module | Description |
|--------|-------------|
| `auth` | Merchant login, JWT token issuance and refresh |
| `merchantuser` | Merchant user profile management |
| `user` | User data (replicated from user-service) |
| `otp` | One-time password verification |
| `invitation` | Team member invitations |
| `terminal` | Terminal estate management and monitoring |
| `transaction` | Transaction listing, detail, filtering, and export |
| `product` | Products/services available to the merchant |
| `dashboard` | Analytics overview — transaction stats, trends, terminal health, charts |
| `notification` | In-app notifications |
| `activity` | Audit trail / activity logging |
| `setting` | Merchant-level configuration |
| `status` | System and terminal status tracking |
| `upload` | File uploads (documents) |
| `grpc` | gRPC client stubs for calling platform microservices |

## Requirements

- Java 21 (Temurin)
- Maven 3.9+
- PostgreSQL 15+ (with logical replication enabled)
- Redis
- Kafka broker
- Access to the `com.shared:core-utils` private dependency (requires `GH_PAT`)

## Quick Start

```bash
cp .env.example .env   # then fill in secrets (JWT_SECRET, AWS keys, DB creds)
./run-dev.sh           # builds and runs on APP_PORT (default 8120)
```

Or manually:

```bash
mvn clean package -DskipTests
java -jar target/merchant-0.0.1-SNAPSHOT.jar
```

### Docker

```bash
export GH_PAT=<your-github-pat>
docker compose up --build
```

Starts the app on **8120** with a local PostgreSQL on **5472**.

## Database Setup

The merchant database is populated via logical replication from the platform
microservices. To bootstrap locally without replication:

```bash
docker exec -i merchant-db psql -U tms -d merchant < replication/create-tables.sql
```

Key replicated tables: `transactions`, `users`, `terminals`, `products`,
`wallets`, `notifications`, `activities`.

## API Endpoints

JWT Bearer auth required on all endpoints except health.

```bash
# Health (no auth)
curl http://localhost:8120/health

# Login
curl -X POST http://localhost:8120/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"<email>","password":"<password>"}'

TOKEN="<token from login>"

# Dashboard analytics
curl http://localhost:8120/dashboard        -H "Authorization: Bearer $TOKEN"

# Terminals
curl http://localhost:8120/terminals         -H "Authorization: Bearer $TOKEN"

# Transactions (with filtering & export)
curl http://localhost:8120/transactions      -H "Authorization: Bearer $TOKEN"

# Notifications
curl http://localhost:8120/notifications     -H "Authorization: Bearer $TOKEN"
```

## Dashboard Analytics

The `/dashboard` endpoint returns a merchant-scoped overview including:

- Total processed value and transaction count
- Transaction status breakdown (completed, processing, reversed, failed)
- Period-over-period deltas (growth indicators)
- Transaction health metrics (success/failure rates)
- Terminal status summary (active, inactive, faulty)
- Daily/weekly/monthly trend charts
- Top-performing terminals

All figures are restricted to the authenticated merchant's own data via
`MerchantScope` — no cross-merchant visibility.

## Environment Variables

See `.env.example` for the full list. Key groups:

- **App**: `APP_NAME`, `APP_PORT`
- **Database**: `DB_HOST`, `DB_PORT`, `DB_DATABASE`, `DB_USERNAME`, `DB_PASSWORD`
- **JWT**: `JWT_SECRET`
- **gRPC services**: endpoints for user, wallet, config, transaction, notification, ledger, settlement, KYC, dispute services
- **AWS S3**: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_DEFAULT_REGION`, `AWS_BUCKET`
- **Replication sources**: individual DB connections for each microservice

## Architecture Notes

- Data is read-only from the merchant's perspective; writes (e.g., profile
  updates, terminal actions) go through gRPC calls to the relevant microservice.
- Kafka SSE enables real-time transaction notifications pushed to the frontend.
- Prometheus metrics are exposed at `/actuator/prometheus` for observability.
- The app seeds a default merchant admin on first run (`APP_SEED_MERCHANT_ID`).
