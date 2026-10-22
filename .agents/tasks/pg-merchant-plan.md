# Implementation Plan: Payment Gateway Merchant Registration API

This plan implements a merchant registration/signup API for the IRPay Payment Gateway channel in Merchant-Backend. PG merchants are identified by user type `pg_merchant` (distinct from `merchant` used by PTSP/agent banking).

## Architecture Decision

The registration flow will:
1. Use existing `CreateUser` gRPC (already supports `type` field) to create a user with `type='pg_merchant'`
2. Add `UpdateMerchantBusinessName` RPC to Merchant-Backend's proto and GrpcClient to set `business_name` on the user after creation
3. Create a local `MerchantUser` record for dashboard access
4. Send a welcome email

**Why this approach?**
- `OnboardUserRequest` gRPC is designed for the mobile app onboarding flow with BVN/address validation, which PG merchants don't use
- `CreateUserCommandRequest` gRPC already supports the `type` field and is simpler for admin-style user creation
- `UpdateMerchantBusinessName` is available in tms-user and can be added to Merchant-Backend's proto (it's already used by tms-kyc)
- Phone normalization: will use simple inline normalization matching the existing `PhoneNumbers.normalize` pattern from tms-user

---

## Implementation Steps

- [ ] 1. **Add UpdateMerchantBusinessName to proto and GrpcClient**
      Add the RPC to Merchant-Backend's user_service.proto and implement the method in GrpcClient.
      Files:
      - `/Users/mathew/Merchant-Backend/src/main/proto/user_service.proto` (add RPC definition and message if missing)
      - `/Users/mathew/Merchant-Backend/src/main/java/com/tms/report/modules/grpc/service/GrpcClient.java` (add updateMerchantBusinessName method)
      Proto additions:
      ```protobuf
      // After existing RPCs in service UserService:
      rpc UpdateMerchantBusinessName (UpdateMerchantBusinessNameRequest) returns (UpdateUserResponse);
      
      // If not already present, add message:
      message UpdateMerchantBusinessNameRequest {
        int64 user_id = 1;
        string business_name = 2;
      }
      ```
      Verify: `mvn compile -f /Users/mathew/Merchant-Backend/pom.xml` — builds without errors.

- [ ] 2. **Create RegisterPgMerchantRequest DTO**
      Create the request DTO with Jakarta validation annotations matching the form fields.
      File: `/Users/mathew/Merchant-Backend/src/main/java/com/tms/report/modules/auth/dto/RegisterPgMerchantRequest.java`
      Fields:
      - `country` (String, optional, default 'NG')
      - `businessName` (String, @NotBlank)
      - `businessType` (String, @NotBlank, @Pattern for 'starter'|'registered')
      - `firstName` (String, @NotBlank)
      - `lastName` (String, @NotBlank)
      - `email` (String, @NotBlank, @Email)
      - `phoneNumber` (String, @NotBlank)
      - `password` (String, @NotBlank, @Size(min = 8))
      Verify: `mvn compile -f /Users/mathew/Merchant-Backend/pom.xml` — builds without errors.

- [ ] 3. **Create RegisterPgMerchantResponse DTO**
      Create the response DTO for the registration endpoint.
      File: `/Users/mathew/Merchant-Backend/src/main/java/com/tms/report/modules/auth/dto/RegisterPgMerchantResponse.java`
      Fields:
      - `userId` (Long)
      - `email` (String)
      - `phoneNumber` (String)
      - `businessName` (String)
      - `businessType` (String)
      - `userType` (String - will be 'pg_merchant')
      - `message` (String)
      Verify: `mvn compile -f /Users/mathew/Merchant-Backend/pom.xml` — builds without errors.

