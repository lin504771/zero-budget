package com.zachary.zero_budget.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins down what {@link AmountParsing#parseAmount(String)} accepts and rejects.
 *
 * <p>The policy these tests encode:
 * <ul>
 *   <li>The result is a {@code BigDecimal}, always with exactly two decimal places.</li>
 *   <li>A leading {@code -} or parentheses mean negative; a leading {@code +} is allowed.
 *       A single {@code $} and US-style thousands commas are allowed.</li>
 *   <li>More than two decimal places is rejected, never silently rounded (it is money).</li>
 *   <li>Only the <em>shape</em> of the text is checked here. "Is zero allowed?" and "does it fit
 *       NUMERIC(10,2)?" are business rules for the validator, so {@code 0.00} parses fine.</li>
 *   <li>Anything unparseable, including null and blank, throws {@link IllegalArgumentException}
 *       ({@code NumberFormatException} is a subclass, so either is acceptable).</li>
 * </ul>
 *
 * <p>Comparing money in tests: {@code BigDecimal.equals} also compares <em>scale</em>, so
 * {@code 12.5} and {@code 12.50} are NOT equal. That is why the value tests use
 * {@code isEqualByComparingTo} (numeric value only) and one separate test pins the scale.
 *
 * <p>In {@code @CsvSource}, a value containing a comma must be wrapped in single quotes.
 */
class AmountParsingTest {

    // ---- accepted ----

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "12.50,   12.50",
            "-12.50, -12.50",
            "+12.50,  12.50",
            "0.00,     0.00",    // zero is the validator's business, not the parser's
            "-0.00,    0.00",
            "12,      12.00",    // no decimals
            "12.5,    12.50",    // one decimal
            "-0.01,   -0.01",
            "99999999.99, 99999999.99",
    })
    void acceptsPlainAndSignedNumbers(String input, BigDecimal expected) {
        assertThat(AmountParsing.parseAmount(input)).isEqualByComparingTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "(12.50),      -12.50",
            "($12.50),     -12.50",
            "'(1,234.56)', -1234.56",
            "(0.99),       -0.99",
    })
    void readsParenthesesAsNegativeAccountingStyle(String input, BigDecimal expected) {
        assertThat(AmountParsing.parseAmount(input)).isEqualByComparingTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "$12.50,   12.50",
            "-$12.50, -12.50",
            "$0.99,    0.99",
            "+$5.00,   5.00",
    })
    void acceptsADollarSign(String input, BigDecimal expected) {
        assertThat(AmountParsing.parseAmount(input)).isEqualByComparingTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "'1,234.56',      1234.56",
            "'$1,234.56',     1234.56",
            "'-1,234.56',    -1234.56",
            "'1,234,567.89',  1234567.89",
            "'999,999.00',    999999.00",
    })
    void acceptsUsThousandsSeparators(String input, BigDecimal expected) {
        assertThat(AmountParsing.parseAmount(input)).isEqualByComparingTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
            "' 12.50 '  | 12.50",
            "'\t-12.50 ' | -12.50",
    })
    void ignoresSurroundingWhitespace(String input, BigDecimal expected) {
        assertThat(AmountParsing.parseAmount(input)).isEqualByComparingTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" has two decimal places")
    @ValueSource(strings = {"12", "12.5", "12.50", "(12.5)", "-7", "$1234"})
    void alwaysReturnsScaleTwo(String input) {
        assertThat(AmountParsing.parseAmount(input).scale()).isEqualTo(2);
    }

    // ---- rejected ----

    @ParameterizedTest(name = "[{0}] is missing")
    @NullAndEmptySource                          // supplies null and "" as two test cases
    @ValueSource(strings = {" ", "   ", "\t"})
    void rejectsMissingValues(String input) {
        assertThatThrownBy(() -> AmountParsing.parseAmount(input))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "\"{0}\" is not a number")
    @ValueSource(strings = {
            "abc",
            "12.50.10",
            "1.2.3",
            "12 50",     // internal space
            "--12.50",
            "+-12.50",
            "$",
            "-",
            "()",
            "1e3",       // BigDecimal's own constructor would accept scientific notation
            "1E3",
            "0x10",
            "NaN",
            "Infinity",
    })
    void rejectsNonNumbers(String input) {
        assertThatThrownBy(() -> AmountParsing.parseAmount(input))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "\"{0}\" has bad parentheses or signs")
    @ValueSource(strings = {
            "(12.50",      // unbalanced
            "12.50)",
            "((12.50))",
            "(-12.50)",    // double negative is ambiguous
            "-(12.50)",
    })
    void rejectsBadParenthesesAndSigns(String input) {
        assertThatThrownBy(() -> AmountParsing.parseAmount(input))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "\"{0}\" has more than two decimals")
    @ValueSource(strings = {"12.505", "0.001", "12.500", "-1.234"})
    void rejectsMoreThanTwoDecimalPlaces(String input) {
        assertThatThrownBy(() -> AmountParsing.parseAmount(input))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "\"{0}\" has misplaced separators")
    @ValueSource(strings = {
            "12,34",        // looks like a European decimal comma; we do not guess
            "1,23.45",      // groups must be exactly three digits
            ",123.45",
            "1,2345.00",
            "1234,567.00",
            "1,,234.00",
    })
    void rejectsMisplacedThousandsSeparators(String input) {
        assertThatThrownBy(() -> AmountParsing.parseAmount(input))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "\"{0}\" has an unsupported currency marker")
    @ValueSource(strings = {"€12.50", "£12.50", "12.50 USD", "12.50$", "$$12.50"})
    void rejectsOtherOrMisplacedCurrencyMarkers(String input) {
        assertThatThrownBy(() -> AmountParsing.parseAmount(input))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectionMessageMentionsTheOffendingText() {
        // The validator will surface this text to the user, so it must name the bad value.
        assertThatThrownBy(() -> AmountParsing.parseAmount("12.505"))
                .hasMessageContaining("12.505");
    }
}
