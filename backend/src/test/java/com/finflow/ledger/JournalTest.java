package com.finflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JournalTest {

    private final UUID merchant = UUID.randomUUID();
    private final UUID payment = UUID.randomUUID();

    @Test
    void captureDebitsReceivableAndCreditsMerchant() {
        Journal j = Journal.capture(merchant, payment, 12_34, "USD");
        assertThat(j.lines()).containsExactly(
                new Journal.Line(LedgerAccount.PROVIDER_RECEIVABLE, EntryDirection.DEBIT, 12_34),
                new Journal.Line(LedgerAccount.MERCHANT_BALANCE, EntryDirection.CREDIT, 12_34));
    }

    @Test
    void refundReversesCapture() {
        Journal j = Journal.refund(merchant, payment, UUID.randomUUID(), 5_00, "USD");
        assertThat(j.lines()).containsExactly(
                new Journal.Line(LedgerAccount.MERCHANT_BALANCE, EntryDirection.DEBIT, 5_00),
                new Journal.Line(LedgerAccount.PROVIDER_RECEIVABLE, EntryDirection.CREDIT, 5_00));
    }

    @Test
    void unbalancedJournalCannotBeBuilt() {
        assertThatThrownBy(() -> new Journal(JournalType.CAPTURE, merchant, payment, null, "USD", "bad", List.of(
                new Journal.Line(LedgerAccount.PROVIDER_RECEIVABLE, EntryDirection.DEBIT, 100),
                new Journal.Line(LedgerAccount.MERCHANT_BALANCE, EntryDirection.CREDIT, 99))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unbalanced");
    }

    @Test
    void nonPositiveAmountsAreRejected() {
        assertThatThrownBy(() -> Journal.capture(merchant, payment, 0, "USD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refundJournalMustReferenceARefund() {
        assertThatThrownBy(() -> new Journal(JournalType.REFUND, merchant, payment, null, "USD", "x", List.of(
                new Journal.Line(LedgerAccount.MERCHANT_BALANCE, EntryDirection.DEBIT, 1),
                new Journal.Line(LedgerAccount.PROVIDER_RECEIVABLE, EntryDirection.CREDIT, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountBalancesUseNormalSide() {
        assertThat(LedgerAccount.PROVIDER_RECEIVABLE.balance(100, 30)).isEqualTo(70);
        assertThat(LedgerAccount.MERCHANT_BALANCE.balance(30, 100)).isEqualTo(70);
    }
}
