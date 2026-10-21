# Implementation Plan

This plan migrates the merchant dashboard settlement config from the legacy `supermerchant.settlement_configs` table to tms-config gRPC calls and reformats the frontend UI to match super-merchant's SettlementConfigTab layout.

## FEAT-001: Backend gRPC Migration (refactor)

- [ ] 1. Add `getMerchantSettlementType` method to GrpcClient.
      Copy implementation from super-merchant GrpcClient: call `configStub.getMerchantSettlementType` with `GetMerchantSettlementTypeRequest`, return Map with `found` (boolean) and `settlement_type` (String). Add import for `com.tms.report.grpc.config.GetMerchantSettlementTypeRequest`.
      Files: /Users/mathew/Merchant-Backend/src/main/java/com/tms/report/modules/grpc/service/GrpcClient.java
      Verify: `mvn compile -B -DskipTests` in /Users/mathew/Merchant-Backend — compiles without errors.

- [ ] 2. Update `get()` method in SettlementConfigController to use gRPC calls.
      (a) Get settlement type from `grpcClient.getMerchantSettlementType(merchantId)` — if found=true, use settlement_type; (b) Get default account from existing `grpcClient.listMerchantSettlementAccounts(merchantId)` — find is_default=true, use account_name/account_number/bank_code (resolve via bankName helper); (c) Keep MSC fields (msc_pos_percentage, instant_transfer_fee, monthly_terminal_fee) from old table via try-catch for backward compatibility; (d) Set hasConfig=true if settlement type found OR accounts exist; (e) Remove settlementTime and accountType field assignments.
      Files: /Users/mathew/Merchant-Backend/src/main/java/com/tms/report/modules/settlement/controller/SettlementConfigController.java
      Verify: `mvn spotless:apply -B && mvn spotless:check -B` in /Users/mathew/Merchant-Backend — passes.

## FEAT-002: Frontend UI Rework (feat)

- [ ] 3. Update InfoRow component to use flex-col layout.
      Change from side-by-side (`flex justify-between`) to stacked (`flex flex-col gap-1 py-3`). Label uses `text-xs text-muted-foreground`, value uses `text-sm font-medium text-foreground`.
      Files: /Users/mathew/merchant-fe/app/(user)/settlements/(files)/SettlementConfig.tsx
      Verify: `npm run lint` in /Users/mathew/merchant-fe — passes.

- [ ] 4. Restructure Settlement Schedule card.
      Use 2-column grid inside CardContent: wrap InfoRows in `grid grid-cols-2 gap-x-6 gap-y-1`. Show only Settlement Type and Settlement Days (remove Settlement Time).
      Files: /Users/mathew/merchant-fe/app/(user)/settlements/(files)/SettlementConfig.tsx
      Verify: Visual inspection — card shows 2 columns with Settlement Type and Settlement Days only.

- [ ] 5. Move and restructure MSC Breakdown card.
      Move to second position (replacing Settlement Account Details slot). Remove `lg:col-span-2`. Replace the 3-column stat boxes with InfoRow components in 2-column grid layout.
      Files: /Users/mathew/merchant-fe/app/(user)/settlements/(files)/SettlementConfig.tsx
      Verify: `npm run lint` in /Users/mathew/merchant-fe — passes.

- [ ] 6. Move Settlement Accounts card to full width.
      Rename card title to "Settlement Accounts". Add `lg:col-span-2` to span full width. Keep existing account list rendering and Add Account button functionality.
      Files: /Users/mathew/merchant-fe/app/(user)/settlements/(files)/SettlementConfig.tsx
      Verify: `npm run build` in /Users/mathew/merchant-fe — builds successfully.
