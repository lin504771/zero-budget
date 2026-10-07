package com.zachary.zero_budget.ingestion;

/**
 * A row that failed validation, and why.
 *
 * @param rowNumber the row's position among the file's data rows (header not counted)
 * @param reason    human-readable; when a row has several problems they are joined with "; "
 */
public record Rejection(long rowNumber, String reason) {
}
