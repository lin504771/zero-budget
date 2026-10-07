package com.zachary.zero_budget.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


public class DateParsingTest {
    @Test 
    public void testParseValidDate() {
        assertEquals(java.time.LocalDate.of(2023, 1, 1), DateParsing.parseDate("2023-01-01"));
        assertEquals(java.time.LocalDate.of(2023, 1, 1), DateParsing.parseDate("01/01/2023"));
        assertEquals(java.time.LocalDate.of(2023, 1, 1), DateParsing.parseDate("1/1/23"));
    }

    @Test 
    public void testParseInvalidDateString() {
        assertThatThrownBy(() -> DateParsing.parseDate("2023-18-23"))
                .isInstanceOf(DateTimeParseException.class);
        assertThatThrownBy(() -> DateParsing.parseDate("20285-05-23"))
                .isInstanceOf(DateTimeParseException.class);
        assertThatThrownBy(() -> DateParsing.parseDate("2023-02-29"))
                .isInstanceOf(DateTimeParseException.class);
        assertThatThrownBy(() -> DateParsing.parseDate("2023-02-30"))
                .isInstanceOf(DateTimeParseException.class);
    }

    @Test 
    public void testParseLeapDate() {
        assertEquals(java.time.LocalDate.of(2024, 02, 29), DateParsing.parseDate("2024-02-29"));
    }

    // =====================================================================================
    // Added cases. The policy they encode:
    //  - three formats: ISO yyyy-MM-dd; US M/d/yyyy with any zero padding; US M/d/yy (20yy)
    //  - slash dates are month-first; 13/04/2026 is rejected, never re-read as day-first
    //  - impossible calendar dates are rejected (no silent "Feb 30 becomes Feb 28")
    //  - only the SHAPE of the text is checked; "is it in the future?" is the validator's rule
    //  - anything unparseable, including null/blank, throws DateTimeParseException, so the
    //    validator has a single exception type to catch
    //
    // @ParameterizedTest runs one method once per input row and reports each row as its own
    // test, so a failure names the exact input. JUnit converts the table text to LocalDate.
    // =====================================================================================

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "1999-12-31, 1999-12-31",
            "2000-02-29, 2000-02-29",   // a century divisible by 400 IS a leap year
    })
    void acceptsMoreIsoDates(String input, LocalDate expected) {
        assertThat(DateParsing.parseDate(input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "1/1/2023,   2023-01-01",
            "01/7/2023,  2023-01-07",   // mixed zero padding is fine
            "1/07/2023,  2023-01-07",
            "12/31/2026, 2026-12-31",
    })
    void acceptsUsSlashDatesWithAnyPadding(String input, LocalDate expected) {
        assertThat(DateParsing.parseDate(input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "01/01/00, 2000-01-01",
            "12/31/99, 2099-12-31",   // two-digit years are always 20yy, never 19yy
    })
    void readsTwoDigitYearsAsThe2000s(String input, LocalDate expected) {
        assertThat(DateParsing.parseDate(input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "03/04/2026, 2026-03-04",   // March 4th, not April 3rd
            "04/03/2026, 2026-04-03",
    })
    void readsSlashDatesMonthFirst(String input, LocalDate expected) {
        assertThat(DateParsing.parseDate(input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
            "' 2023-01-01 ' | 2023-01-01",
            "'\t1/1/2023'   | 2023-01-01",
    })
    void ignoresSurroundingWhitespace(String input, LocalDate expected) {
        assertThat(DateParsing.parseDate(input)).isEqualTo(expected);
    }

    @Test
    void acceptsFutureDatesBecauseThatRuleBelongsToTheValidator() {
        assertThat(DateParsing.parseDate("2099-12-31")).isEqualTo(LocalDate.of(2099, 12, 31));
    }

    @ParameterizedTest(name = "\"{0}\" is not a real date")
    @ValueSource(strings = {
            "2023-04-31",   // April has 30 days
            "1900-02-29",   // centuries not divisible by 400 are not leap years
            "2023-00-10",   // month 0
            "2023-01-00",   // day 0
            "02/30/2023",
            "13/04/2026",   // day-first reading must not be used as a fallback
            "31/12/2026",
    })
    void rejectsMoreImpossibleCalendarDates(String input) {
        assertThatThrownBy(() -> DateParsing.parseDate(input))
                .isInstanceOf(DateTimeParseException.class);
    }

    @ParameterizedTest(name = "\"{0}\" is not a supported format")
    @ValueSource(strings = {
            "2023/01/01",           // ISO order with slashes
            "01-01-2023",           // US order with dashes
            "2023-1-1",             // ISO requires zero padding
            "20230101",
            "Oct 5, 2026",
            "2023-01-01 00:00:00",  // timestamps are not dates
            "2023-01-01T00:00:00",
            "abc",
    })
    void rejectsUnsupportedFormats(String input) {
        assertThatThrownBy(() -> DateParsing.parseDate(input))
                .isInstanceOf(DateTimeParseException.class);
    }

    @ParameterizedTest(name = "[{0}] is missing")
    @NullAndEmptySource                          // supplies null and "" as two test cases
    @ValueSource(strings = {" ", "   ", "\t"})   // plus a few blank strings
    void rejectsMissingValues(String input) {
        assertThatThrownBy(() -> DateParsing.parseDate(input))
                .isInstanceOf(DateTimeParseException.class);
    }

    @Test
    void rejectionMessageMentionsTheOffendingText() {
        // The validator will surface this text to the user, so it must name the bad value.
        assertThatThrownBy(() -> DateParsing.parseDate("Oct 5, 2026"))
                .hasMessageContaining("Oct 5, 2026");
    }
}
