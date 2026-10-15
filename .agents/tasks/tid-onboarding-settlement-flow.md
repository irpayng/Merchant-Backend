# TID Onboarding Flow Investigation for Settlement Data

## Executive Summary

This investigation traces how merchant TID onboarding data flows from the uploaded Excel document to the tms-config tables that settlement depends on. **The biggest gap identified:** the `merchant_settlement_types` table (which drives settlement sweep scheduling) is NOT automatically populated during TID import. It only gets populated via an explicit gRPC sync call when a settlement schedule change is approved in super-merchant — but there's no initial auto-creation when a merchant is first onboarded via TID import.

---

## 1. Current Data Flow Diagram

```
┌────────────────────────────────────────────────────────────────────────────────┐
│                        SUPER-MERCHANT PORTAL                                   │
│                                                                                │
│  Bank Admin uploads TID Excel file → POST /tids (TidController)               │
│                                      │                                         │
│                                      ▼                                         │
│                              ConfigHttpClient.uploadTids()                     │
│                                      │                                         │
│                                      │ HTTP + multipart                        │
│                                      ▼                                         │
└──────────────────────────────────────┼─────────────────────────────────────────┘
                                       │
┌──────────────────────────────────────▼─────────────────────────────────────────┐
│                           TMS-CONFIG SERVICE                                   │
│                                                                                │
│  POST /tids/upload → TerminalResource.uploadTids()                            │
│                              │                                                 │
│                              ▼                                                 │
│                      ImportService.importTids()                                │
│                              │                                                 │
│        ┌─────────────────────┼─────────────────────┐                          │
│        │                     │                     │                          │
│        ▼                     ▼                     ▼                          │
│   TidService.store()   UserClient           MerchantSettlement                │
│        │               .findOrCreate()       AccountEntity                    │
│        │                     │               .persist()                       │
│        ▼                     ▼                                                │
│   ┌─────────┐         ┌─────────┐         ┌────────────────────────┐         │
│   │  tids   │         │ users   │         │ merchant_settlement_   │         │
│   │ table   │         │ (gRPC)  │         │ accounts               │         │
│   └─────────┘         └─────────┘         └────────────────────────┘         │
│                                                                                │
│   MISSING: merchant_settlement_types is NOT auto-populated here!              │
│                                                                                │
└────────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       │ Response includes settlementConfigs[]
                                       ▼
┌──────────────────────────────────────┴─────────────────────────────────────────┐
│                        SUPER-MERCHANT PORTAL                                   │
│                                                                                │
│  TidController receives response, calls:                                       │
│  settlementConfigService.createOrUpdateFromImport(settlementConfigs)          │
│                              │                                                 │
│                              ▼                                                 │
│                 ┌─────────────────────────┐                                   │
│                 │   settlement_configs    │  (super-merchant schema)          │
│                 │   DISPLAY ONLY - does   │                                   │
│                 │   NOT drive sweeps!     │                                   │
│                 └─────────────────────────┘                                   │
│                                                                                │
└────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. What's in the TID Onboarding Document

Based on analysis of `ImportService.importTids()`, the expected Excel columns are:

### Required Fields
| Column | Maps To | Description |
|--------|---------|-------------|
| `terminalId` | `tids.terminal_id` | 8-char NIBSS TID |
| `merchantId` | `tids.merchant_id` | ISO 8583 field 42 |
| `merchantName` | `tids.merchant_name` | Merchant display name |
| `bankAccNo` | `tids.bank_acc_no` | Legacy settlement account |
| `email` | `tids.email` | Merchant email (triggers user auto-creation) |

### Settlement Configuration Fields (Optional)
| Column | Extracted To | Notes |
|--------|-------------|-------|
| `settlementType` | `settlement_configs.settlement_type` | INSTANT, HOURLY, T+1, T+2, T+3 |
| `settlementDays` | `settlement_configs.settlement_days` | Monday-Friday, etc. |
| `settlementAccountNumber` | Primary settlement account | |
| `settlementBankCode` | Primary settlement bank | |
| `settlementAccountName` | Account holder name | |
| `settlementAccount2Number` | Second account | Supports up to 3 accounts |
| `settlementAccount2BankCode` | Second account bank | |
| `settlementAccount3Number` | Third account | |
| `settlementAccount3BankCode` | Third account bank | |

### MSC Fields (Aesthetics Only)
| Column | Notes |
|--------|-------|
| `mscPosPercentage` | Display only |
| `instantTransferFee` | Display only |
| `monthlyTerminalFee` | Display only |

---

## 3. What Gets Populated During Import

### ✅ Populated Automatically

| Table | Database | Populated By | Notes |
|-------|----------|--------------|-------|
| `tids` | tms-config | `TidService.store()` | Core TID data, `branchId` nullable |
| `users` | tms-user (gRPC) | `UserClient.findOrCreateMerchant()` | Merchant account auto-created from email |
| `merchant_settlement_accounts` | tms-config | `ImportService.importTids()` | Settlement account pool populated from Excel |
| `settlement_configs` | super-merchant | `SettlementConfigService.createOrUpdateFromImport()` | **DISPLAY ONLY - does NOT drive sweeps** |

### ❌ NOT Populated (Gaps)

| Table | Database | Required For | Impact |
|-------|----------|--------------|--------|
| `merchant_settlement_types` | tms-config | HOURLY/INSTANT sweep scheduling | **CRITICAL:** Without this, `MerchantHourlySettlementScheduler` and instant sweep never find the merchant |
| `merchant_branches` | tms-config | Multi-branch settlement routing | `TidEntity.branchId` is nullable; no branches created during import |
| `bank_settlement_configs` | tms-config | Bank-level settlement enable/disable | Must be pre-configured by bank admin before TID import |

---

## 4. The Critical Missing Link

### How Settlement Finds Merchants to Sweep

The settlement service queries merchants via gRPC:

```java
// MerchantHourlySettlementScheduler.java
List<Long> merchantIds = configClient.listMerchantsBySettlementType("HOURLY");

