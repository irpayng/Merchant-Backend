# Multi-Branch Merchant Settlement Architecture

## Overview

This document describes the settlement architecture for IRPay merchants, covering:
1. Multi-branch merchant structure (settlement accounts, branches, TIDs)
2. Settlement routing based on merchant type
3. Direct-to-bank settlement for bank merchants
4. Wallet-based settlement for IRPay direct merchants

---

## 1. Merchant Categories

### 1.1 Bank Merchants
Merchants onboarded through a super-merchant bank portal. Their TIDs have a `bank_code` field identifying the affiliated bank.

**Characteristics:**
- TID has `bank_code` set (e.g., "058" for GTBank)
- Onboarded via super-merchant portal
- Settlement controlled by bank-level configuration
- May have multiple branches and settlement accounts

### 1.2 IRPay Direct Merchants
Merchants onboarded directly through IRPay (typically via mobile app).

**Characteristics:**
- TID has no `bank_code` (null or empty)
- Onboarded via IRPay mobile app or direct onboarding
- Settlement goes to their wallet
- Can optionally configure wallet-to-bank sweeps

---

## 2. Settlement Routing

```
┌─────────────────────────────────────────────────────────────────────┐
│                        PURCHASE TRANSACTION                          │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
                    ┌──────────────────────────┐
                    │  TID has bank_code?      │
                    └──────────────────────────┘
                         │              │
                        YES             NO
                         │              │
                         ▼              ▼
              ┌──────────────────┐  ┌──────────────────┐
              │  BANK MERCHANT   │  │  IRPAY DIRECT    │
              │                  │  │  MERCHANT        │
              └──────────────────┘  └──────────────────┘
                         │                   │
                         ▼                   │
              ┌──────────────────┐           │
              │ Bank settlement  │           │
              │ enabled?         │           │
              └──────────────────┘           │
                   │       │                 │
                  YES      NO                │
                   │       │                 │
                   ▼       ▼                 ▼
            ┌─────────┐ ┌─────────┐   ┌─────────────┐
            │ Direct  │ │ Bank    │   │ Credit      │
            │ to bank │ │ settles │   │ WALLET      │
            │ account │ │ outside │   │             │
            │         │ │ IRPay   │   │             │
            └─────────┘ └─────────┘   └─────────────┘
```

### 2.1 Bank Merchants with Settlement Enabled

When a bank has enabled merchant settlement (`bank_settlement_configs.purchase_settlement_enabled = true`), IRPay settles transactions directly to the merchant's bank account.

**Settlement Modes:**
- `INSTANT` — Settle immediately after transaction completion
- `HOURLY` — Batch and settle at configured hours (e.g., "09:00,15:00,21:00")

**Flow:**
1. Purchase transaction completes
2. `TransactionResultConsumer` checks `bank_code` on the TID
3. Queries `bank_settlement_configs` for that bank
4. If enabled, publishes `DirectSettlementEvent` to Kafka
5. `tms-settlement` consumes and initiates bank transfer
6. Funds go directly to merchant's configured settlement account

### 2.2 Bank Merchants with Settlement Disabled

When a bank has NOT enabled merchant settlement, the bank handles settlement outside IRPay (through NIBSS/switch settlement).

**Flow:**
1. Purchase transaction completes
2. `TransactionResultConsumer` checks `bank_code`
3. Queries `bank_settlement_configs` — not enabled
4. No action taken — bank settles via their own processes

### 2.3 IRPay Direct Merchants

For merchants without bank affiliation, settlement goes to their wallet.

**Flow:**
1. Purchase transaction completes
2. `TransactionResultConsumer` checks `bank_code` — not set
3. Credits merchant's default wallet
4. Merchant can later sweep wallet to bank via settlement windows

---

## 3. Multi-Branch Merchant Structure

### 3.1 Data Model

```
Merchant (UserEntity)
├── MerchantSettlementAccounts[] (merchant owns accounts)
│   ├── id
│   ├── user_id (FK to merchant)
│   ├── account_number
│   ├── bank_code
│   ├── account_name
│   ├── label (e.g., "Main Account", "Lagos Branch Account")
│   ├── is_default (boolean, one per merchant)
│   └── status (active/suspended/closed)
│
├── MerchantBranches[]
│   ├── id
│   ├── user_id (FK to merchant)
│   ├── name (e.g., "Lagos Main", "Abuja Branch")
│   ├── code (unique per merchant, e.g., "LG01")
│   ├── address, state_code, lga_code
│   ├── phone_number, email
│   ├── is_primary (boolean)
│   └── status (active/suspended/closed)
│
├── TIDs[] (can be assigned to branches)
│   ├── branch_id (nullable, FK to MerchantBranches)
│   ├── settlement_account_id (nullable, FK to MerchantSettlementAccounts)
│   └── ... existing TID fields
│
├── Terminals[] (can be assigned to branches)
│   ├── branch_id (nullable, FK to MerchantBranches)
│   └── ... existing terminal fields
│
└── Operators[] (can be scoped to branches)
    ├── branch_id (nullable, null = merchant-wide access)
    └── ... existing operator fields
```

