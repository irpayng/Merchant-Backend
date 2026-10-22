# Payment Gateway Merchant Registration API

This implementation adds a public `POST /auth/register/pg-merchant` endpoint for PG merchant onboarding. The approach avoids the BVN-centric `OnboardUser` gRPC (designed for mobile app signup) and instead uses `CreateUserCommandRequest` with `type='pg_merchant'` plus a new `UpdateMerchantBusinessName` RPC to set the business name. The flow validates email/phone uniqueness before user creation, creates a local `MerchantUser` record for dashboard access, sets up default roles, and attempts to send a welcome email.

**Watch for:** The `pg-merchant-welcome` email template does not exist in tms-notification (confirmed). The code catches this failure gracefully (logs and continues), but registration will silently skip the welcome email until the template is added. This is a non-blocking gap but should be documented or addressed.

**Verdict**: APPROVED

## High-level view

The security configuration opens `/auth/register/pg-merchant` as a public endpoint, matching other auth endpoints like `/auth/login` and `/auth/forgot-password`. The endpoint accepts a request DTO with standard Jakarta validation (`@NotBlank`, `@Email`, `@Size(min=8)` for password, `@Pattern` for business type).

The registration flow implements the duplicate-check-first pattern: email and phone lookups via gRPC happen before any write operations, rejecting with 409 Conflict if either exists. Phone normalization handles the specified formats (`07089...`, `+23407089...`, `23407089...`) and outputs the `234...` format with no plus prefix.

The proto change adds `UpdateMerchantBusinessName` to the `UserService`, using field numbers 1 and 2 which are standard and non-colliding with existing messages. The new gRPC method in `GrpcClient` follows the same pattern as other user service calls.

The `MerchantUser` record is created with `ROLE_OWNER` and `STATUS_ACTIVE`, with `merchantId` set to the tms-user `users.id` as required. The rate-limiting TODO is present on the controller method.

<details>
<summary>Issues (1)</summary>

1. **Missing email template** (confirmed) — The code references template `pg-merchant-welcome` which doesn't exist in tms-notification. Registration succeeds but the welcome email silently fails. Either create the template in tms-notification or switch to an existing template like `merchant-onboarded`.

</details>

<details>
<summary>Details</summary>

## Phone normalization edge case

Unrecognized phone formats pass through as-is to the gRPC call. If tms-user doesn't validate phone format on its end, invalid numbers could be stored. The three specified formats are handled correctly; anything else relies on downstream validation.

## Welcome email template gap

The template `pg-merchant-welcome` does not exist in tms-notification. Existing templates include `merchant-onboarded`, `onboarding-welcome`, and `admin-welcome`. Registration completes regardless (failure is caught and logged), but no welcome email reaches new PG merchants until the template is created or the code switches to an existing template.

## File map

| File | Change |
|------|--------|
| `SecurityConfig.java` | Add `/auth/register/pg-merchant` to public endpoints |
| `AuthController.java` | Add `registerPgMerchant` endpoint with rate-limiting TODO |
| `RegisterPgMerchantRequest.java` | New DTO with Jakarta validation |
| `RegisterPgMerchantResponse.java` | New response DTO |
| `AuthService.java` | Add `registerPgMerchant`, `normalizePhoneNumber`, `extractUserIdFromResponse`, `sendPgMerchantWelcomeEmail` |
| `GrpcClient.java` | Add `updateMerchantBusinessName` method |
| `user_service.proto` | Add `UpdateMerchantBusinessName` RPC and message |

Build verified in commit message: `mvn spotless:check compile -B` SUCCESS.

</details>
