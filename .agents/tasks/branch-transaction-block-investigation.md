# Investigation: Preventing Terminals Under Deactivated Branches from Transacting

## Summary

To prevent terminals under deactivated branches from transacting, a branch status check needs to be added to the **`prepareCardTransaction`** method in **`tms-config/ConfigServiceImpl.java`**. This is the central validation point for all card-based transactions. For wallet-funded transactions, a similar check should be added to extend the existing **`getTerminalLockStatus`** gRPC method and the **`verifyTerminalNotLocked`** method in **`tms-transaction/ProductService.java`**.

The fix follows the existing pattern for terminal locking and requires:
1. Modifying `prepareCardTransaction` in tms-config to reject terminals whose branch is inactive
2. Extending `GetTerminalLockStatus` to include branch status in the response
3. Updating `verifyTerminalNotLocked` in tms-transaction to check branch status

---

## Key Findings

### 1. Transaction Processing Entry Points

**Card-based transactions** (withdrawals, purchases, cash-advance, etc.) are validated through:
- **File:** `/Users/mathew/tms-config/src/main/java/com/config/grpc/ConfigServiceImpl.java`
- **Method:** `prepareCardTransaction()` (line ~2308)
- This method already checks for locked terminals and rejects with `TERMINAL_LOCKED: <reason>`

**Wallet-funded transactions** (airtime, data, transfers, etc.) are validated through:
- **File:** `/Users/mathew/tms-transaction/src/main/java/com/transaction/service/ProductService.java`
- **Method:** `verifyTerminalNotLocked()` (line ~623)
- Uses `getTerminalLockStatus` gRPC call to tms-config

### 2. Terminal-Branch Relationship

Terminals are linked to branches via the `branch_id` column:

**Terminal Entity:**
- **File:** `/Users/mathew/tms-config/src/main/java/com/config/entity/TerminalEntity.java`
- **Field:** `public Long branchId;` (line ~31)
- Index: `idx_terminals_branch_id`

**Branch Entity:**
- **File:** `/Users/mathew/tms-config/src/main/java/com/config/entity/MerchantBranchEntity.java`
- **Status field:** `public String status = "active";` (line ~86) — values: `active`, `suspended`, `closed`
- Query method exists: `findByIdAndUserId(Long id, Long userId)`

### 3. Existing Terminal Lock Check Pattern

The terminal lock is enforced in two places that we can follow as a pattern:

**For card transactions (in `prepareCardTransaction`):**
```java
// From ConfigServiceImpl.java line ~2326-2340
if (terminal.locked) {
    String reason = (terminal.lockMessage == null || terminal.lockMessage.isBlank())
            ? "Terminal locked"
            : terminal.lockMessage;
    responseObserver.onNext(PrepareCardTransactionResponse.newBuilder().setSuccess(false)
            .setError("TERMINAL_LOCKED: " + reason).build());
    responseObserver.onCompleted();
    return;
}
```

**For wallet transactions (in `ProductService.verifyTerminalNotLocked`):**
```java
// From ProductService.java line ~623-647
var status = configClient.getTerminalLockStatus(serial);
if (status != null && status.getFound() && status.getLocked()) {
    String reason = status.getLockMessage() == null || status.getLockMessage().isBlank()
            ? "Your terminal is locked. Contact support."
            : status.getLockMessage();
    throw new TransactionException("terminal", reason, 423);
}
```

### 4. Branch ID is Already Propagated

The `prepareCardTransaction` response already includes `branchId` from the terminal:
- **Proto:** `int64 branch_id = 20;` in `PrepareCardTransactionResponse`
- **Set at:** line ~2595: `.setBranchId(terminal.branchId != null ? terminal.branchId : 0L)`

### 5. Error Response Format

- **Card transactions:** Return `PrepareCardTransactionResponse` with `success=false` and `error="<PREFIX>: <message>"`
- **Wallet transactions:** Throw `TransactionException(field, message, statusCode)` where statusCode 423 is used for "locked" states

---

## Recommended Implementation

### Option A: Centralized Check in tms-config (Recommended)

Add the branch status check directly in `prepareCardTransaction`, right after the terminal lock check. This ensures all card transactions are blocked at the source.

