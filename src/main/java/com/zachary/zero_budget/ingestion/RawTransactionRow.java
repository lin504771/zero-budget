package com.zachary.zero_budget.ingestion;

/**
 * One data row from a bank CSV, exactly as it appeared in the file.
 *
 * <p>Every field is deliberately a {@code String}. The parser's only job is to turn
 * the CSV <em>syntax</em> (quotes, commas, headers) into fields. It does not decide
 * whether "03/04/2026" is a valid date or whether "(12.50)" is a negative amount;
 * that interpretation belongs to the validator, which can then reject a row with a
 * reason instead of the parser having to guess or blow up.
 *
 * <p>Banks report the money in one of two shapes, so the row carries all three raw
 * possibilities and the validator works out the signed amount:
 * <ul>
 *   <li>a single signed {@code amount} column, or</li>
 *   <li>separate {@code debit} (money out) and {@code credit} (money in) columns, where
 *       the cell for the other direction is usually blank.</li>
 * </ul>
 *
 * <p>All text fields are trimmed. An empty cell is the empty string {@code ""}; {@code null}
 * means the column does not exist in the file or the row was too short to include it.
 *
 * <p>A {@code record} is Java's compact immutable data class: the compiler generates
 * the constructor, accessors ({@code date()}, {@code amount()}), {@code equals},
 * {@code hashCode} and {@code toString} for us.
 *
 * @param rowNumber   1-based position among the data rows (the header is not counted)
 * @param date        raw text of the posted date (falls back to the transaction date if the
 *                    file has no posted-date column); {@code null} if the row was too short
 * @param description raw text of the description column
 * @param category    the bank's own category label; {@code null} if the file has no such column
 * @param amount      raw text of a signed amount column; {@code null} if absent
 * @param debit       raw text of a debit column; {@code null} if absent
 * @param credit      raw text of a credit column; {@code null} if absent
 */
public record RawTransactionRow(
        long rowNumber,
        String date,
        String description,
        String category,
        String amount,
        String debit,
        String credit) {
}
