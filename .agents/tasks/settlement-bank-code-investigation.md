# Settlement Bank Code Investigation Report

## Summary

**Root Cause:** The settlement bank code mismatch is caused by inconsistent bank code formats across the system. The UIs (merchant-fe, super-merchant-ui) send **CBN/NIBSS bank codes** (3-digit format, e.g., `057` for Zenith Bank), but the tms-config database's `bank_codes` seed data uses **NIP bank codes** (6-digit format, e.g., `000015` for Zenith Bank). When the bank code `000007` is stored but the account actually belongs to Zenith Bank (NIP `000015`), name enquiry fails because the account doesn't exist at Fidelity Bank (`000007`).

**Impact:** Settlements fail with "name enquiry failed" because the stored bank code points to the wrong financial institution.

---

## Evidence

### 1. UI Bank Code Format (CBN/NIBSS 3-digit codes)

All frontend components use **3-digit CBN/NIBSS codes**:

**merchant-fe** (`/Users/mathew/merchant-fe/app/(user)/settlements/(files)/SettlementConfig.tsx`):
```typescript
const BANKS = [
  { code: '070', name: 'Fidelity Bank' },  // CBN code
  { code: '057', name: 'Zenith Bank' },    // CBN code
  // ...
];
```

**super-merchant-ui** (`/Users/mathew/super-merchant-ui/components/settlement/SettlementAccountsCard.tsx`):
```typescript
const BANKS = [
  { code: '070', name: 'Fidelity Bank' },  // CBN code
  { code: '057', name: 'Zenith Bank' },    // CBN code
  // ...
];
```

### 2. Backend Seed Data Format (NIP 6-digit codes)

The tms-config seed data uses **6-digit NIP codes**:

**tms-config** (`/Users/mathew/tms-config/src/main/resources/seed/bank_codes.json`):
```json
{ "code": "000007", "name": "Fidelity Bank" },    // NIP code
{ "code": "000015", "name": "Zenith Bank Plc" },  // NIP code
```

### 3. Bank Code Mapping Evidence

The **NubanValidator** in tms-transaction shows the clear mapping between CBN and NIP codes:

**tms-transaction** (`/Users/mathew/tms-transaction/src/main/java/com/transaction/util/NubanValidator.java`):
```java
DMB_TO_NIP.put("070", "000007"); // Fidelity Bank: CBN 070 → NIP 000007
DMB_TO_NIP.put("057", "000015"); // Zenith Bank: CBN 057 → NIP 000015
```

### 4. Backend Fallback Uses CBN Codes

The **ApprovalService** in super-merchant has a `resolveBankCodeFromName()` method that returns **CBN codes**:

**super-merchant** (`/Users/mathew/super-merchant/src/main/java/com/tms/report/modules/checker/service/ApprovalService.java`):
```java
private static String resolveBankCodeFromName(String bankName) {
    if (lower.contains("fidelity")) return "070";  // Returns CBN code
    if (lower.contains("zenith")) return "057";     // Returns CBN code
}
```

Similarly, the **SettlementConfigService** (`/Users/mathew/super-merchant/src/main/java/com/tms/report/modules/settlement/service/SettlementConfigService.java`):
```java
private static String resolveBankCodeFromName(String bankName) {
    if (lower.contains("fidelity")) return "070";  // Returns CBN code
    if (lower.contains("zenith")) return "057";     // Returns CBN code
}
```

### 5. Settlement Account Storage

The **MerchantSettlementAccountEntity** stores `bank_code` as-is without transformation:

**tms-config** (`/Users/mathew/tms-config/src/main/java/com/config/entity/MerchantSettlementAccountEntity.java`):
```java
/**
 * Settlement destination bank code (NIBSS institution code).
 */
@Column(name = "bank_code", nullable = false, length = 20)
public String bankCode;
```

### 6. Name Enquiry Flow

The settlement sweep calls name enquiry with the stored bank code directly. Name enquiry providers expect **NIP codes**:

**tms-provider-paystack** (`/Users/mathew/tms-provider-paystack/src/main/java/com/provider/paystack/grpc/PaystackBankCodeResolver.java`):
```java
// NIP code → CBN code → Paystack code translation
private static final Map<String, String> NIP_TO_CBN = Map.ofEntries(
    Map.entry("000007", "070"),  // NIP 000007 → CBN 070 (Fidelity)
    Map.entry("000015", "057"),  // NIP 000015 → CBN 057 (Zenith)
);
```

