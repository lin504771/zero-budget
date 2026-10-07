package com.zachary.zero_budget.ingestion;

import java.util.List;

/**
 * Everything the validator learned about a file: the rows that are good, and the rows that
 * are not (with reasons). Every input row appears in exactly one of the two lists.
 *
 * <p>The block below the component list is a <em>compact constructor</em>: code that runs
 * whenever a record is created. {@code List.copyOf} makes an unmodifiable copy, so once a
 * result exists nobody can add to or remove from its lists by accident (and a later change
 * to the validator's own working lists can't leak into it).
 */
public record ValidationResult(List<ValidatedTransaction> valid, List<Rejection> rejections) {

    public ValidationResult {
        valid = List.copyOf(valid);
        rejections = List.copyOf(rejections);
    }
}
