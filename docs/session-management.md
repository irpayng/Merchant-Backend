# Session Management Architecture

This document describes the session management system added to Merchant-Backend to enable global session revocation and unified identity across tms-user (microservices) and the merchant dashboard.

## Overview

Merchant-Backend issues its own JWTs for dashboard authentication, separate from the mobile JWTs issued by tms-user. The session management layer tracks these dashboard sessions in a database table and provides:

1. **Session revocation** — Invalidate specific tokens or all sessions for a user
2. **Cross-system revocation** — When tms-user revokes a user (block, password change), dashboard sessions are also invalidated
3. **Audit trail** — Track when sessions were created, from which devices, and when revoked

## Architecture

```
┌─────────────┐      Kafka (CDC)       ┌──────────────────┐
│   tms-user  │  ────────────────────▶ │ Merchant-Backend │
│             │   outbox.event.session │                  │
│ (microsvcs) │                        │   (dashboard)    │
└─────────────┘                        └──────────────────┘
      │                                        │
      │ outbox_events                          │ merchant.sessions
      │ (Debezium CDC)                         │
      ▼                                        ▼
┌─────────────┐                        ┌──────────────────┐
│   Kafka     │                        │   PostgreSQL     │
└─────────────┘                        └──────────────────┘
```

## Database Schema

The `merchant.sessions` table:

```sql
CREATE TABLE merchant.sessions (
    id              BIGSERIAL PRIMARY KEY,
    merchant_user_id BIGINT NOT NULL,          -- FK to merchant_users
    tms_user_id     BIGINT NOT NULL,           -- users.id or operators.id
    user_type       VARCHAR(20) NOT NULL,      -- 'user' or 'operator'
    token_hash      VARCHAR(64) NOT NULL,      -- SHA-256 of JWT
    channel         VARCHAR(50) DEFAULT 'dashboard',
    device_info     VARCHAR(500),              -- User-Agent
    ip_address      VARCHAR(64),
    created_at      TIMESTAMP DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL,
    revoked_at      TIMESTAMP,
    revocation_reason VARCHAR(255)
);
```

## Event Flow: Cross-System Revocation

When tms-user needs to revoke dashboard sessions (e.g., user blocked, password changed):

1. **tms-user** writes to `outbox_events` table:
   ```json
   {
     "aggregateType": "session",
     "aggregateId": "12345",
     "type": "session.revoke_all",
     "payload": "{\"tms_user_id\":12345,\"user_type\":\"user\",\"reason\":\"block\",\"source\":\"admin\"}"
   }
   ```

2. **Debezium CDC** captures the insert and publishes to Kafka topic `outbox.event.session`

3. **Merchant-Backend** `SessionRevocationConsumer` receives the event and calls `sessionService.revokeSessionsForTmsUser()`

4. **Sessions marked revoked** — `revoked_at` is set, subsequent requests with those tokens fail validation

## Configuration

### Application Properties

```properties
# Session management - set to true after migration window
app.session.require-record=${SESSION_REQUIRE_RECORD:false}
```

| Property | Default | Description |
|----------|---------|-------------|
| `app.session.require-record` | `false` | When `false`, tokens without session records are allowed (backward compatibility). Set to `true` after one JWT expiry cycle to enforce session validation. |

## Migration Path

### Phase 1: Deploy with Session Tracking (Current)

1. Deploy Merchant-Backend with session management code
2. Flyway runs `V3__create_sessions_table.sql` automatically
3. `app.session.require-record=false` — old tokens still work
4. New logins create session records

### Phase 2: Wait for JWT Expiry Cycle

- Default JWT expiry is 24 hours (`app.jwt.expiration-ms=86400000`)
- Wait at least 24-48 hours after Phase 1 deploy
- All active sessions will now have session records

### Phase 3: Enforce Session Records

1. Set environment variable: `SESSION_REQUIRE_RECORD=true`
2. Redeploy Merchant-Backend
3. Tokens without session records are now rejected (401)

### Rollback

If issues arise after Phase 3:
- Set `SESSION_REQUIRE_RECORD=false`
- Redeploy
- Users with old tokens can log in again

## API Impact

### Login (`POST /auth/login`)

- Creates a session record in `merchant.sessions`
- Session creation is fail-safe; if it fails, login still succeeds (warning logged)

### Authentication Filter

- Validates JWT signature (existing behavior)
- **New**: Checks session validity via `sessionService.isSessionValid(token)`
- If session is revoked, returns 401 even if JWT is valid

### Logout (`POST /auth/logout`)

- Currently stateless (client discards token)
- **Future**: Call `sessionService.revokeSession(token)` for immediate invalidation

## Triggering Revocation from tms-user

### BlockService

When a user is blocked, `SessionRevocationPublisher.publishRevokeAllForUser()` is called:

```java
// In BlockService.block()
sessionRevocationPublisher.publishRevokeAllForUser(user.id, reason, "block");
```

### Password Change/Reset

Add to `AuthService.changePassword()` and `AuthService.resetPassword()`:

```java
// After updating password
sessionRevocationPublisher.publishRevokeAllForUser(user.id, "password_changed", "password_change");
```

### Manual/Admin Revocation

To revoke all sessions for a user programmatically:

```java
@Inject
SessionRevocationPublisher publisher;

// Revoke for merchant owner
publisher.publishRevokeAllForUser(userId, "admin_revoke", "admin");

// Revoke for operator
publisher.publishRevokeAllForOperator(operatorId, "admin_revoke", "admin");
```

## Future Enhancements

### Token Exchange (`/auth/exchange`)

Seamless mobile → dashboard SSO. The mobile app sends its JWT, Merchant-Backend verifies it with tms-user and issues a dashboard JWT.

**Status**: Not yet implemented. Requires:
- tms-user gRPC method to verify mobile JWT and return user profile
- `/auth/exchange` endpoint in Merchant-Backend

### Logout Endpoint

Update `POST /auth/logout` to actually revoke the session:

```java
@PostMapping("/logout")
public ApiResponse<Void> logout(@RequestHeader("Authorization") String authHeader) {
    String token = authHeader.replace("Bearer ", "");
    sessionService.revokeSession(token, "user_logout");
    return ApiResponse.success(null);
}
```

## Monitoring

### Logs to Watch

- `SessionService` — Session creation, revocation, cleanup
- `SessionRevocationConsumer` — Kafka event processing
- `JwtAuthenticationFilter` — Session validation failures

### Metrics to Consider

- Sessions created per minute
- Sessions revoked per minute
- Revocation event processing latency
- Tokens rejected due to revoked sessions