- [ ] 4. **Add registerPgMerchant method to AuthService**
      Implement the main registration logic following existing patterns.
      File: `/Users/mathew/Merchant-Backend/src/main/java/com/tms/report/modules/auth/service/AuthService.java`
      Method signature: `public RegisterPgMerchantResponse registerPgMerchant(RegisterPgMerchantRequest request)`
      Logic:
      (a) Normalize email to lowercase
      (b) Normalize phone to '234...' format (no + prefix, with country code):
          - Remove all non-digits
          - If starts with '234' and length 13, keep as-is
          - If starts with '0' and length 11, replace leading 0 with '234'
          - If length 10 and doesn't start with '0', prepend '234'
      (c) Validate email not registered via `grpcClient.findUserByEmail`
      (d) Validate phone not registered via `grpcClient.findUserByPhoneNumber`
      (e) Call `grpcClient.createUser` with type='pg_merchant' (use existing CreateUserCommandRequest pattern)
      (f) Call `grpcClient.updateMerchantBusinessName` to set business_name
      (g) Create MerchantUser record with ROLE_OWNER and STATUS_ACTIVE
      (h) Call `ensureDefaultRolesForMerchant` for role setup
      (i) Send welcome email via `grpcClient.sendEmail` with template 'pg-merchant-welcome'
      (j) Return RegisterPgMerchantResponse
      Verify: `mvn compile -f /Users/mathew/Merchant-Backend/pom.xml` — builds without errors.

- [ ] 5. **Add POST /auth/register/pg-merchant endpoint to AuthController**
      Add the public endpoint to the controller.
      File: `/Users/mathew/Merchant-Backend/src/main/java/com/tms/report/modules/auth/controller/AuthController.java`
      Endpoint: `POST /auth/register/pg-merchant`
      - Public (no JWT required - follows pattern of existing /login, /forgot-password)
      - Uses `@Valid @RequestBody RegisterPgMerchantRequest`
      - Returns `ApiResponse<RegisterPgMerchantResponse>`
      - Add TODO comment about rate limiting
      Verify: `mvn compile -f /Users/mathew/Merchant-Backend/pom.xml` — builds without errors.

- [ ] 6. **Add pg-merchant-welcome email template (if not exists)**
      Check if tms-notification has a welcome email template, or add one. If skipping template creation is acceptable, can use existing 'activate-account' style template with modified data.
      Optional File: `/Users/mathew/tms-notification/src/main/resources/templates/emails/pg-merchant-welcome.html`
      If template doesn't exist: modify AuthService to use existing template with appropriate data.
      Verify: `mvn compile -f /Users/mathew/Merchant-Backend/pom.xml` — builds without errors.

---

## Phone Number Normalization Logic

```java
private String normalizePhoneNumber(String phoneNumber) {
    if (phoneNumber == null || phoneNumber.isBlank()) return null;
    String digits = phoneNumber.replaceAll("[^0-9]", "");
    
    // +234XXXXXXXXXX or 234XXXXXXXXXX (13 digits total)
    if (digits.startsWith("234") && digits.length() == 13) {
        return digits; // already in correct format
    }
    // 0XXXXXXXXXX (11 digits, local format)
    if (digits.startsWith("0") && digits.length() == 11) {
        return "234" + digits.substring(1);
    }
    // XXXXXXXXXX (10 digits, missing leading zero)
    if (digits.length() == 10 && !digits.startsWith("0")) {
        return "234" + digits;
    }
    // Return as-is if unrecognized (validation should catch invalid formats)
    return digits;
}
```

---

## Key Files Reference

- **Existing patterns to follow:**
  - `AuthService.crossLogin` — user lookup/creation flow
  - `AuthService.findOrCreateMerchantUser` — MerchantUser creation
  - `AuthService.ensureDefaultRolesForMerchant` — role setup
  - `GrpcClient.createUser` — user creation via gRPC
  - `GrpcClient.sendEmail` — email sending
  - `LoginRequest.java` — DTO validation pattern

- **Proto field numbers for UpdateMerchantBusinessName (if adding):**
  - Check existing highest field number in user_service.proto messages
  - UpdateMerchantBusinessNameRequest: user_id=1, business_name=2 (matches tms-user proto)

---

## Verification

After each step: `mvn compile -f /Users/mathew/Merchant-Backend/pom.xml`

Final verification: Build succeeds with all new code in place.
