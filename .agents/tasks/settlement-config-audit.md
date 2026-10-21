# Settlement Configuration Architecture Audit

## Executive Summary

This audit investigates settlement configuration tables across the system. The key finding is a **split architecture**: tms-config is the source of truth for settlement-affecting configuration, while super-merchant's `settlement_configs` table is **purely cosmetic/informational**—it displays terms to merchants but does NOT drive settlement behavior.

**Root cause of reported bug (wallet not credited, empty settlement history):**
1. Bank-level settlement requires a row in `bank_settlement_configs` (tms-config) with `purchase_settlement_enabled=true`
2. Merchant sweep requires a row in `merchant_settlement_types` (tms-config) with the settlement type
3. **Neither table has a direct API**—both require direct DB insert or gRPC sync from super-merchant, and no onboarding flow creates these rows automatically

---

## Table-by-Table Audit Results

### 1. `settlement_configs` (super-merchant database, schema: supermerchant)

**Entity:** `/Users/mathew/super-merchant/src/main/java/com/tms/report/modules/settlement/model/SettlementConfig.java`

**Has API?** ✅ YES
- Controller: `SettlementConfigController` at `/merchants/{userId}/settlement-config`
- Routes: GET (read), PUT `/schedule`, `/account`, `/msc` (maker-checker workflow)
- Service: `SettlementConfigService` handles reads and TID import seeding

**Has Consumers?** ⚠️ PARTIAL
- Read by super-merchant portal for **display only**
- The `SettlementConfigApprovalExecutor` does call `grpcClient.syncMerchantSettlementType()` on schedule approval, syncing to tms-config

**Source of Truth?** ❌ NO
- Entity JavaDoc explicitly states: *"Informational settlement configuration... This does not trigger any actual settlement logic — it's purely for displaying settlement terms to the merchant."*
- Settlement behavior is driven by tables in tms-config

**Sync Mechanism?**
- **Inbound:** TID import creates/updates rows via `SettlementConfigService.createOrUpdateFromImport()`
- **Outbound:** Schedule approval syncs `settlement_type` to tms-config via gRPC `SyncMerchantSettlementType`
- Account/MSC changes do NOT sync anywhere—they're cosmetic

---

### 2. `bank_settlement_configs` (tms-config database)

**Entity:** `/Users/mathew/tms-config/src/main/java/com/config/entity/BankSettlementConfigEntity.java`

**Has API?** ✅ YES
- gRPC: `GetBankSettlementConfig`, `SetBankSettlementConfig`, `CheckBankSettlement`, `ListHourlySettlementBanks`
- Proto definition in `config_service.proto`

**Has Consumers?** ✅ YES
- `TransactionResultConsumer` (tms-wallet) calls `configClient.getBankSettlementMode(bankCode)` to decide whether to credit wallet
- `BankHourlySettlementScheduler` (tms-settlement) queries banks with hourly settlement enabled
- Entity methods: `isPurchaseSettlementEnabled()`, `isInstantSettlementEnabled()`, `isHourlySettlementEnabled()`, `findHourlyConfigsDueAt()`

**Source of Truth?** ✅ YES
- This IS the authoritative source for bank-level settlement mode
- Entity doc: *"Bank-level settlement configuration. Determines whether IRPay settles purchase transactions for all merchants of a bank"*

**Sync Mechanism?**
- **No UI detected** — requires direct database insert or gRPC call from super-merchant admin portal
- Super-merchant GrpcClient has NO method for `SetBankSettlementConfig` visible in the read portion

---

### 3. `merchant_settlement_types` (tms-config database)

**Entity:** `/Users/mathew/tms-config/src/main/java/com/config/entity/MerchantSettlementTypeEntity.java`

**Has API?** ⚠️ PARTIAL
- gRPC: `GetMerchantSettlementType`, `ListMerchantsBySettlementType`, `SyncMerchantSettlementType` (proto lines 162-167)
- NO REST endpoint for direct CRUD

**Has Consumers?** ✅ YES
- `TransactionResultConsumer` (tms-wallet) calls `configClient.getMerchantSettlementType(userId)` to trigger instant sweep
- `MerchantHourlySettlementScheduler` calls `configClient.listMerchantsBySettlementType("HOURLY")`
- `DeferredSettlementScheduler` queries T+1/T+2/T+3 merchants

**Source of Truth?** ✅ YES for sweep timing
- Drives wallet sweep scheduling based on merchant's configured type (INSTANT, HOURLY, T+1, T+2, T+3)

