package com.zachary.zero_budget.ingestion;

/**
 * Thrown when a file cannot be read as a transaction CSV at all: a required column
 * is missing, or the quoting is so broken that the parser cannot find where rows end.
 *
 * <p>This is different from a single bad row (for example an unreadable date), which
 * is a {@code rejection} reported by the validator while the rest of the file is still
 * processed. It is unchecked ({@code RuntimeException}) so callers are not forced to
 * wrap every call in try/catch; the ingestion service will catch it in one place.
 */
public class CsvParseException extends RuntimeException {

    public CsvParseException(String message) {
        super(message);
    }

    public CsvParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