### 3.2 Settlement Account Pool

Settlement accounts are at the **merchant level**, not branch level. This avoids duplication when multiple branches settle to the same account.

**Key Points:**
- One merchant can have multiple settlement accounts
- Each account has a label for identification
- One account is marked as `is_default` for fallback
- TIDs link directly to accounts via `settlement_account_id`

### 3.3 Branch Structure

Branches are organizational units within a merchant:
- Own TIDs and terminals
- Have managers and operators
- Have their own contact info and address
- One branch can be marked as `is_primary` (headquarters)

### 3.4 TID-to-Account Mapping

Each TID can specify which settlement account receives its transactions:

```sql
-- TID explicitly mapped to a settlement account
UPDATE tids 
SET settlement_account_id = 123 
WHERE terminal_id = '12345678';
```

---

## 4. Settlement Destination Resolution

When settling a transaction, the destination account is resolved in priority order:

### 4.1 Resolution Order

1. **TID's `settlement_account_id`** — If the TID has an explicit account mapping
2. **Merchant's default account** — From `merchant_settlement_accounts` where `is_default = true`
3. **Legacy fallback** — From `instant_settlements.destination*` fields

### 4.2 Resolution Service

```java
// SettlementDestinationResolver.java (tms-config)
public Optional<Destination> resolveForTid(TidEntity tid) {
    // 1. Check TID's explicit settlement account
    if (tid.settlementAccountId != null) {
        MerchantSettlementAccountEntity account = 
            MerchantSettlementAccountEntity.findById(tid.settlementAccountId);
        if (account != null && "active".equals(account.status)) {
            return Optional.of(new Destination(
                account.accountNumber, 
                account.bankCode, 
                account.accountName,
                "tid:" + tid.id
            ));
        }
    }
    
    // 2. Fall back to merchant's default
    return resolveForUser(tid.userId);
}

public Optional<Destination> resolveForUser(Long userId) {
    // 1. Check merchant's default settlement account
    MerchantSettlementAccountEntity defaultAccount = 
        MerchantSettlementAccountEntity.findDefaultByUserId(userId);
    if (defaultAccount != null) {
        return Optional.of(new Destination(...));
    }
    
    // 2. Legacy fallback: instant_settlements
    InstantSettlementEntity legacy = 
        InstantSettlementEntity.findActiveByUserId(userId);
    if (legacy != null && legacy.destinationAccountNumber != null) {
        return Optional.of(new Destination(...));
    }
    
    return Optional.empty();
}
```

---

## 5. Database Schema

### 5.1 New Tables

#### merchant_settlement_accounts
```sql
CREATE TABLE merchant_settlement_accounts (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    account_number      VARCHAR(30) NOT NULL,
    bank_code           VARCHAR(20) NOT NULL,
    account_name        VARCHAR(255),
    label               VARCHAR(100),
    is_default          BOOLEAN NOT NULL DEFAULT false,
    status              VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at          TIMESTAMP,
    updated_at          TIMESTAMP
);

CREATE INDEX idx_merchant_settlement_acct_user_id ON merchant_settlement_accounts(user_id);
```

#### merchant_branches
```sql
CREATE TABLE merchant_branches (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    name                VARCHAR(255) NOT NULL,
    code                VARCHAR(20) NOT NULL,
    address             TEXT,
    state_code          VARCHAR(10),
    lga_code            VARCHAR(20),
    phone_number        VARCHAR(20),
    email               VARCHAR(255),
    status              VARCHAR(20) NOT NULL DEFAULT 'active',
    is_primary          BOOLEAN NOT NULL DEFAULT false,
    created_at          TIMESTAMP,
    updated_at          TIMESTAMP
);

CREATE UNIQUE INDEX idx_merchant_branch_code ON merchant_branches(user_id, code);
```

