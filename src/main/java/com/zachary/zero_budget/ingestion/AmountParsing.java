package com.zachary.zero_budget.ingestion;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the text of a money cell ("12.50", "-$1,234.56", "(12.50)") into a {@link BigDecimal}.
 *
 * <p>A pure helper, like {@link DateParsing}. It checks only the <em>shape</em> of the text.
 * Whether zero is acceptable, or whether the value fits the database column, are business
 * rules that live in {@link TransactionValidator}.
 *
 * <p>Why not just {@code new BigDecimal(text)}? That constructor would happily accept
 * "1E3" (scientific notation), and it rejects "$" and "," and "(12.50)". Instead we first
 * check the text against a strict pattern, then build the number from only the digits.
 *
 * <p>Why {@code BigDecimal} and not {@code double}? Binary floating point cannot represent
 * 0.10 exactly, so sums of money drift by fractions of a cent. BigDecimal is exact.
 */
public final class AmountParsing {

    /**
     * The whole accepted shape, as a regular expression (a mini-language for describing text).
     * Reading it left to right:
     *
     * <pre>
     *  ([+-]?)                          group 1: optional sign
     *  (\$?)                            group 2: optional dollar sign
     *  (\d{1,3}(?:,\d{3})+|\d+)         group 3: the whole-number part, either
     *                                     "1,234,567"  one to three digits, then groups of exactly
     *                                                  three digits each preceded by a comma, or
     *                                     "1234567"    plain digits with no commas
     *  (?:\.(\d{1,2}))?                 group 4: optional ".5" or ".50", never three decimals
     * </pre>
     *
     * <p>{@code (?: ... )} is a group we don't need to read back out later. {@code \d} matches
     * 0-9 only. The backslashes are doubled below because Java strings use {@code \} as an escape.
     */
    private static final Pattern AMOUNT = Pattern.compile(
            "([+-]?)(\\$?)(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?");

    private AmountParsing() {
    }

    /**
     * @return the amount with exactly two decimal places; negative for "-12.50" and "(12.50)"
     * @throws IllegalArgumentException if the text is null, blank or not a supported amount
     */
    public static BigDecimal parseAmount(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Amount is missing");
        }
        String trimmed = text.strip();

        // Accounting style: "(12.50)" means -12.50. Peel the parentheses off, remember them,
        // and let the same pattern check what is inside.
        boolean inParentheses = trimmed.length() >= 2 && trimmed.startsWith("(") && trimmed.endsWith(")");
        String body = inParentheses ? trimmed.substring(1, trimmed.length() - 1) : trimmed;

        Matcher match = AMOUNT.matcher(body);
        // matches() requires the pattern to cover the WHOLE text, not just a part of it.
        // A sign inside parentheses ("(-12.50)") would be a double negative, so reject it.
        if (!match.matches() || (inParentheses && !match.group(1).isEmpty())) {
            throw new IllegalArgumentException(
                    "'%s' is not a valid amount (examples: 12.50, -12.50, (12.50), $1,234.56)".formatted(trimmed));
        }

        String wholeNumber = match.group(3).replace(",", "");
        String decimals = match.group(4);   // null when there was no decimal part
        String plain = decimals == null ? wholeNumber : wholeNumber + "." + decimals;

        // setScale(2) pads "12" or "12.5" out to "12.00" / "12.50". The pattern already
        // guarantees at most two decimals, so no rounding ever happens.
        BigDecimal value = new BigDecimal(plain).setScale(2);

        boolean negative = inParentheses || "-".equals(match.group(1));
        return negative ? value.negate() : value;
    }
}
