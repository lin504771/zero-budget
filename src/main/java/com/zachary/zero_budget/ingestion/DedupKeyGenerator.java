package com.zachary.zero_budget.ingestion;

import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Builds the fingerprint stored in {@code transaction.dedup_key}.
 *
 * <p>The database has a UNIQUE constraint on that column, so two transactions with the same
 * key can never both be stored. That makes re-importing the same file harmless (idempotent):
 * every row hashes to a key that already exists and {@code ON CONFLICT DO NOTHING} skips it.
 *
 * <p>This class is a <em>pure function</em>: the output depends only on the arguments, it
 * touches no database, clock or file, so it is trivial to unit test.
 *
 * <h2>The "two identical coffees" problem</h2>
 * Our bank CSV has no transaction ID. Two real purchases on the same day, for the same amount,
 * at the same merchant therefore look identical. If we hashed only date + amount + description
 * the second coffee would be thrown away as a "duplicate". To tell them apart the caller passes
 * an {@code occurrence} number: 1 for the first identical row in the file, 2 for the second, and
 * so on. Importing the same file again yields the same numbers, so it stays idempotent.
 *
 * <p>Trade-off: if two <em>overlapping</em> files split the same day differently (one file ends
 * after the first coffee, the other contains both), the numbering can disagree. That is a known
 * limitation of having no bank ID, and worth stating in the README.
 */
public final class DedupKeyGenerator {

    // The fields are joined with this character before hashing. It can't be confused with the
    // other fields (numbers, ISO dates), and the free-text description goes last so that a "|"
    // inside a description cannot shift into a neighbouring field.
    // It is a String, not a char, on purpose: in Java, `long + char` is arithmetic (the char's
    // numeric code gets added), whereas `long + String` is text concatenation.
    private static final String SEPARATOR = "|";

    // A utility class holds only static methods, so nobody should create an instance.
    private DedupKeyGenerator() {
    }

    /**
     * @param accountId  the account the transaction belongs to; the same purchase in two
     *                   different accounts is two different transactions
     * @param transaction a validated row
     * @param occurrence 1-based count of identical (date, amount, description) rows so far in
     *                   the file, including this one
     * @return 64 lowercase hex characters (a SHA-256 hash)
     */
    public static String generate(long accountId, ValidatedTransaction transaction, int occurrence) {
        if (occurrence < 1) {
            throw new IllegalArgumentException("occurrence must be >= 1 but was " + occurrence);
        }

        String canonical = accountId
                + SEPARATOR + transaction.postedDate()                                // ISO-8601, e.g. 2026-10-02
                + SEPARATOR + transaction.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                + SEPARATOR + occurrence
                + SEPARATOR + normalizeDescription(transaction.description());

        return sha256Hex(canonical);
    }

    /**
     * Makes cosmetic differences disappear: "  Blue   BOTTLE coffee " and "blue bottle coffee"
     * describe the same purchase. Also used to fill {@code transaction.normalized_description}.
     */
    public static String normalizeDescription(String description) {
        // Locale.ROOT makes lowercasing independent of the machine's language settings.
        // (In a Turkish locale, "I".toLowerCase() becomes a dotless "ı", which would change hashes.)
        return description.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Always specify the charset; the default one differs between machines.
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime is required to support SHA-256, so this can't really happen.
            // Wrapping it in an unchecked exception keeps callers free of impossible try/catch.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
