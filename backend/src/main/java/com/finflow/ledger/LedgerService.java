package com.finflow.ledger;

import com.finflow.common.persistence.AuditedEntity;
import com.finflow.ledger.LedgerEntryRepository.AccountTotal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

    private final LedgerEntryRepository entries;

    public LedgerService(LedgerEntryRepository entries) {
        this.entries = entries;
    }

    /**
     * Must join the caller's transaction so the ledger write commits or rolls back together with
     * the payment/refund state change that caused it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void post(Journal journal) {
        UUID journalId = UUID.randomUUID();
        Instant now = AuditedEntity.now();
        entries.saveAll(journal.lines().stream().map(line -> new LedgerEntry(journalId, journal, line, now)).toList());
    }

    @Transactional(readOnly = true)
    public Page<LedgerEntry> list(UUID merchantId, UUID paymentId, Pageable pageable) {
        return paymentId == null
                ? entries.findByMerchantId(merchantId, pageable)
                : entries.findByMerchantIdAndPaymentId(merchantId, paymentId, pageable);
    }

    public record AccountBalance(LedgerAccount account, String currency, long debitsMinor, long creditsMinor,
                                 long balanceMinor) {
    }

    public record CurrencyCheck(String currency, long totalDebitsMinor, long totalCreditsMinor, boolean balanced) {
    }

    public record Balances(List<AccountBalance> accounts, List<CurrencyCheck> checks) {
    }

    @Transactional(readOnly = true)
    public Balances balances(UUID merchantId) {
        record Key(String currency, LedgerAccount account) {
        }
        Map<Key, long[]> totals = new TreeMap<>(Comparator.comparing(Key::currency).thenComparing(Key::account));
        Map<String, long[]> perCurrency = new TreeMap<>();
        for (AccountTotal t : entries.totalsByAccount(merchantId)) {
            int idx = t.getDirection() == EntryDirection.DEBIT ? 0 : 1;
            totals.computeIfAbsent(new Key(t.getCurrency(), t.getAccount()), k -> new long[2])[idx] += t.getTotal();
            perCurrency.computeIfAbsent(t.getCurrency(), k -> new long[2])[idx] += t.getTotal();
        }
        List<AccountBalance> accounts = new ArrayList<>();
        totals.forEach((key, dc) -> accounts.add(new AccountBalance(key.account(), key.currency(), dc[0], dc[1],
                key.account().balance(dc[0], dc[1]))));
        List<CurrencyCheck> checks = new ArrayList<>();
        perCurrency.forEach((currency, dc) -> checks.add(new CurrencyCheck(currency, dc[0], dc[1], dc[0] == dc[1])));
        return new Balances(accounts, checks);
    }
}