---

## Data Flow Analysis

### Current (Broken) Flow:
1. UI sends `bank_code: "057"` (CBN code for Zenith Bank)
2. Backend stores `bank_code: "057"` in `merchant_settlement_accounts`
3. Settlement sweep reads `bank_code: "057"` 
4. Name enquiry provider expects NIP code `"000015"` for Zenith
5. Provider translation fails or maps incorrectly
6. **Name enquiry fails**

### Specific Log Example:
```
Settlement destination resolved: userId=52 account=2161788834 bank=000007 source=merchant_default:1
```
Here `000007` is the NIP code for **Fidelity Bank**, but the account `2161788834` likely belongs to **Zenith Bank**.

---

## Root Causes

1. **Inconsistent Code Format**: The UI uses CBN codes, seed data uses NIP codes, and no consistent translation happens at the storage layer.

2. **No Validation**: The `createMerchantSettlementAccount` gRPC method stores whatever bank code is passed without validating against the `bank_codes` table or converting formats.

3. **Legacy Migration**: The log shows `source=merchant_default:1` which suggests this account came from a TID import or migration, potentially with mismatched bank code format.

4. **Two Sources of Truth**: The UI BANKS list and the seed data have different code formats for the same banks.

---

## Recommendations

### Option A: Standardize on NIP Codes (Recommended)

1. **Update all UIs** to use NIP codes in BANKS lists:
   - `merchant-fe/app/(user)/settlements/(files)/SettlementConfig.tsx`
   - `super-merchant-ui/components/settlement/SettlementAccountsCard.tsx`
   - `super-merchant-ui/components/settlement/MerchantSettlementAccounts.tsx`

2. **Update backend fallback resolvers** in:
   - `super-merchant/src/main/java/com/tms/report/modules/checker/service/ApprovalService.java`
   - `super-merchant/src/main/java/com/tms/report/modules/settlement/service/SettlementConfigService.java`

3. **Data migration**: Write a script to convert existing CBN codes to NIP codes in `merchant_settlement_accounts`:
   ```sql
   UPDATE merchant_settlement_accounts 
   SET bank_code = CASE bank_code
     WHEN '070' THEN '000007'  -- Fidelity
     WHEN '057' THEN '000015'  -- Zenith
     -- ... other mappings
   END
   WHERE bank_code ~ '^[0-9]{3}$';  -- Only 3-digit codes
   ```

### Option B: Add Translation Layer

1. **Add CBN-to-NIP translation** in the `createMerchantSettlementAccount` gRPC method before storage.

2. **Add NIP-to-CBN translation** in the UI when displaying bank names.

### Option C: Validate at Storage Time

1. Modify `ConfigServiceImpl.createMerchantSettlementAccount` to:
   - Validate `bank_code` exists in `bank_codes` table
   - Reject or auto-translate if format doesn't match

---

## Immediate Fix for Merchant 52

Query to identify the correct bank code for account `2161788834`:
```sql
-- Check what's stored
SELECT * FROM merchant_settlement_accounts 
WHERE user_id = 52 AND account_number = '2161788834';

-- If bank should be Zenith, update to NIP code:
UPDATE merchant_settlement_accounts 
SET bank_code = '000015' 
WHERE user_id = 52 AND account_number = '2161788834';
```

---

## Files Requiring Changes

| File | Change Required |
|------|----------------|
| `merchant-fe/app/(user)/settlements/(files)/SettlementConfig.tsx` | Update BANKS list to NIP codes |
| `super-merchant-ui/components/settlement/SettlementAccountsCard.tsx` | Update BANKS list to NIP codes |
| `super-merchant-ui/components/settlement/MerchantSettlementAccounts.tsx` | Update BANKS list to NIP codes |
| `super-merchant/src/.../ApprovalService.java` | Update `resolveBankCodeFromName()` to return NIP codes |
| `super-merchant/src/.../SettlementConfigService.java` | Update `resolveBankCodeFromName()` to return NIP codes |
| `tms-config/src/.../ConfigServiceImpl.java` | Add bank code validation in `createMerchantSettlementAccount()` |
| Database | Migration to convert existing CBN codes to NIP codes |

---

## Conclusion

The settlement failure is caused by bank codes being stored in CBN format (3-digit) while the name enquiry providers expect NIP format (6-digit). The fix requires standardizing on NIP codes across all UIs and backend services, plus a data migration to correct existing records.