#### direct_settlements (history)
```sql
CREATE TABLE direct_settlements (
    id                          BIGSERIAL PRIMARY KEY,
    user_id                     BIGINT NOT NULL,
    transaction_reference       VARCHAR(50) NOT NULL UNIQUE,
    settlement_reference        VARCHAR(50),
    amount                      NUMERIC(18,2) NOT NULL,
    settlement_type             VARCHAR(30) NOT NULL,
    terminal_id                 VARCHAR(20),
    bank_code                   VARCHAR(20),
    destination_account_number  VARCHAR(30),
    destination_bank_code       VARCHAR(20),
    destination_account_name    VARCHAR(255),
    destination_source          VARCHAR(50),
    status                      VARCHAR(20) NOT NULL,
    error_message               VARCHAR(500),
    created_at                  TIMESTAMP
);
```

Note: This is separate from the main `settlements` table which tracks the resolution
workflow (pending → scheduled → resolved → paid). Direct settlements bypass the wallet
and go straight to the bank.

#### pending_settlements (hourly queue)
```sql
CREATE TABLE pending_settlements (
    id                          BIGSERIAL PRIMARY KEY,
    user_id                     BIGINT NOT NULL,
    transaction_reference       VARCHAR(50) NOT NULL UNIQUE,
    amount                      NUMERIC(18,2) NOT NULL,
    settlement_type             VARCHAR(30) NOT NULL,
    terminal_id                 VARCHAR(20),
    bank_code                   VARCHAR(20),
    status                      VARCHAR(20) NOT NULL DEFAULT 'pending',
    settlement_reference        VARCHAR(50),
    destination_account_number  VARCHAR(30),
    destination_bank_code       VARCHAR(20),
    destination_account_name    VARCHAR(255),
    error_message               VARCHAR(500),
    retry_count                 INTEGER NOT NULL DEFAULT 0,
    created_at                  TIMESTAMP,
    processed_at                TIMESTAMP,
    updated_at                  TIMESTAMP
);
```

### 5.2 Column Additions

#### tids table
```sql
ALTER TABLE tids ADD COLUMN branch_id BIGINT;
ALTER TABLE tids ADD COLUMN settlement_account_id BIGINT;
CREATE INDEX idx_tids_branch_id ON tids(branch_id);
CREATE INDEX idx_tids_settlement_account_id ON tids(settlement_account_id);
```

#### terminals table
```sql
ALTER TABLE terminals ADD COLUMN branch_id BIGINT;
CREATE INDEX idx_terminals_branch_id ON terminals(branch_id);
```

#### operators table
```sql
ALTER TABLE operators ADD COLUMN branch_id BIGINT;
CREATE INDEX idx_operators_branch_id ON operators(branch_id);
```

---

## 6. Service Components

### 6.1 tms-config

| Component | Purpose |
|-----------|---------|
| `MerchantSettlementAccountEntity` | JPA entity for settlement accounts |
| `MerchantBranchEntity` | JPA entity for branches |
| `SettlementDestinationResolver` | Resolves destination for TID/user |
| `SettlementWindowResource` | HTTP endpoint for settlement discovery |
| `ConfigServiceImpl.resolveSettlementDestination()` | gRPC endpoint for destination resolution |

### 6.2 tms-wallet

| Component | Purpose |
|-----------|---------|
| `TransactionResultConsumer` | Routes settlements based on merchant type |
| `DirectSettlementEvent` | Kafka event for direct bank settlements |
| `DirectSettlementPublisher` | Publishes to `direct-settlement` topic |
| `ConfigClient` | Queries bank settlement config |

### 6.3 tms-settlement

| Component | Purpose |
|-----------|---------|
| `DirectSettlementConsumer` | Consumes from `direct-settlement` topic |
| `DirectSettlementService` | Processes instant and hourly settlements |
| `PendingSettlementEntity` | Queue for hourly batch processing |
| `DirectSettlementEntity` | Direct settlement history for audit |
| `SettlementEntity` | Main settlement entity (resolution workflow) |
| `BankHourlySettlementScheduler` | Processes hourly batches at configured times |
| `ConfigClient` | Queries settlement destinations |

### 6.4 tms-report-java (super-merchant)

| Component | Purpose |
|-----------|---------|
| `BankSettlementConfigService` | Manages bank settlement configuration |
| `BankSettlementConfigController` | REST API for config management |

---

## 7. Kafka Topics

| Topic | Producer | Consumer | Purpose |
|-------|----------|----------|---------|
| `direct-settlement` | tms-wallet | tms-settlement | Bank merchant settlements |
| `direct-settlement-dlq` | tms-settlement | — | Dead letter queue for failed processing |

---

## 8. Configuration Tables

### 8.1 bank_settlement_configs

Controls whether IRPay settles for a bank's merchants:

