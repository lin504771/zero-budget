package com.zachary.zero_budget.ingestion;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Step 2 of ingestion: turns raw text rows into typed {@link ValidatedTransaction}s, and sets
 * aside the rows that cannot be trusted along with the reason for each.
 *
 * <p>The pattern to notice: <strong>a bad row is not an exception</strong>. In a real bank file
 * a few bad rows are normal and expected, and one of them must not stop the others from
 * importing. So each check <em>records a problem</em> instead of throwing, and a row with no
 * recorded problems is valid. (The helper parsers do throw, but we catch that here and
 * convert it to a problem message.)
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li><b>Date</b>: must parse (see {@link DateParsing}), be no later than today, and be
 *       on or after {@link #EARLIEST_DATE}. A typo like 2062 would otherwise parse fine.</li>
 *   <li><b>Amount</b>: from one of two shapes. Either an {@code amount} cell, used as given, or
 *       {@code debit}/{@code credit} cells where <em>debit</em> (money out) becomes negative and
 *       <em>credit</em> (money in) becomes positive. Exactly one source must be filled in.
 *       It must not be zero and must fit the database column.</li>
 *   <li><b>Description</b>: must not be blank. (Later steps build the dedup key from it.)</li>
 *   <li><b>Category</b>: optional; trimmed, and blank becomes {@code null}.</li>
 * </ul>
 *
 * <p>Deliberately <em>not</em> here: duplicate detection. Only the database knows what has
 * already been imported, so that is the repository's job ({@code ON CONFLICT DO NOTHING}).
 */
@Component
public class TransactionValidator {

    /** Oldest date we believe. Anything earlier is almost certainly a typo or a bad year. */
    static final LocalDate EARLIEST_DATE = LocalDate.of(1990, 1, 1);

    /** Largest absolute amount the database column allows: NUMERIC(10, 2) holds up to 99,999,999.99. */
    static final BigDecimal MAX_ABSOLUTE_AMOUNT = new BigDecimal("99999999.99");

    private final Clock clock;

    /**
     * Spring calls this constructor and passes in the {@link Clock} bean from
     * {@code ClockConfig}. Receiving dependencies through the constructor, rather than creating
     * them inside the class, is what lets tests substitute their own (here, a frozen clock).
     */
    public TransactionValidator(Clock clock) {
        this.clock = clock;
    }

    public ValidationResult validate(List<RawTransactionRow> rows) {
        LocalDate today = LocalDate.now(clock);   // read the clock once, so every row is judged against the same "today"

        List<ValidatedTransaction> valid = new ArrayList<>();
        List<Rejection> rejections = new ArrayList<>();

        for (RawTransactionRow row : rows) {
            // Each check below may add to this list. We run ALL of them, so a row with two
            // problems reports both, rather than making the user fix them one at a time.
            List<String> problems = new ArrayList<>();

            LocalDate postedDate = checkDate(row.date(), today, problems);
            BigDecimal amount = checkAmount(row, problems);
            String description = checkDescription(row.description(), problems);

            if (problems.isEmpty()) {
                valid.add(new ValidatedTransaction(
                        row.rowNumber(), postedDate, amount, description, cleanCategory(row.category())));
            } else {
                rejections.add(new Rejection(row.rowNumber(), String.join("; ", problems)));
            }
        }
        return new ValidationResult(valid, rejections);
    }

    // ---- date ----

    /** Returns the date, or null after recording why it is not acceptable. */
    private static LocalDate checkDate(String text, LocalDate today, List<String> problems) {
        LocalDate date;
        try {
            date = DateParsing.parseDate(text);
        } catch (DateTimeParseException e) {
            problems.add(e.getMessage());
            return null;
        }
        if (date.isAfter(today)) {
            problems.add("Date %s is in the future".formatted(date));
            return null;
        }
        if (date.isBefore(EARLIEST_DATE)) {
            problems.add("Date %s is before %s".formatted(date, EARLIEST_DATE));
            return null;
        }
        return date;
    }

    // ---- amount ----

    /** Returns the signed amount (negative = money out), or null after recording why not. */
    private static BigDecimal checkAmount(RawTransactionRow row, List<String> problems) {
        boolean hasAmount = hasText(row.amount());
        boolean hasDebit = hasText(row.debit());
        boolean hasCredit = hasText(row.credit());

        BigDecimal signed;   // the amount, once we've worked out which cells to read
        if (hasAmount && (hasDebit || hasCredit)) {
            problems.add("Both an amount and a debit/credit are filled in; expected only one");
            return null;
        } else if (hasAmount) {
            signed = parseMoney("amount", row.amount(), problems);
        } else if (hasDebit && hasCredit) {
            problems.add("Both debit and credit are filled in; expected only one");
            return null;
        } else if (hasDebit) {
            BigDecimal debit = parseMoney("debit", row.debit(), problems);
            if (debit != null && debit.signum() < 0) {
                problems.add("Debit must not be negative (got %s)".formatted(debit.toPlainString()));
                return null;
            }
            signed = debit == null ? null : debit.negate();   // money out is negative
        } else if (hasCredit) {
            BigDecimal credit = parseMoney("credit", row.credit(), problems);
            if (credit != null && credit.signum() < 0) {
                problems.add("Credit must not be negative (got %s)".formatted(credit.toPlainString()));
                return null;
            }
            signed = credit;   // money in is positive
        } else {
            problems.add("No amount: amount, debit and credit are all empty");
            return null;
        }

        if (signed == null) {
            return null;   // parseMoney already recorded the reason
        }
        if (signed.signum() == 0) {
            problems.add("Amount is zero");
            return null;
        }
        if (signed.abs().compareTo(MAX_ABSOLUTE_AMOUNT) > 0) {
            problems.add("Amount %s is larger than the supported maximum of %s"
                    .formatted(signed.toPlainString(), MAX_ABSOLUTE_AMOUNT.toPlainString()));
            return null;
        }
        return signed;
    }

    /** Parses one money cell. On failure, records the reason (naming which cell) and returns null. */
    private static BigDecimal parseMoney(String cellName, String text, List<String> problems) {
        try {
            return AmountParsing.parseAmount(text);
        } catch (IllegalArgumentException e) {
            problems.add("Bad %s: %s".formatted(cellName, e.getMessage()));
            return null;
        }
    }

    // ---- description and category ----

    private static String checkDescription(String text, List<String> problems) {
        if (!hasText(text)) {
            problems.add("Description is missing");
            return null;
        }
        return text.strip();
    }

    private static String cleanCategory(String text) {
        return hasText(text) ? text.strip() : null;
    }

    /** True for a string with at least one non-whitespace character; false for null, "" and "   ". */
    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