**In `ConfigServiceImpl.prepareCardTransaction()` (~line 2341, after terminal lock check):**
```java
// 1b. Block terminals whose branch is inactive/suspended.
// A deactivated branch should prevent all its terminals from transacting.
if (terminal.branchId != null) {
    MerchantBranchEntity branch = MerchantBranchEntity.findById(terminal.branchId);
    if (branch != null && !"active".equals(branch.status)) {
        String reason = "Branch is " + branch.status + ". Contact support to reactivate.";
        responseObserver.onNext(PrepareCardTransactionResponse.newBuilder().setSuccess(false)
                .setError("BRANCH_INACTIVE: " + reason).build());
        responseObserver.onCompleted();
        return;
    }
}
```

**For wallet transactions, extend `GetTerminalLockStatusResponse`:**
1. Add new fields to the proto:
   ```protobuf
   message GetTerminalLockStatusResponse {
     bool found = 1;
     bool locked = 2;
     string lock_message = 3;
     int64 user_id = 4;
     // New fields for branch status
     bool branch_inactive = 5;
     string branch_status = 6;
   }
   ```

2. Update `getTerminalLockStatus` in ConfigServiceImpl to populate these fields

3. Update `verifyTerminalNotLocked` in ProductService to check branch status:
   ```java
   if (status != null && status.getFound()) {
       if (status.getLocked()) {
           // existing lock check
       }
       if (status.getBranchInactive()) {
           throw new TransactionException("terminal", 
               "Branch is " + status.getBranchStatus() + ". Contact support.", 423);
       }
   }
   ```

### Option B: Performance-Optimized with Caching

If performance is a concern, consider caching branch status:
- Add `branchStatus` column to terminal or use a lookup cache
- Update on branch status change via Kafka event
- This avoids a DB join on every transaction

---

## Files to Modify

### Core Changes (tms-config)
1. `/Users/mathew/tms-config/src/main/java/com/config/grpc/ConfigServiceImpl.java`
   - `prepareCardTransaction()` — add branch status check
   - `getTerminalLockStatus()` — add branch status to response

2. `/Users/mathew/tms-config/src/main/proto/config_service.proto`
   - Update `GetTerminalLockStatusResponse` message

### Dependent Changes (tms-transaction)
3. `/Users/mathew/tms-transaction/src/main/proto/config_service.proto`
   - Mirror proto changes

4. `/Users/mathew/tms-transaction/src/main/java/com/transaction/service/ProductService.java`
   - Update `verifyTerminalNotLocked()` to check branch status

### Optional (tms-processor - for symmetry)
5. `/Users/mathew/tms-processor/src/main/proto/config_service.proto`
   - Mirror proto changes if processor uses lock status

---

## Verification Strategy

1. **Unit tests in tms-config:**
   - Test `prepareCardTransaction` rejects terminal with inactive branch
   - Test `getTerminalLockStatus` returns branch status

2. **Integration test:**
   - Create a terminal under a branch
   - Deactivate the branch via PUT /branches/{id} with status='inactive'
   - Attempt a card transaction — should fail with `BRANCH_INACTIVE:`
   - Attempt a wallet transaction (airtime/transfer) — should fail with 423

3. **Manual verification:**
   - Via POS app: attempt transaction on terminal under inactive branch

---

## Risk Considerations

1. **Performance:** Adding a branch lookup adds one DB query per transaction. The `MerchantBranchEntity` has an index on `id` (primary key), so this is O(1).

2. **Backward compatibility:** Terminals without a `branch_id` (null) should NOT be blocked — only terminals explicitly assigned to an inactive branch.

3. **Fail-open vs fail-closed:** Follow the existing pattern — fail-open on lookup errors (degraded config-service shouldn't block all transactions), but fail-closed when branch is found and explicitly inactive.

---

## Related Code References

| Component | File | Purpose |
|-----------|------|---------|
| Terminal lock check (card) | `ConfigServiceImpl.java:2326-2340` | Pattern to follow |
| Terminal lock check (wallet) | `ProductService.java:623-647` | Pattern to follow |
| Branch entity | `MerchantBranchEntity.java` | Branch status field |
| Terminal entity | `TerminalEntity.java` | branchId field |
| Branch update endpoint | `BranchController.java:83` | PUT /branches/{id} |
| Lock status proto | `config_service.proto:795` | Response structure |