**Sync Mechanism?**
- **Inbound sync from super-merchant:** `SettlementConfigApprovalExecutor` calls `grpcClient.syncMerchantSettlementType()` when a schedule change is approved
- **Gap:** No rows created during merchant onboarding—table only populated when bank admin explicitly configures and approves a schedule change

---

### 4. `terminal_settlement_destinations` (tms-config database)

**Entity:** `/Users/mathew/tms-config/src/main/java/com/config/entity/TerminalSettlementDestinationEntity.java`

**Has API?** ❌ NO
- No gRPC methods found in `ConfigServiceImpl.java` or `config_service.proto`
- No REST endpoints reference this entity
- Grep for `TerminalSettlementDestinationEntity` in service layer: **no matches**

**Has Consumers?** ❌ NO
- `SettlementDestinationResolver` does NOT use this table—it uses:
  1. TID's `settlementAccountId`
  2. Branch's `settlementAccountId`
  3. Merchant's default `MerchantSettlementAccountEntity`
- Entity doc: *"This replaces the older terminal-based override system (`terminal_settlement_destinations`)"*

**Source of Truth?** ❌ DEAD CODE
- Superseded by the TID/branch/merchant account pool model

**Sync Mechanism?** N/A (dead table)

---

### 5. `merchant_settlement_accounts` (tms-config database)

**Entity:** `/Users/mathew/tms-config/src/main/java/com/config/entity/MerchantSettlementAccountEntity.java`

**Has API?** ✅ YES
- gRPC (proto lines 143-156): `ListMerchantSettlementAccounts`, `GetMerchantSettlementAccount`, `CreateMerchantSettlementAccount`, `UpdateMerchantSettlementAccount`, `SetDefaultMerchantSettlementAccount`, `DeleteMerchantSettlementAccount`
- Exposed via `GrpcClient` in super-merchant for admin portal use

**Has Consumers?** ✅ YES
- `SettlementDestinationResolver.resolveForTid()` and `resolveForUser()` query default accounts
- Used by sweep services to determine where to send funds

**Source of Truth?** ✅ YES
- Central pool of settlement destinations per merchant
- `is_default` flag determines fallback when TID/branch has no explicit mapping

**Sync Mechanism?**
- gRPC from super-merchant portal
- `SettlementConfigService.createPoolAccountFromImport()` creates accounts during TID import

---

## Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           ADMIN CONFIGURATION                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Super-Merchant Portal                                                      │
│  ├── /merchants/{id}/settlement-config                                      │
│  │   └── PUT /schedule → SettlementConfigApprovalExecutor                   │
│  │       ├── Saves to settlement_configs (DISPLAY ONLY)                     │
│  │       └── Calls grpcClient.syncMerchantSettlementType() ───────┐         │
│  │                                                                 │         │
│  ├── TID Import (Excel upload)                                     │         │
│  │   └── SettlementConfigService.createOrUpdateFromImport()        │         │
│  │       ├── Creates settlement_configs row (DISPLAY ONLY)         │         │
│  │       └── Creates merchant_settlement_accounts via gRPC ────────┼────┐    │
│  │                                                                 │    │    │
│  └── [MISSING] Bank Settlement Config UI                           │    │    │
│      └── No API to SetBankSettlementConfig                         │    │    │
│                                                                    ▼    ▼    │
└───────────────────────────────────────────────────────────────────────────────┘
                                                                     │    │
┌───────────────────────────────────────────────────────────────────────────────┐
│                         TMS-CONFIG (Source of Truth)               │    │    │
├───────────────────────────────────────────────────────────────────────────────┤
│                                                                    │    │    │
│  ┌─────────────────────────────────┐  ┌────────────────────────────┴────┴──┐ │
│  │ bank_settlement_configs         │  │ merchant_settlement_types          │ │
│  │ ─────────────────────────────── │  │ ────────────────────────────────── │ │
│  │ • bank_code (unique)            │  │ • user_id (unique)                 │ │
│  │ • purchase_settlement_enabled   │  │ • settlement_type                  │ │
│  │ • settlement_mode (INSTANT/     │  │   (INSTANT/HOURLY/T+1/T+2/T+3)     │ │
│  │   HOURLY)                       │  │                                    │ │
│  │ ⚠️ NO UI - DIRECT DB INSERT     │  │ ✓ Sync from super-merchant         │ │
│  └────────────────┬────────────────┘  └────────────────┬───────────────────┘ │
│                   │                                    │                     │
│  ┌────────────────┴────────────────┐  ┌────────────────┴───────────────────┐ │
│  │ merchant_settlement_accounts    │  │ terminal_settlement_destinations   │ │
│  │ ─────────────────────────────── │  │ ────────────────────────────────── │ │
│  │ • user_id, account_number       │  │ ❌ DEAD CODE                        │ │
│  │ • bank_code, account_name       │  │ • No API                           │ │
│  │ • is_default, status            │  │ • No consumers                     │ │
│  │ ✓ Full gRPC CRUD API            │  │ • Superseded by account pool       │ │
│  └────────────────┬────────────────┘  └────────────────────────────────────┘ │
│                   │                                                          │
└───────────────────┼──────────────────────────────────────────────────────────┘
                    │
