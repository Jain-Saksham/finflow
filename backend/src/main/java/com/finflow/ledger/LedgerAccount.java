package com.finflow.ledger;

/**
 * Chart of accounts, from FinFlow's point of view as the platform.
 * <ul>
 *   <li>{@code PROVIDER_RECEIVABLE} (asset): money the provider owes us for captured payments.</li>
 *   <li>{@code MERCHANT_BALANCE} (liability): money we owe the merchant.</li>
 * </ul>
 */
public enum LedgerAccount {
    PROVIDER_RECEIVABLE(EntryDirection.DEBIT),
    MERCHANT_BALANCE(EntryDirection.CREDIT);

    private final EntryDirection normalBalance;

    LedgerAccount(EntryDirection normalBalance) {
        this.normalBalance = normalBalance;
    }

    /** Balance expressed so that a positive number is the account's natural state. */
    public long balance(long debits, long credits) {
        return normalBalance == EntryDirection.DEBIT ? debits - credits : credits - debits;
    }
}
