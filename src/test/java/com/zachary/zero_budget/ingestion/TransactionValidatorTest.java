package com.zachary.zero_budget.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Plain unit tests (no Spring, no database). "Today" is frozen at 2026-10-06 with
 * {@code Clock.fixed}, so the future-date tests give the same answer next year as today.
 */
class TransactionValidatorTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);

    private final TransactionValidator validator = new TransactionValidator(FIXED_CLOCK);

    // ---- helpers: keep each test focused on the one field it is about ----

    /** A row with a good date/description and whatever money cells the test supplies. */
    private static RawTransactionRow money(String amount, String debit, String credit) {
        return new RawTransactionRow(1, "2026-10-01", "Coffee", "Dining", amount, debit, credit);
    }

    private static RawTransactionRow dated(String date) {
        return new RawTransactionRow(1, date, "Coffee", "Dining", "-4.50", null, null);
    }

    /** Validates one row and returns the single result, asserting nothing about validity. */
    private ValidationResult validate(RawTransactionRow... rows) {
        return validator.validate(List.of(rows));
    }

    private static void assertRejectedWith(ValidationResult result, String reasonFragment) {
        assertThat(result.valid()).isEmpty();
        assertThat(result.rejections()).singleElement()
                .extracting(Rejection::reason).asString().contains(reasonFragment);
    }

    // ---- valid rows ----

    @Test
    void convertsAFullyValidRowToTypedValues() {
        var result = validate(new RawTransactionRow(7, " 10/01/2026 ", "  CORNER CAFE ", " Dining ", "-4.50", null, null));

        assertThat(result.rejections()).isEmpty();
        assertThat(result.valid()).containsExactly(new ValidatedTransaction(
                7, LocalDate.of(2026, 10, 1), new BigDecimal("-4.50"), "CORNER CAFE", "Dining"));
    }

    @Test
    void debitBecomesNegativeAndCreditPositive() {
        var result = validate(money(null, "4.50", ""), money(null, "", "250.00"));

        assertThat(result.rejections()).isEmpty();
        assertThat(result.valid()).extracting(ValidatedTransaction::amount)
                .usingElementComparator(BigDecimal::compareTo)   // compare by value, ignoring scale
                .containsExactly(new BigDecimal("-4.50"), new BigDecimal("250.00"));
    }

    @Test
    void aSignedAmountColumnIsUsedAsGiven() {
        var result = validate(money("(12.50)", null, null), money("$1,234.56", "", ""));

        assertThat(result.valid()).extracting(ValidatedTransaction::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("-12.50"), new BigDecimal("1234.56"));
    }

    @Test
    void aBlankCategoryBecomesNullAndCategoryColumnMayBeAbsent() {
        var blank = new RawTransactionRow(1, "2026-10-01", "Coffee", "   ", "-4.50", null, null);
        var absent = new RawTransactionRow(2, "2026-10-01", "Coffee", null, "-4.50", null, null);

        assertThat(validator.validate(List.of(blank, absent)).valid())
                .extracting(ValidatedTransaction::category).containsOnlyNulls();
    }

    // ---- dates ----

    @Test
    void acceptsTodayButNotTomorrow() {
        assertThat(validate(dated("2026-10-06")).valid()).hasSize(1);
        assertRejectedWith(validate(dated("2026-10-07")), "in the future");
    }

    @Test
    void acceptsTheEarliestDateButNotTheDayBefore() {
        assertThat(validate(dated("1990-01-01")).valid()).hasSize(1);
        assertRejectedWith(validate(dated("1989-12-31")), "before 1990-01-01");
    }

    @ParameterizedTest(name = "date [{0}] -> reason contains \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "Oct 5, 2026 | Unrecognized date 'Oct 5, 2026'",
            "2026-02-30  | Unrecognized date",
            "            | Date is missing",
    })
    void rejectsUnreadableDatesWithAReason(String date, String reasonFragment) {
        assertRejectedWith(validate(dated(date)), reasonFragment);
    }

    // ---- amounts ----

    @Test
    void rejectsWhenDebitAndCreditAreBothFilledIn() {
        assertRejectedWith(validate(money(null, "4.50", "4.50")), "Both debit and credit");
    }

    @Test
    void rejectsWhenAnAmountIsGivenAlongsideDebitOrCredit() {
        assertRejectedWith(validate(money("-4.50", "4.50", "")), "Both an amount and a debit/credit");
    }

    @Test
    void rejectsWhenNoMoneyCellIsFilledIn() {
        assertRejectedWith(validate(money("", " ", null)), "No amount");
    }

    @Test
    void rejectsNegativeDebitsAndCredits() {
        assertRejectedWith(validate(money(null, "-4.50", "")), "Debit must not be negative");
        assertRejectedWith(validate(money(null, "", "-4.50")), "Credit must not be negative");
    }

    @Test
    void rejectsUnreadableAmountsNamingTheCell() {
        assertRejectedWith(validate(money(null, "12.505", "")), "Bad debit");
        assertRejectedWith(validate(money("abc", null, null)), "Bad amount");
    }

    @Test
    void rejectsZeroAmounts() {
        assertRejectedWith(validate(money("0.00", null, null)), "Amount is zero");
        assertRejectedWith(validate(money(null, "0.00", "")), "Amount is zero");
    }

    @Test
    void acceptsTheLargestAmountTheColumnHoldsButNotOneCentMore() {
        assertThat(validate(money("99999999.99", null, null)).valid()).hasSize(1);
        assertThat(validate(money("-99999999.99", null, null)).valid()).hasSize(1);
        assertRejectedWith(validate(money("100000000.00", null, null)), "larger than the supported maximum");
        assertRejectedWith(validate(money(null, "100000000.00", "")), "larger than the supported maximum");
    }

    // ---- description ----

    @Test
    void rejectsMissingOrBlankDescriptions() {
        var blank = new RawTransactionRow(1, "2026-10-01", "   ", "Dining", "-4.50", null, null);
        var missing = new RawTransactionRow(2, "2026-10-01", null, "Dining", "-4.50", null, null);

        assertThat(validator.validate(List.of(blank, missing)).rejections())
                .extracting(Rejection::reason).containsExactly("Description is missing", "Description is missing");
    }

    // ---- whole-file behaviour ----

    @Test
    void reportsEveryProblemInARowTogether() {
        var row = new RawTransactionRow(4, "not a date", "", "Dining", "", "", "");

        var result = validate(row);

        assertThat(result.rejections()).singleElement().satisfies(rejection -> {
            assertThat(rejection.rowNumber()).isEqualTo(4);
            assertThat(rejection.reason())
                    .contains("Unrecognized date")
                    .contains("No amount")
                    .contains("Description is missing");
        });
    }

    @Test
    void oneBadRowDoesNotStopTheGoodOnesAndRowNumbersSurvive() {
        var good1 = new RawTransactionRow(1, "2026-10-01", "A", null, "-1.00", null, null);
        var bad = new RawTransactionRow(2, "garbage", "B", null, "-2.00", null, null);
        var good2 = new RawTransactionRow(3, "2026-10-03", "C", null, null, "3.00", "");

        var result = validator.validate(List.of(good1, bad, good2));

        assertThat(result.valid()).extracting(ValidatedTransaction::rowNumber).containsExactly(1L, 3L);
        assertThat(result.rejections()).extracting(Rejection::rowNumber).containsExactly(2L);
    }

    @Test
    void anEmptyFileGivesAnEmptyResult() {
        var result = validator.validate(List.of());

        assertThat(result.valid()).isEmpty();
        assertThat(result.rejections()).isEmpty();
    }

    // ---- smoke test: real parser + real validator + real statement (git-ignored data/) ----

    /**
     * Pushes the real bank export through the real parser and validator. Checks only overall
     * shape (nothing rejected, charges negative, the one payment positive), so no personal data
     * lives in the test. Skipped when the file isn't on this machine. The real clock is used on
     * purpose: a statement from the past is never "in the future".
     */
    @Test
    void validatesTheRealStatementEndToEnd() throws IOException {
        Path statement = Path.of("data", "2026-10-02_transaction_download.csv");
        assumeTrue(Files.exists(statement), "no real statement at " + statement + ", skipping");

        var rows = new CsvTransactionParser().parse(statement);
        var result = new TransactionValidator(Clock.systemDefaultZone()).validate(rows);

        assertThat(result.rejections()).as("rejections: %s", result.rejections()).isEmpty();
        assertThat(result.valid()).hasSameSizeAs(rows);
        assertThat(result.valid()).allSatisfy(t -> assertThat(t.amount().scale()).isEqualTo(2));
        assertThat(result.valid()).extracting(ValidatedTransaction::amount)
                .anyMatch(a -> a.signum() < 0)    // charges
                .anyMatch(a -> a.signum() > 0);   // the payment
    }
}