┌───────────────────┼──────────────────────────────────────────────────────────┐
│                   │          TRANSACTION PROCESSING                          │
├───────────────────┼──────────────────────────────────────────────────────────┤
│                   │                                                          │
│  Purchase Transaction Completes                                              │
│  │                                                                           │
│  ▼                                                                           │
│  TransactionResultConsumer (tms-wallet)                                      │
│  │                                                                           │
│  ├── Check bank_code on TID metadata                                         │
│  │   └── configClient.getBankSettlementMode(bankCode)                        │
│  │       └── If NULL (no row or disabled) → "bank settles externally" RETURN │
│  │                                                                           │
│  ├── If bank settlement enabled:                                             │
│  │   └── Credit merchant wallet ✅                                           │
│  │                                                                           │
│  └── triggerInstantSweepIfConfigured()                                       │
│      └── configClient.getMerchantSettlementType(userId)                      │
│          └── If NOT "INSTANT" → RETURN (no sweep)                            │
│          └── If "INSTANT" → Publish to merchant-instant-sweep Kafka topic    │
│                                    │                                         │
└────────────────────────────────────┼─────────────────────────────────────────┘
                                     │
┌────────────────────────────────────┼─────────────────────────────────────────┐
│                                    │          TMS-SETTLEMENT                 │
├────────────────────────────────────┼─────────────────────────────────────────┤
│                                    ▼                                         │
│  MerchantInstantSweepConsumer                                                │
│  │                                                                           │
│  └── MerchantSweepService.sweepMerchant()                                    │
│      └── SettlementDestinationResolver.resolveForTid()                       │
│          ├── TID's settlement_account_id                                     │
│          ├── Branch's settlement_account_id                                  │
│          └── merchant_settlement_accounts (is_default=true) ◄────────────────│
│                                                                              │
│  MerchantHourlySettlementScheduler (cron: 0 0 * * * ?)                       │
│  └── configClient.listMerchantsBySettlementType("HOURLY") ◄──────────────────│
│                                                                              │
│  DeferredSettlementScheduler (cron: 0 0 17 * * ?)                            │
│  └── configClient.listMerchantsBySettlementType("T+1"/"T+2"/"T+3") ◄─────────│
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Identified Gaps and Dead Code

### Dead Code: `terminal_settlement_destinations`
- **Status:** DEAD
- **Evidence:** 
  - No gRPC method references it in `ConfigServiceImpl.java`
  - `SettlementDestinationResolver` explicitly states it *replaces* this table
  - No grep hits for the entity name in service code
- **Recommendation:** Remove the entity and drop the table in a cleanup migration

### Gap 1: No API for `bank_settlement_configs`
- **Impact:** Bank admins cannot enable bank-level settlement through any UI
- **Current state:** Requires direct database INSERT
- **Connection to bug:** If this table has no row for the merchant's bank with `purchase_settlement_enabled=true`, the `TransactionResultConsumer` returns early with *"bank settles externally"* and the wallet is never credited

### Gap 2: `merchant_settlement_types` not populated on onboarding
- **Impact:** Newly onboarded merchants have no settlement type, so:
  - INSTANT sweep never triggers (requires `settlement_type=INSTANT`)
  - HOURLY scheduler skips them
  - Deferred schedulers skip them
- **Current state:** Only populated when bank admin manually configures schedule and it's approved
- **Connection to bug:** Empty settlement history because no sweep events are ever published

### Gap 3: `settlement_configs` is cosmetic
- **Impact:** Bank admin changes to settlement schedule in super-merchant UI affect the display but do NOT affect sweep behavior unless:
  1. The change goes through maker-checker approval
  2. `SettlementConfigApprovalExecutor` successfully calls `syncMerchantSettlementType`
  3. `bank_settlement_configs` already has bank-level settlement enabled
