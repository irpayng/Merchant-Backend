# Super Merchant (Bank Portal Backend)

Spring Boot backend for the **super-merchant** portal — used by banks to onboard
merchants (TID upload), monitor their terminal estate, and view transaction
activity. It is a trimmed clone of `tms-report-java`: the platform-admin and
finance surfaces (funding, manual debit/credit, provider configs, ledger,
settlements, KYC review, disputes, traffic logs, etc.) are intentionally absent
from both the API and the data model.

It maintains its own PostgreSQL database populated by logical replication from
the microservice databases (transactions, users, terminals, tids, products),
exactly like `tms-report-java`, but subscribes to a narrower set of tables.

## Requirements

- Java 21 (Temurin)
- Maven 3.9+
- PostgreSQL 15+

## Quick Start

```bash
cp .env.example .env   # then edit
./run-dev.sh           # builds and runs on APP_PORT (default 8110)
```

Or manually:

```bash
mvn clean package -DskipTests
java -jar target/super-merchant-0.0.1-SNAPSHOT.jar
```

### Docker

```bash
docker compose up --build
```

Starts the app on **8110** with a local PostgreSQL on **5471**.

## API Endpoints

JWT Bearer auth. The default seeded admin is `admin@irpay.ng` / `milimatr`
(change in production).

```bash
# Health (no auth)
curl http://localhost:8110/health

# Login
curl -X POST http://localhost:8110/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@irpay.ng","password":"milimatr"}'

TOKEN="<token from login>"

# Terminals / TIDs / transactions / dashboard
curl http://localhost:8110/terminals     -H "Authorization: Bearer $TOKEN"
curl http://localhost:8110/tids          -H "Authorization: Bearer $TOKEN"
curl http://localhost:8110/transactions  -H "Authorization: Bearer $TOKEN"
curl http://localhost:8110/dashboard     -H "Authorization: Bearer $TOKEN"
```

## Kept modules

`auth`, `admin`, `role`, `privilege`, `otp`, `invitation`, `notification`,
`setting`, `activity`, `user`, `onboarding`, `terminal`, `tid`, `upload`,
`transaction`, `product`, `status`, `dashboard`, `grpc`.

Everything else from `tms-report-java` (funding, ledger, settlement, provider,
dispute, KYC review, traffic logs, fraud monitoring, etc.) has been removed.

## Notes / follow-ups

- The `transaction` listing/detail SQL still contains provider/ledger joins
  inherited from the clone; those tables are not replicated here, so the
  provider/`provider_cost` columns must be stripped from the queries (see
  `TransactionService`) before the transaction pages are wired up end-to-end.
- Multi-tenant scoping (each bank only sees its own merchants/terminals) is not
  yet implemented — see the architecture notes.
