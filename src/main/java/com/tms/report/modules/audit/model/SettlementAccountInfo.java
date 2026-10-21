package com.tms.report.modules.audit.model;

/**
 * Value object representing settlement account information for audit logging.
 * Contains bank name, account name, and a masked account number for security.
 *
 * @param bankName
 *            the name of the bank (e.g., "GTBank", "Zenith Bank")
 * @param accountName
 *            the name on the account (e.g., "John Doe Enterprises")
 * @param maskedAccountNumber
 *            the account number with only last 4 digits visible (e.g.,
 *            "****1234")
 */
public record SettlementAccountInfo(String bankName, String accountName, String maskedAccountNumber) {

    /**
     * Creates a SettlementAccountInfo with the account number automatically masked.
     * The masking shows only the last 4 digits prefixed with "****".
     *
     * @param bankName
     *            the name of the bank
     * @param accountName
     *            the name on the account
     * @param accountNumber
     *            the full account number to be masked
     * @return a new SettlementAccountInfo with the masked account number
     */
    public static SettlementAccountInfo fromAccountNumber(String bankName, String accountName, String accountNumber) {
        return new SettlementAccountInfo(bankName, accountName, maskAccountNumber(accountNumber));
    }

    /**
     * Masks an account number showing only the last 4 digits. For account numbers
     * shorter than 4 digits, returns "****".
     *
     * @param accountNumber
     *            the account number to mask
     * @return masked account number in format "****{last_4}" or "****" if
     *         null/short
     */
    private static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            return "****";
        }
        return "****" + accountNumber.substring(accountNumber.length() - 4);
    }
}
