package com.finflow.ledger;

import com.finflow.common.web.PageResponse;
import com.finflow.common.web.Paging;
import com.finflow.security.MerchantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ledger")
@Tag(name = "Ledger")
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    public record LedgerEntryResponse(long id, UUID journalId, JournalType journalType, UUID paymentId, UUID refundId,
                                      UUID reversesJournalId, LedgerAccount account, EntryDirection direction, long amountMinor,
                                      String currency, String description, Instant createdAt) {

        static LedgerEntryResponse from(LedgerEntry e) {
            return new LedgerEntryResponse(e.getId(), e.getJournalId(), e.getJournalType(), e.getPaymentId(),
                    e.getRefundId(), e.getReversesJournalId(), e.getAccount(), e.getDirection(), e.getAmountMinor(), e.getCurrency(),
                    e.getDescription(), e.getCreatedAt());
        }
    }

    @GetMapping("/entries")
    @Operation(summary = "List ledger entries, newest first, optionally for one payment")
    public PageResponse<LedgerEntryResponse> entries(@AuthenticationPrincipal MerchantPrincipal merchant,
                                                     @RequestParam(required = false) UUID paymentId,
                                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                                     @RequestParam(defaultValue = Paging.DEFAULT_SIZE) @Min(1) @Max(Paging.MAX_SIZE) int size) {
        // Newest posting first; id breaks ties within a journal (both rows share a timestamp).
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return PageResponse.of(ledgerService.list(merchant.merchantId(), paymentId, pageable), LedgerEntryResponse::from);
    }

    @GetMapping("/balances")
    @Operation(summary = "Account balances and a per-currency debits = credits check")
    public LedgerService.Balances balances(@AuthenticationPrincipal MerchantPrincipal merchant) {
        return ledgerService.balances(merchant.merchantId());
    }
}
