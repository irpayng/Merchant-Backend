# Implementation Plan: Merchant Settlement Account Maker-Checker Workflow

This plan implements a maker-checker workflow where merchants can request settlement account changes via Merchant-Backend, and their super-merchant (bank) admins approve via the existing super-merchant approval system.

## Architecture Summary

- **Merchant-Backend** (/Users/mathew/Merchant-Backend): Portal for merchants to manage their business (port 8120)
- **Super-merchant** (/Users/mathew/super-merchant): Portal for bank admins to manage merchants under their bank (port 8110)
- Both apps share the same PostgreSQL database with separate schemas (`merchant`, `supermerchant`, `public`)
- Merchant-Backend writes directly to `supermerchant.pending_approvals` using native SQL for cross-schema access
- Bank-merchant relationship: A merchant belongs to a bank if that bank issued them a TID (`tids.bank_code where tids.user_id = merchant_id`)

## Cross-Service Contract

Both services must agree on:
- **Table**: `supermerchant.pending_approvals`
- **action_type**: `'settlement_config'`
- **section**: `'account'` for settlement account changes
- **merchant_initiated**: `true` for merchant-originated requests (maker_id is NULL)
- **merchant_name/merchant_email**: Populated from the merchant's profile for display in approvals

---

## Implementation Plan

- [ ] 1. **Schema Changes (super-merchant)**: Modify PendingApproval entity to support merchant-initiated requests
      
      Add three new columns to support merchant-initiated requests where there is no admin maker:
      - `merchant_initiated` (boolean, default false)
      - `merchant_name` (varchar 255)
      - `merchant_email` (varchar 255)
      - Make `maker_id` nullable (currently `nullable = false`)
      
      Files:
      - `/Users/mathew/super-merchant/src/main/java/com/tms/report/modules/checker/model/PendingApproval.java`
      - `/Users/mathew/super-merchant/src/main/resources/db/admin-schema.sql`
      
      Verify: `cd /Users/mathew/super-merchant && mvn spotless:apply -B && mvn compile -B -DskipTests` — compiles successfully.

- [ ] 2. **Update SettlementConfigApprovalDto (super-merchant)**: Add merchant-initiated fields to DTO
      
      Add fields to expose merchant_initiated, merchant_name_from_request, merchant_email_from_request in API responses.
      
      Files:
      - `/Users/mathew/super-merchant/src/main/java/com/tms/report/modules/approval/dto/SettlementConfigApprovalDto.java`
      
      Verify: `cd /Users/mathew/super-merchant && mvn compile -B -DskipTests` — compiles.

- [ ] 3. **Update ApprovalService.toSettlementDto() (super-merchant)**: Handle merchant-initiated requests
      
      Modify the `toSettlementDto()` method to:
      - Check if `merchant_initiated == true`
      - If true, use `merchant_name`/`merchant_email` for makerName/makerEmail instead of `maker.getName()`/`maker.getEmail()`
      - Handle null `maker` gracefully (maker is null for merchant-initiated requests)
      - Populate the new DTO fields
      
      Files:
      - `/Users/mathew/super-merchant/src/main/java/com/tms/report/modules/checker/service/ApprovalService.java`
      
      Verify: `cd /Users/mathew/super-merchant && mvn spotless:apply -B && mvn compile -B -DskipTests` — compiles.

- [ ] 4. **Create Request DTO (Merchant-Backend)**: SettlementAccountRequestDto for the request body
      
      Create DTO with validation annotations for: bank_name, account_number, account_name, account_type.
      
      Files:
      - `/Users/mathew/Merchant-Backend/src/main/java/com/tms/report/modules/settlement/dto/SettlementAccountRequestDto.java`
      
      Verify: `cd /Users/mathew/Merchant-Backend && mvn compile -B -DskipTests` — compiles.

- [ ] 5. **Create Response DTO (Merchant-Backend)**: ApprovalRequestResponse for listing approval requests
      
      Create DTO with fields: id, user_id, section, status, description, payload, reject_reason, timestamps.
      
      Files:
      - `/Users/mathew/Merchant-Backend/src/main/java/com/tms/report/modules/settlement/dto/ApprovalRequestResponse.java`
      
      Verify: `cd /Users/mathew/Merchant-Backend && mvn compile -B -DskipTests` — compiles.

- [ ] 6. **Create SettlementApprovalService (Merchant-Backend)**: Service for approval request operations
      
      Implement:
      - `submitAccountChangeRequest()`: Resolves bank_code from tids, checks for existing pending, inserts to pending_approvals
      - `getApprovalRequests()`: Lists merchant's approval requests
      - `hasPendingRequest()`: Checks if pending request exists for a section
      - Helper to get merchant name/email from users + profiles tables
      
      Key queries:
      - Bank code resolution: `SELECT DISTINCT bank_code FROM tids WHERE user_id = :merchantId AND bank_code IS NOT NULL LIMIT 1`
      - Insert approval: Native INSERT to `supermerchant.pending_approvals` with `merchant_initiated=true, maker_id=NULL`
      
      Files:
      - `/Users/mathew/Merchant-Backend/src/main/java/com/tms/report/modules/settlement/service/SettlementApprovalService.java`
      
      Verify: `cd /Users/mathew/Merchant-Backend && mvn spotless:apply -B && mvn compile -B -DskipTests` — compiles.

- [ ] 7. **Add endpoints to SettlementConfigController (Merchant-Backend)**: POST /request-account and GET /requests
      
      Add to existing controller:
      - `POST /settlement-config/request-account`: Accept SettlementAccountRequestDto, call service, return approval_id
      - `GET /settlement-config/requests`: Return list of merchant's approval requests
      
      Both endpoints use `@Transactional` and get merchantId from MerchantScope.
      
      Files:
      - `/Users/mathew/Merchant-Backend/src/main/java/com/tms/report/modules/settlement/controller/SettlementConfigController.java`
      
      Verify: `cd /Users/mathew/Merchant-Backend && mvn spotless:apply -B && mvn compile -B -DskipTests` — compiles.

---

## Assumptions

1. **Profile table structure**: Assumed `profiles` table has `user_id`, `first_name`, `last_name` columns based on the pattern in super-merchant's `getMerchantName()` query.

2. **TID bank_code**: Assumed every merchant has at least one TID with a `bank_code`. If a merchant has no TIDs, the request will fail with an appropriate error.

3. **Existing approve/reject flow**: The super-merchant's existing `approveSettlementConfig()`/`rejectSettlementConfig()` methods check `bank_code` scope (not `maker`), so they should work for merchant-initiated requests without modification.

4. **Required privilege**: Both endpoints use existing `manage_settlement` privilege check via `@PreAuthorize`.

5. **No notification**: This implementation does not add notifications to bank admins when a merchant submits a request. Consider adding this in a follow-up.

---

## Testing Notes

- Test that a merchant can submit a request and it appears in `supermerchant.pending_approvals`
- Test that the merchant's requests endpoint returns their requests
- Test that duplicate pending requests for the same section are rejected
- Test that super-merchant approval UI shows merchant_name/merchant_email for merchant-initiated requests
- Test that approve/reject flows work for merchant-initiated requests
