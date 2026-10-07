package com.zachary.zero_budget.ingestion;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;

/**
 * Turns the text of a date cell into a {@link LocalDate}.
 *
 * <p>This is a "pure function" helper: no Spring, no state, no I/O. The same input always
 * gives the same output, which is what makes it so easy to test (see {@code DateParsingTest}).
 * That is why the class is {@code final} with a private constructor and only static methods:
 * there is nothing to instantiate.
 *
 * <p>It checks only the <em>shape</em> of the text. Whether a date is plausible for a
 * transaction (not in the future, not absurdly old) is a business rule that lives in
 * {@link TransactionValidator}.
 */
public final class DateParsing {

    /**
     * The accepted formats, tried in this order until one fits.
     *
     * <ul>
     *   <li>{@code uuuu-MM-dd}: ISO, zero padding required ("2026-01-05").</li>
     *   <li>{@code M/d/uuuu}: US, month first, any padding ("1/5/2026", "01/05/2026").</li>
     *   <li>{@code M/d/uu}: US with a two-digit year ("1/5/26"). {@code uu} means "2000 to 2099".</li>
     * </ul>
     *
     * <p>Pattern letters: {@code M} = month, {@code d} = day. A <em>single</em> letter ({@code M})
     * accepts one or two digits; a doubled letter ({@code MM}) requires exactly two. {@code uuuu}
     * is the year. We use {@code u} rather than the more familiar {@code y}: {@code y} means
     * "year of era", which STRICT mode refuses to resolve without an era (BC/AD) in the text.
     */
    private static final List<DateTimeFormatter> FORMATS = List.of(
            strict("uuuu-MM-dd"),
            strict("M/d/uuuu"),
            strict("M/d/uu"));

    private DateParsing() {
    }

    /**
     * @throws DateTimeParseException if the text is null, blank, or not a real date in a supported
     *                                format. One exception type for every failure, so the caller
     *                                needs only a single catch.
     */
    public static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            throw new DateTimeParseException("Date is missing", "", 0);
        }
        String trimmed = text.strip();

        for (DateTimeFormatter format : FORMATS) {
            try {
                return LocalDate.parse(trimmed, format);
            } catch (DateTimeParseException notThisFormat) {
                // Not an error yet: this format just didn't fit. Try the next one.
            }
        }
        throw new DateTimeParseException(
                "Unrecognized date '%s' (expected yyyy-MM-dd, M/d/yyyy or M/d/yy)".formatted(trimmed),
                trimmed, 0);
    }

    /**
     * Builds a formatter that rejects impossible dates.
     *
     * <p>The default resolver ({@code SMART}) quietly turns "2023-02-30" into 2023-02-28, so a
     * typo would become a different, plausible date. {@code STRICT} throws instead, which is what
     * we want when the data is money.
     */
    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT);
    }
}
