package com.zachary.zero_budget.ingestion;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A transaction row that has passed validation, with real types instead of text.
 *
 * <p>Compare with {@link RawTransactionRow}, where every field is a {@code String}. This is
 * the "parse, don't validate" idea: once a row has become a {@code ValidatedTransaction},
 * later code (dedup key, repository) can trust it and never has to re-check "is this really a
 * date?". The type itself is the proof that the checks happened.
 *
 * @param rowNumber   position in the source file, kept so later steps can refer back to it
 * @param postedDate  the date the money actually moved
 * @param amount      signed, two decimal places: <strong>negative = money out, positive = money in</strong>
 * @param description trimmed text from the bank, never blank
 * @param category    the bank's own category label (trimmed), or {@code null} if it had none
 */
public record ValidatedTransaction(
        long rowNumber,
        LocalDate postedDate,
        BigDecimal amount,
        String description,
        String category) {
}