- **Connection to bug:** If admin edits `settlement_configs` via direct DB or non-approval path, changes are cosmetic

---

## Root Cause of Reported Bug

**Bug description:** *"It's not getting settled, even the wallet isn't getting updated when bank level settlement is enabled on tms report. Also the settlement history is empty."*

**Probable causes:**

1. **`bank_settlement_configs` table is empty or misconfigured**
   - `TransactionResultConsumer` checks `configClient.getBankSettlementMode(bankCode)`
   - If the bank has no row with `purchase_settlement_enabled=true`, the consumer logs *"bank settles externally"* and returns without crediting wallet

2. **`merchant_settlement_types` table has no row for the merchant**
   - Even if wallet is credited, `triggerInstantSweepIfConfigured()` calls `configClient.getMerchantSettlementType(userId)`
   - If null or not "INSTANT", no sweep event is published
   - For HOURLY/T+N types, the merchant won't appear in scheduler queries

3. **Settlement history is populated by tms-settlement**
   - If no sweeps are triggered (per #2), `MerchantSweepEntity` is never created
   - Settlement history is empty because no sweeps occurred

**Verification steps:**
```sql
-- 1. Check if bank-level settlement is enabled
SELECT * FROM bank_settlement_configs WHERE bank_code = '<merchant_bank_code>';

-- 2. Check if merchant has a settlement type
SELECT * FROM merchant_settlement_types WHERE user_id = <merchant_user_id>;

-- 3. Check if merchant has a settlement account destination
SELECT * FROM merchant_settlement_accounts WHERE user_id = <merchant_user_id>;
```

---

## Recommendation: Target Architecture

### Principle
**tms-config is the source of truth. Data syncs TO display layers (super-merchant), not FROM them.**

### Required Changes

#### 1. Add Admin API for `bank_settlement_configs`
- Add REST/gRPC endpoints in tms-config for CRUD
- Super-merchant UI needs a "Bank Settlement Settings" page calling `SetBankSettlementConfig`
- Alternative: Use existing `SetBankSettlementConfig` gRPC and add `GrpcClient` method in super-merchant

#### 2. Auto-populate `merchant_settlement_types` on merchant onboarding
- When a merchant is created with a bank affiliation, create a default settlement type row
- Default to bank-level mode or a sensible default (e.g., HOURLY)
- Flow: User created → tms-user publishes event → tms-config consumer creates row

#### 3. Remove `terminal_settlement_destinations`
- Drop table in cleanup migration
- Remove entity file

#### 4. Clarify `settlement_configs` role
- Keep as display-only with clear documentation
- OR remove entirely and have super-merchant read settlement config from tms-config via gRPC
- The current dual-write (save locally + sync) creates confusion and opportunities for drift

### Target Table Ownership

| Table | Location | Purpose | Has API | Has Consumers |
|-------|----------|---------|---------|---------------|
| `bank_settlement_configs` | tms-config | Bank-level T+0 mode | ✅ gRPC | ✅ tms-wallet, tms-settlement |
| `merchant_settlement_types` | tms-config | Merchant sweep timing | ✅ gRPC | ✅ tms-wallet, tms-settlement |
| `merchant_settlement_accounts` | tms-config | Sweep destinations | ✅ gRPC | ✅ tms-settlement |
| `settlement_configs` | super-merchant | Display only | ✅ REST | ⚠️ Display only |
| `terminal_settlement_destinations` | tms-config | **DELETE** | ❌ | ❌ |

### Sync Flow (Current → Target)

**Current:**
```
super-merchant settlement_configs → (gRPC sync) → tms-config merchant_settlement_types
```

**Target:**
```
Admin UI → tms-config (authoritative) → (gRPC read) → super-merchant (display)
```

---

## Summary

| Table | Has API? | Used by Business Logic? | Status |
|-------|----------|------------------------|--------|
| `settlement_configs` (super-merchant) | ✅ Yes (REST) | ❌ Display only | **Cosmetic** |
| `bank_settlement_configs` (tms-config) | ⚠️ gRPC only, no UI | ✅ Yes | **Missing UI** |
| `merchant_settlement_types` (tms-config) | ⚠️ gRPC sync only | ✅ Yes | **Missing auto-creation** |
| `terminal_settlement_destinations` (tms-config) | ❌ No | ❌ No | **Dead code** |
| `merchant_settlement_accounts` (tms-config) | ✅ Yes (gRPC) | ✅ Yes | **Working** |