// ConfigServiceImpl.java responds from:
List<Long> userIds = MerchantSettlementTypeEntity.findUserIdsBySettlementType(request.getSettlementType());
```

**If `merchant_settlement_types` has no row for a merchant → they never appear in the sweep schedule.**

### Current Sync Mechanism (Post-Approval Only)

The only path to populate `merchant_settlement_types` is via gRPC after an admin explicitly changes the settlement type:

```java
// ConfigServiceImpl.syncMerchantSettlementType() - gRPC
@Override
@Transactional
public void syncMerchantSettlementType(SyncMerchantSettlementTypeRequest request, ...) {
    MerchantSettlementTypeEntity entity = MerchantSettlementTypeEntity.findByUserId(userId);
    if (entity == null) {
        entity = new MerchantSettlementTypeEntity();
        entity.userId = userId;
    }
    entity.settlementType = type;
    entity.persist();
}
```

**This is never called during TID import.**

---

## 5. Branch → Account Mapping

### Current Relationship

```
TidEntity
├── branchId (nullable) → MerchantBranchEntity
│                              └── settlementAccountId → MerchantSettlementAccountEntity
├── settlementAccountId → MerchantSettlementAccountEntity (direct override)
└── userId → User's default settlement account
```

### SettlementDestinationResolver Resolution Order

From `/Users/mathew/tms-config/src/main/java/com/config/service/SettlementDestinationResolver.java`:

1. **TID's `settlement_account_id`** — explicit account assigned to this TID
2. **Branch's `settlement_account_id`** — account assigned to the TID's branch
3. **Merchant's default account** — `merchant_settlement_accounts` where `is_default=true`

### Gap: Branches Not Created During Import

- `TidEntity.branchId` remains `null` during import
- No `merchant_branches` rows are created
- Branch-level settlement routing is unavailable until branches are manually created via UI/API

---

## 6. UI Update Flow

### Where Settlement Data Can Be Updated

| Surface | Location | What it Updates | Syncs to tms-config? |
|---------|----------|-----------------|----------------------|
| super-merchant `/merchants/{userId}/settlement` | `MerchantSettlementController` | Instant settlement windows (maker-checker) | ✅ Yes, via `ApprovalService` |
| super-merchant `/tids` upload | `TidController` | `settlement_configs` display table | ❌ No sync to `merchant_settlement_types` |
| tms-report-java "SM Banks" page | `BankSettlementConfig` | `bank_settlement_configs` | ✅ Direct tms-config |
| Missing: No UI to set `settlement_type` | - | `merchant_settlement_types` | ❌ No UI exists |

---

## 7. Recommended Fix

### Where to Add the Sync: During Import

The cleanest fix is to populate `merchant_settlement_types` during TID import when `settlementType` is present in the Excel row.

**Entry point:** `ImportService.importTids()` in tms-config

### Concrete Implementation

```java
// In ImportService.importTids(), after creating settlement accounts:

