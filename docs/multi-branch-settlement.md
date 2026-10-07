# Multi-Branch Merchant Settlement Architecture

This document describes the multi-branch settlement system that allows merchants to manage multiple settlement accounts and organize their operations across physical branches.

## Overview

The multi-branch architecture enables:
- **Multiple settlement accounts** per merchant — route transactions from specific terminals to specific bank accounts
- **Branch management** — organize terminals, TIDs, and users under physical locations
- **Flexible settlement routing** — TID → settlement account mapping with merchant-level default fallback

## Data Model

### Merchant Settlement Accounts

Each merchant can have multiple settlement accounts. One account is marked as default for fallback when a TID has no explicit mapping.

```
merchant_settlement_accounts
├── id (PK)
├── user_id (FK → users.id)     -- Merchant who owns this account
├── account_number              -- Settlement destination
├── bank_code                   -- NIBSS institution code
├── account_name                -- Account holder name
├── label                       -- Human-readable label (e.g., "Main Account")
├── is_default                  -- Fallback account for this merchant
├── status                      -- active | suspended | closed
├── created_at
└── updated_at
```

### Merchant Branches

Branches represent physical locations. TIDs and terminals can be assigned to branches for organizational grouping.

```
merchant_branches
├── id (PK)
├── user_id (FK → users.id)     -- Merchant who owns this branch
├── name                        -- Human-readable name (e.g., "Lagos Main")
├── code                        -- Short code, unique per merchant (e.g., "LG01")
├── address                     -- Physical address
├── state_code                  -- State location
├── lga_code                    -- LGA location
├── phone_number                -- Contact phone
├── email                       -- Contact email
├── status                      -- active | suspended | closed
├── is_primary                  -- Headquarters/main branch flag
├── created_at
└── updated_at
```

### Settlement Routing

TIDs can reference a specific settlement account via `settlement_account_id`. The resolution order is:

1. **TID's explicit account** — `tids.settlement_account_id` → `merchant_settlement_accounts`
2. **Merchant's default account** — `merchant_settlement_accounts` where `is_default = true`
3. **Legacy fallback** — existing `instant_settlements` / `terminal_settlement_destinations` tables

## API Endpoints

### Settlement Accounts

Base path: `/merchants/{userId}/settlement-accounts`

| Method | Path | Description | Permission |
|--------|------|-------------|------------|
| GET | `/` | List all settlement accounts | `manage_settlement` |
| GET | `/{accountId}` | Get single account | `manage_settlement` |
| POST | `/` | Create new account | `manage_settlement` |
| PUT | `/{accountId}` | Update account | `manage_settlement` |
| POST | `/{accountId}/set-default` | Set as default | `manage_settlement` |
| DELETE | `/{accountId}` | Delete account | `manage_settlement` |

#### Create Account Request
```json
{
  "account_number": "0123456789",
  "bank_code": "058",
  "account_name": "ACME CORP LTD",
  "label": "Main Account",
  "is_default": true
}
```

#### Update Account Request
```json
{
  "account_name": "ACME CORPORATION LIMITED",
  "label": "Primary Settlement Account",
  "status": "active"
}
```

### Branches

Base path: `/merchants/{userId}/branches`

| Method | Path | Description | Permission |
|--------|------|-------------|------------|
| GET | `/` | List all branches | `manage_merchant` |
| GET | `/{branchId}` | Get single branch | `manage_merchant` |
| POST | `/` | Create new branch | `manage_merchant` |
| PUT | `/{branchId}` | Update branch | `manage_merchant` |
| POST | `/{branchId}/set-primary` | Set as primary | `manage_merchant` |
| DELETE | `/{branchId}` | Delete branch | `manage_merchant` |

#### Create Branch Request
```json
{
  "name": "Lagos Island Branch",
  "code": "LG01",
  "address": "123 Marina Road, Lagos Island",
  "state_code": "LA",
  "lga_code": "LA001",
  "phone_number": "08012345678",
  "email": "lagos@merchant.com",
  "is_primary": false
}
```

#### Update Branch Request
```json
{
  "name": "Lagos Island Main Branch",
  "address": "125 Marina Road, Lagos Island",
  "status": "active"
}
```

## Service Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        super-merchant                           │
│  ┌─────────────────────────┐  ┌─────────────────────────────┐  │
│  │ SettlementAccountCtrl   │  │    MerchantBranchCtrl       │  │
│  └───────────┬─────────────┘  └──────────────┬──────────────┘  │
│              │                               │                  │
│              └───────────┬───────────────────┘                  │
│                          ▼                                      │
│                    ┌───────────┐                                │
│                    │ GrpcClient│                                │
│                    └─────┬─────┘                                │
└──────────────────────────┼──────────────────────────────────────┘
                           │ gRPC