```sql
CREATE TABLE bank_settlement_configs (
    id                              BIGSERIAL PRIMARY KEY,
    bank_code                       VARCHAR(20) NOT NULL UNIQUE,
    purchase_settlement_enabled     BOOLEAN NOT NULL DEFAULT false,
    settlement_mode                 VARCHAR(20) NOT NULL DEFAULT 'INSTANT',
    settlement_times                VARCHAR(255),  -- For HOURLY: "09:00,15:00,21:00"
    created_at                      TIMESTAMP,
    updated_at                      TIMESTAMP
);
```

### 8.2 instant_settlements (legacy, for IRPay direct merchants)

Used for wallet-to-bank sweeps for IRPay direct merchants:

```sql
-- Existing table, used for:
-- 1. IRPay direct merchant settlement window configuration
-- 2. Legacy fallback for destination resolution
```

---

## 9. API Endpoints

### 9.1 Settlement Destination Resolution (gRPC)

```protobuf
rpc ResolveSettlementDestination (ResolveSettlementDestinationRequest) 
    returns (ResolveSettlementDestinationResponse);

message ResolveSettlementDestinationRequest {
  int64 tid_id = 1;
  int64 user_id = 2;
  string terminal_id = 3;
}

message ResolveSettlementDestinationResponse {
  bool found = 1;
  string account_number = 2;
  string bank_code = 3;
  string account_name = 4;
  string source = 5;  // "tid:123", "merchant_default:456", "instant_settlement:789"
}
```

### 9.2 Bank Settlement Config (REST)

```
GET  /bank-settlement-configs/{bankCode}
POST /bank-settlement-configs
PUT  /bank-settlement-configs/{bankCode}
```

---

## 10. Settlement Types Summary

| Type | Merchant Category | Destination | Timing |
|------|------------------|-------------|--------|
| `bank_instant` | Bank merchant (IRPay settles) | Bank account | Immediate |
| `bank_hourly` | Bank merchant (IRPay settles) | Bank account | Configured hours |
| N/A | Bank merchant (bank settles) | N/A | Bank handles externally |
| Wallet credit | IRPay direct merchant | Wallet | Immediate |

---

## 11. Files Modified/Created

### tms-config
- `MerchantSettlementAccountEntity.java` (new)
- `MerchantBranchEntity.java` (new)
- `SettlementDestinationResolver.java` (new)
- `TidEntity.java` (modified — added branch_id, settlement_account_id)
- `TerminalEntity.java` (modified — added branch_id)
- `SettlementWindowResource.java` (modified)
- `ConfigServiceImpl.java` (modified)
- `config_service.proto` (modified)

### tms-wallet
- `TransactionResultConsumer.java` (modified)
- `DirectSettlementEvent.java` (new)
- `DirectSettlementPublisher.java` (new)
- `ConfigClient.java` (modified)
- `application.properties` (modified)

### tms-settlement
- `DirectSettlementConsumer.java` (new)
- `DirectSettlementService.java` (new)
- `DirectSettlementEvent.java` (new)
- `DirectSettlementEntity.java` (new — direct bank settlement history)
- `PendingSettlementEntity.java` (new)
- `SettlementEntity.java` (existing — extended with resolution workflow fields)
- `ConfigClient.java` (modified)
- `config_service.proto` (modified)
- `pom.xml` (modified — added Kafka dependency)
- `application.properties` (modified)

### tms-user
- `OperatorEntity.java` (modified — added branch_id)
- `V7__add_operator_branch_id.sql` (new migration)

### tms-report-java
- `BankSettlementConfigService.java` (existing)
- `BankSettlementConfigController.java` (existing)

### Merchant-Backend
- `replication/create-tables.sql` (modified)

### super-merchant
- `admin-schema.sql` (modified)

---

## 12. Migration Path

1. **Deploy schema changes** — Run DDL for new tables and columns
2. **Deploy tms-config** — New entities and resolution service
3. **Deploy tms-settlement** — New consumer and processing service
4. **Deploy tms-wallet** — Updated routing logic
5. **Configure bank settlements** — Enable for specific banks via super-merchant portal
6. **Migrate existing data** — Create settlement accounts for existing merchants with destinations

---

## 13. Future Considerations

1. **T+1 Settlement** — Add support for next-day batch settlement
2. **Settlement Reconciliation** — Automated matching of settlements with bank statements
3. **Settlement Notifications** — Notify merchants on successful/failed settlements
4. **Settlement Reports** — Dashboard for settlement history and analytics
5. **Multi-currency** — Support for settlements in different currencies