// Sync merchant settlement type to the sweep-scheduling table
String settlementType = row.get("settlementType");
if (settlementType != null && !settlementType.isBlank() && merchantUserId != null) {
    try {
        MerchantSettlementTypeEntity mst = MerchantSettlementTypeEntity.findByUserId(merchantUserId);
        if (mst == null) {
            mst = new MerchantSettlementTypeEntity();
            mst.userId = merchantUserId;
        }
        mst.settlementType = settlementType.trim();
        mst.updatedAt = Instant.now();
        mst.persist();
        LOG.infof("Synced settlement type for user %d: %s", merchantUserId, settlementType);
    } catch (Exception e) {
        LOG.warnf("Failed to sync settlement type for user %d: %s", merchantUserId, e.getMessage());
        // Don't fail import - settlement type sync is non-critical
    }
}
```

### Default Settlement Type for Merchants Without Explicit Config

Consider defaulting newly imported merchants to `HOURLY` if no `settlementType` is in the Excel:

```java
// After merchant creation, if no settlement type in row, default to HOURLY
if (settlementType == null && merchantUserId != null) {
    MerchantSettlementTypeEntity mst = MerchantSettlementTypeEntity.findByUserId(merchantUserId);
    if (mst == null) {
        mst = new MerchantSettlementTypeEntity();
        mst.userId = merchantUserId;
        mst.settlementType = "HOURLY"; // Default
        mst.updatedAt = Instant.now();
        mst.persist();
    }
}
```

---

## 8. Complete TID → Settlement Relationship Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           ONBOARDING FLOW                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   Excel Upload                                                              │
│       │                                                                     │
│       ▼                                                                     │
│   ┌─────────────────┐      ┌─────────────────┐                             │
│   │      tids       │──────│     users       │                             │
│   │  (terminal_id,  │      │  (auto-created  │                             │
│   │   merchant_id,  │      │   from email)   │                             │
│   │   user_id,      │      └────────┬────────┘                             │
│   │   branch_id=NULL│               │                                       │
│   └────────┬────────┘               │                                       │
│            │                        │                                       │
│            │                        ▼                                       │
│            │            ┌─────────────────────────────┐                    │
│            │            │ merchant_settlement_accounts │ ← Created ✅       │
│            │            │ (account pool from Excel)    │                    │
│            │            └──────────────────────────────┘                    │
│            │                                                                │
│            │            ┌─────────────────────────────┐                    │
│            └───────────►│  merchant_settlement_types   │ ← NOT Created ❌   │
│                         │  (sweep schedule - MISSING)  │                    │
│                         └─────────────────────────────┘                    │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                          SETTLEMENT FLOW                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   MerchantHourlySettlementScheduler (runs every hour)                      │
│       │                                                                     │
│       ▼ gRPC: listMerchantsBySettlementType("HOURLY")                      │
│   ┌─────────────────────────────┐                                          │
│   │  merchant_settlement_types   │ ← Query source                          │
│   │  user_id | settlement_type   │                                          │
│   └──────────────┬───────────────┘                                          │
│                  │ If no row → merchant never swept                        │
│                  ▼                                                          │
│   MerchantSweepService.sweepMerchant(userId, ...)                          │
│       │                                                                     │
│       ▼ gRPC: resolveSettlementDestination(terminalId, userId)             │
│   SettlementDestinationResolver                                            │
│       │                                                                     │
│       ├─► TID's settlement_account_id                                      │
│       ├─► Branch's settlement_account_id (if branch exists)                │
│       └─► Merchant default account (is_default=true)                       │
│                  │                                                          │
│                  ▼                                                          │
│   ┌─────────────────────────────┐                                          │
│   │ merchant_settlement_accounts │ ← Destination lookup                    │
│   │ account_number, bank_code    │                                          │
│   └─────────────────────────────┘                                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 9. Files Investigated

### TID Import Flow
- `/Users/mathew/tms-config/src/main/java/com/config/service/ImportService.java` — Main import logic
- `/Users/mathew/tms-config/src/main/java/com/config/rest/TerminalResource.java` — REST endpoint
- `/Users/mathew/super-merchant/src/main/java/com/tms/report/modules/tid/controller/TidController.java` — Bank portal upload

### Settlement Entities (tms-config)
- `/Users/mathew/tms-config/src/main/java/com/config/entity/TidEntity.java`
- `/Users/mathew/tms-config/src/main/java/com/config/entity/MerchantSettlementTypeEntity.java`
- `/Users/mathew/tms-config/src/main/java/com/config/entity/MerchantSettlementAccountEntity.java`
- `/Users/mathew/tms-config/src/main/java/com/config/entity/MerchantBranchEntity.java`

### Settlement Resolution
- `/Users/mathew/tms-config/src/main/java/com/config/service/SettlementDestinationResolver.java`
- `/Users/mathew/tms-config/src/main/java/com/config/grpc/ConfigServiceImpl.java` (lines 4370-4412)

### Settlement Scheduling
- `/Users/mathew/tms-settlement/src/main/java/com/settlement/scheduler/MerchantHourlySettlementScheduler.java`
- `/Users/mathew/tms-settlement/src/main/java/com/settlement/service/MerchantSweepService.java`
- `/Users/mathew/tms-settlement/src/main/java/com/settlement/client/ConfigClient.java`

### Super-Merchant Settlement Config (Display Only)
- `/Users/mathew/super-merchant/src/main/java/com/tms/report/modules/settlement/model/SettlementConfig.java`
- `/Users/mathew/super-merchant/src/main/java/com/tms/report/modules/settlement/service/SettlementConfigService.java`

---

## 10. Summary of Gaps

| Gap | Severity | Impact | Fix Location |
|-----|----------|--------|--------------|
| `merchant_settlement_types` not populated during TID import | **CRITICAL** | Merchants never appear in sweep schedule | `ImportService.importTids()` |
| `merchant_branches` not created during TID import | Medium | No branch-level settlement routing | Consider adding branch columns to Excel template |
| No UI to directly set merchant settlement type | Medium | Must be set via Excel or direct DB | Add to super-merchant merchant detail page |
| `settlement_configs` (super-merchant) is display-only | Low | Confusing separation of config vs. operational tables | Document clearly; consider removing |

---

## Appendix: Tables That Are Direct-DB-Insert Only

Per the user's principle: "No table should be for direct database insert only."

| Table | Has API? | Status |
|-------|----------|--------|
| `merchant_settlement_types` | ✅ gRPC sync exists | **But never called during onboarding** — gap |
| `bank_settlement_configs` | ✅ gRPC via SM Banks UI | Working |
| `merchant_settlement_accounts` | ✅ Created during TID import | Working |
| `merchant_branches` | ⚠️ No creation during import | Gap — manual creation only |