┌──────────────────────────┼──────────────────────────────────────┐
│                          ▼                    tms-config        │
│                 ┌─────────────────┐                             │
│                 │ConfigServiceImpl│                             │
│                 └────────┬────────┘                             │
│                          │                                      │
│         ┌────────────────┼────────────────┐                     │
│         ▼                ▼                ▼                     │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐            │
│  │ Settlement   │ │   Branch     │ │     TID      │            │
│  │ AccountEntity│ │   Entity     │ │   Entity     │            │
│  └──────────────┘ └──────────────┘ └──────────────┘            │
└─────────────────────────────────────────────────────────────────┘
```

## gRPC Methods

The following RPCs are available in `ConfigService`:

### Settlement Accounts
- `ListMerchantSettlementAccounts` — List accounts for a merchant
- `GetMerchantSettlementAccount` — Get single account by ID
- `CreateMerchantSettlementAccount` — Create new account
- `UpdateMerchantSettlementAccount` — Update existing account
- `SetDefaultMerchantSettlementAccount` — Mark account as default
- `DeleteMerchantSettlementAccount` — Delete account (blocked if TIDs assigned)

### Branches
- `ListMerchantBranches` — List branches for a merchant
- `GetMerchantBranch` — Get single branch by ID
- `CreateMerchantBranch` — Create new branch
- `UpdateMerchantBranch` — Update existing branch
- `SetPrimaryMerchantBranch` — Mark branch as primary/HQ
- `DeleteMerchantBranch` — Delete branch (blocked if TIDs/terminals assigned)

### Settlement Resolution
- `ResolveSettlementDestination` — Resolve the settlement account for a TID or user

## Settlement Flow

```
Transaction Complete
        │
        ▼
┌───────────────────┐
│ Get TID's         │
│ settlement_account│
│ _id               │
└─────────┬─────────┘
          │
    ┌─────┴─────┐
    │ Has ID?   │
    └─────┬─────┘
          │
    Yes ──┼── No
          │     │
          ▼     ▼
   ┌──────────┐ ┌──────────────────┐
   │ Use that │ │ Get merchant's   │
   │ account  │ │ default account  │
   └──────────┘ └────────┬─────────┘
                         │
                   ┌─────┴─────┐
                   │ Has one?  │
                   └─────┬─────┘
                         │
                   Yes ──┼── No
                         │     │
                         ▼     ▼
                  ┌──────────┐ ┌──────────────┐
                  │ Use that │ │ Legacy       │
                  │ account  │ │ fallback     │
                  └──────────┘ └──────────────┘
```

## Database Migration

Tables are created automatically by Hibernate in tms-config (`hibernate.hbm2ddl=update`).

For the merchant portal database, run the DDL from:
```bash
docker exec -i merchant-db psql -U tms -d merchant < replication/create-tables.sql
```

## Constraints & Validation

### Settlement Accounts
- `account_number` + `bank_code` must be unique per merchant
- Only one account per merchant can be `is_default = true`
- Cannot delete an account that has TIDs assigned to it

### Branches
- `code` must be unique per merchant
- Only one branch per merchant can be `is_primary = true`
- Cannot delete a branch that has TIDs or terminals assigned to it

## Files Modified

| Service | File | Purpose |
|---------|------|---------|
| tms-config | `config_service.proto` | gRPC service definitions |
| tms-config | `ConfigServiceImpl.java` | gRPC method implementations |
| tms-config | `MerchantSettlementAccountEntity.java` | JPA entity |
| tms-config | `MerchantBranchEntity.java` | JPA entity |
| super-merchant | `config_service.proto` | Client-side proto copy |
| super-merchant | `GrpcClient.java` | gRPC client methods |
| super-merchant | `MerchantSettlementAccountController.java` | REST endpoints |
| super-merchant | `MerchantBranchController.java` | REST endpoints |
| Merchant-Backend | `create-tables.sql` | DDL for merchant DB |

## Future Considerations

1. **TID Assignment UI** — Allow assigning TIDs to specific settlement accounts from the admin portal
2. **Branch-level reporting** — Aggregate transaction reports by branch
3. **Branch user scoping** — Restrict operators to see only their branch's data
4. **Settlement account verification** — NIP name enquiry before saving account details
