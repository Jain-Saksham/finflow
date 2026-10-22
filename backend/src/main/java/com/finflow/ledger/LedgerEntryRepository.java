package com.finflow.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    Page<LedgerEntry> findByMerchantId(UUID merchantId, Pageable pageable);

    Page<LedgerEntry> findByMerchantIdAndPaymentId(UUID merchantId, UUID paymentId, Pageable pageable);

    interface AccountTotal {
        LedgerAccount getAccount();

        String getCurrency();

        EntryDirection getDirection();

        Long getTotal();
    }

    @Query("""
            select e.account as account, e.currency as currency, e.direction as direction, sum(e.amountMinor) as total
            from LedgerEntry e where e.merchantId = :merchantId
            group by e.account, e.currency, e.direction""")
    List<AccountTotal> totalsByAccount(UUID merchantId);
}
