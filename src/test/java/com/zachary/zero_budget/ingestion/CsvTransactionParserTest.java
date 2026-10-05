package com.zachary.zero_budget.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A plain unit test: no Spring, no database, so it runs in milliseconds. We create the
 * parser with {@code new} because it has no dependencies to inject.
 */
class CsvTransactionParserTest {

    private final CsvTransactionParser parser = new CsvTransactionParser();

    private List<RawTransactionRow> parse(String csv) {
        return parser.parse(new StringReader(csv));
    }

    // ---- Unit tests: tiny inline CSVs with made-up data (safe to commit) ----

    @Test
    void readsTheDebitCreditLayoutWithCategory() {
        // Same column layout as the real bank export, with invented rows.
        var rows = parse("""
                Transaction Date,Posted Date,Card No.,Description,Category,Debit,Credit
                2026-01-02,2026-01-03,1234,CORNER CAFE,Dining,4.50,
                2026-01-04,2026-01-05,1234,PAYMENT THANK YOU,Payment/Credit,,250.00
                """);

        assertThat(rows).containsExactly(
                new RawTransactionRow(1, "2026-01-03", "CORNER CAFE", "Dining", null, "4.50", ""),
                new RawTransactionRow(2, "2026-01-05", "PAYMENT THANK YOU", "Payment/Credit", null, "", "250.00"));
    }

    @Test
    void prefersPostedDateOverTransactionDateRegardlessOfColumnOrder() {
        var rows = parse("""
                Posted Date,Transaction Date,Description,Debit
                2026-01-03,2026-01-02,Coffee,4.50
                """);

        assertThat(rows.get(0).date()).isEqualTo("2026-01-03");
    }

    @Test
    void readsASingleSignedAmountColumn() {
        var rows = parse("""
                Date,Description,Amount
                2026-10-01,Coffee,-4.50
                2026-10-02,Paycheck,1500.00
                """);

        assertThat(rows).containsExactly(
                new RawTransactionRow(1, "2026-10-01", "Coffee", null, "-4.50", null, null),
                new RawTransactionRow(2, "2026-10-02", "Paycheck", null, "1500.00", null, null));
    }

    @Test
    void keepsCommasAndEscapedQuotesInsideQuotedFields() {
        var rows = parse("""
                Date,Description,Amount
                2026-10-01,"AMAZON, INC. \"\"Prime\"\"",-12.50
                """);

        assertThat(rows.get(0).description()).isEqualTo("AMAZON, INC. \"Prime\"");
        assertThat(rows.get(0).amount()).isEqualTo("-12.50");
    }

    @Test
    void passesDatesAndAmountsThroughUntouched() {
        // Interpreting these is the validator's job; the parser must simply not choke on them.
        var rows = parse("""
                Date,Description,Amount
                03/04/2026,A,(12.50)
                "Oct 5, 2026",B,"$1,234.56"
                2026-10-06,C,-0.99
                """);

        assertThat(rows).extracting(RawTransactionRow::date)
                .containsExactly("03/04/2026", "Oct 5, 2026", "2026-10-06");
        assertThat(rows).extracting(RawTransactionRow::amount)
                .containsExactly("(12.50)", "$1,234.56", "-0.99");
    }

    @Test
    void matchesHeadersCaseInsensitivelyUsingAliasesInAnyOrder() {
        var rows = parse("""
                 Amount , MEMO ,Posted Date,Balance
                -3.00,Snack,2026-10-01,100.00
                """);

        assertThat(rows).containsExactly(
                new RawTransactionRow(1, "2026-10-01", "Snack", null, "-3.00", null, null));
    }

    @Test
    void ignoresByteOrderMarkAndBlankLines() {
        var rows = parse("﻿\"Date\",Description,Amount\n\n2026-10-01,Coffee,-4.50\n\n");

        assertThat(rows).containsExactly(
                new RawTransactionRow(1, "2026-10-01", "Coffee", null, "-4.50", null, null));
    }

    @Test
    void shortRowsGetNullFieldsInsteadOfFailing() {
        var rows = parse("""
                Date,Description,Amount
                2026-10-01,Coffee
                """);

        assertThat(rows).containsExactly(
                new RawTransactionRow(1, "2026-10-01", "Coffee", null, null, null, null));
    }

    @Test
    void failsWhenTheDateColumnIsMissing() {
        assertThatThrownBy(() -> parse("Description,Amount\nCoffee,-4.50\n"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("DATE");
    }

    @Test
    void failsWhenThereIsNoMoneyColumn() {
        assertThatThrownBy(() -> parse("Date,Description\n2026-10-01,Coffee\n"))
                .isInstanceOf(CsvParseException.class)
                .hasMessageContaining("money column");
    }

    @Test
    void failsOnAnUnclosedQuote() {
        assertThatThrownBy(() -> parse("Date,Description,Amount\n2026-10-01,\"Coffee,-4.50\n"))
                .isInstanceOf(CsvParseException.class);
    }

    // ---- Smoke test against the real statement (not committed: data/ is git-ignored) ----

    /**
     * Runs the parser on a real bank export if one is present. It checks the <em>shape</em> of
     * the result, never specific values, so no personal data ends up in the test code, and it is
     * skipped (not failed) on a machine that doesn't have the file. Surefire runs tests with the
     * project root as the working directory, hence the relative path.
     */
    @Test
    void parsesTheRealStatementInTheDataFolder() throws IOException {
        Path statement = Path.of("data", "2026-10-02_transaction_download.csv");
        assumeTrue(Files.exists(statement), "no real statement at " + statement + ", skipping");

        long dataLines;
        try (var lines = Files.lines(statement)) {
            dataLines = lines.filter(line -> !line.isBlank()).count() - 1; // minus the header
        }

        var rows = parser.parse(statement);

        assertThat(rows).as("one parsed row per non-blank data line").hasSize((int) dataLines);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.date()).as("date of row %d", row.rowNumber()).isNotBlank();
            assertThat(row.description()).as("description of row %d", row.rowNumber()).isNotBlank();
            assertThat(row.category()).as("category column present").isNotNull();
            // exactly one side of the money columns is filled in
            assertThat(row.debit().isBlank() ^ row.credit().isBlank())
                    .as("exactly one of debit/credit in row %d", row.rowNumber()).isTrue();
        });
    }
}
