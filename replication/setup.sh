#!/bin/bash
# =============================================================================
# Logical replication: report DB (tms-report-db) -> super-merchant-db.
#
# super-merchant owns its auth/admin tables in the `supermerchant` schema and
# reads business data (transactions, users, terminals, tids, products, ...)
# from `public`, which is populated here by subscribing to the report DB.
#
# Steps (idempotent):
#   1. Pipe the schema of the needed public tables from source -> target
#      (logical replication does not copy schema, so tables must pre-exist).
#   2. Set REPLICA IDENTITY FULL on the source tables (needed for UPDATE/DELETE
#      replication of tables without a suitable PK).
#   3. Create a table-scoped publication on the source.
#   4. Create a subscription on the target (initial snapshot via copy_data).
#
# Runs entirely through `docker exec`, so no host psql/pg_dump is required and
# the pg_dump/server versions always match. Both DB containers must be on the
# shared `tms-network`.
# =============================================================================
set -euo pipefail

# ── Source (publisher) — the report database ────────────────
SOURCE_CONTAINER=${SOURCE_CONTAINER:-tms-report-db}
SOURCE_DB=${SOURCE_DB:-tms_report}
SOURCE_USER=${SOURCE_USER:-tms}
SOURCE_PASS=${SOURCE_PASS:-secret}
# Host/port the subscriber's apply worker uses to reach the source over the
# shared docker network (container name resolves on tms-network).
SOURCE_NET_HOST=${SOURCE_NET_HOST:-tms-report-db}
SOURCE_NET_PORT=${SOURCE_NET_PORT:-5432}

# ── Target (subscriber) — super-merchant's own DB ───────────
TARGET_CONTAINER=${TARGET_CONTAINER:-super-merchant-db}
TARGET_DB=${TARGET_DB:-super_merchant}
TARGET_USER=${TARGET_USER:-tms}
TARGET_PASS=${TARGET_PASS:-secret}

PUB=super_merchant_pub
SUB=super_merchant_sub

# Business tables super-merchant reads from `public`. Kept to the set the
# service actually queries (dashboard, transactions, terminals, users,
# onboarding, statements/wallets, tenant scoping).
TABLES="transactions users profiles products tids terminals terminal_tid statuses channels payment_methods tiers countries states lgas addresses onboardings wallets statements virtual_accounts nins"

src() { docker exec -e PGPASSWORD="$SOURCE_PASS" "$SOURCE_CONTAINER" psql -U "$SOURCE_USER" -d "$SOURCE_DB" "$@"; }
tgt() { docker exec -e PGPASSWORD="$TARGET_PASS" "$TARGET_CONTAINER" psql -U "$TARGET_USER" -d "$TARGET_DB" "$@"; }

DUMP_ARGS=""
CSV=""
for t in $TABLES; do
  DUMP_ARGS="$DUMP_ARGS -t public.$t"
  CSV="$CSV${CSV:+, }public.$t"
done

echo "== Step 1/4: piping schema ($SOURCE_DB -> $TARGET_DB) =="
docker exec -e PGPASSWORD="$SOURCE_PASS" "$SOURCE_CONTAINER" \
  pg_dump -U "$SOURCE_USER" -d "$SOURCE_DB" --schema-only --no-owner --no-privileges --no-comments $DUMP_ARGS \
  | docker exec -i -e PGPASSWORD="$TARGET_PASS" "$TARGET_CONTAINER" \
    psql -U "$TARGET_USER" -d "$TARGET_DB" -q -v ON_ERROR_STOP=0 >/dev/null 2>&1 || true
echo "   schema piped"

echo "== Step 2/4: REPLICA IDENTITY FULL on source tables =="
for t in $TABLES; do
  src -q -c "ALTER TABLE IF EXISTS public.$t REPLICA IDENTITY FULL;" >/dev/null 2>&1 || true
done
echo "   done"

echo "== Step 3/4: publication '$PUB' on source =="
src -q -c "DO \$\$ BEGIN IF EXISTS (SELECT 1 FROM pg_publication WHERE pubname='$PUB') THEN EXECUTE 'DROP PUBLICATION $PUB'; END IF; END \$\$;"
src -q -c "CREATE PUBLICATION $PUB FOR TABLE $CSV;"
echo "   published: $CSV"

echo "== Step 4/4: subscription '$SUB' on target =="
EXISTS=$(tgt -t -A -c "SELECT count(*) FROM pg_subscription WHERE subname='$SUB';" 2>/dev/null | tr -d '[:space:]')
if [ "$EXISTS" != "0" ]; then
  echo "   subscription already exists — skipping create"
else
  tgt -q -c "CREATE SUBSCRIPTION $SUB CONNECTION 'host=$SOURCE_NET_HOST port=$SOURCE_NET_PORT dbname=$SOURCE_DB user=$SOURCE_USER password=$SOURCE_PASS' PUBLICATION $PUB WITH (copy_data = true);"
  echo "   subscription created (initial snapshot copying)"
fi

echo "== Step 5/5: reconcile schema for the report-DB source =="
# The report DB (tms-report-java's legacy monolith schema) differs from the
# microservice schema super-merchant's queries were written against:
#   - wallets is polymorphic (walletable_type/id), but the app reads
#     wallets.user_id / pnd / pnd_reason
#   - users lacks the microservice status/KYC columns
#   - instant_settlements does not exist
# Reconcile on the subscriber so queries resolve. user_id is DERIVED from the
# polymorphic ref via a generated column (so ongoing replication keeps it in
# sync); the truly-absent fields (pnd, suspension/block/freeze, instant
# settlement) degrade to false/empty since the report DB doesn't track them.
docker exec -i -e PGPASSWORD="$TARGET_PASS" "$TARGET_CONTAINER" \
  psql -U "$TARGET_USER" -d "$TARGET_DB" -q -v ON_ERROR_STOP=0 <<'SQL' >/dev/null 2>&1 || true
ALTER TABLE wallets ADD COLUMN IF NOT EXISTS pnd boolean DEFAULT false;
ALTER TABLE wallets ADD COLUMN IF NOT EXISTS pnd_reason text;
ALTER TABLE wallets ADD COLUMN IF NOT EXISTS user_id bigint
  GENERATED ALWAYS AS (CASE WHEN walletable_type = 'users' THEN walletable_id END) STORED;
ALTER TABLE users ADD COLUMN IF NOT EXISTS frozen_at timestamp;
ALTER TABLE users ADD COLUMN IF NOT EXISTS suspended_at timestamp;
ALTER TABLE users ADD COLUMN IF NOT EXISTS suspended_reason text;
ALTER TABLE users ADD COLUMN IF NOT EXISTS suspended_by_type varchar(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS blocked_at timestamp;
ALTER TABLE users ADD COLUMN IF NOT EXISTS blocked_reason text;
ALTER TABLE users ADD COLUMN IF NOT EXISTS bvn_photo_url text;
ALTER TABLE users ADD COLUMN IF NOT EXISTS selfie_url text;
ALTER TABLE users ADD COLUMN IF NOT EXISTS bvn varchar(50);
CREATE TABLE IF NOT EXISTS instant_settlements (
  id bigint, user_id bigint, status varchar(50), created_at timestamp, updated_at timestamp
);
SQL
echo "   reconciled"

echo ""
echo "Replication configured. Verify with: docker exec $TARGET_CONTAINER psql -U $TARGET_USER -d $TARGET_DB -c 'SELECT count(*) FROM transactions;'"
