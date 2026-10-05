package com.zachary.zero_budget.ingestion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Step 1 of ingestion: turns a bank CSV into {@link RawTransactionRow}s.
 *
 * <p>Why a library instead of {@code line.split(",")}? Real bank exports contain things
 * like {@code "AMAZON, INC.",-12.50} (a comma inside quotes) and {@code "He said ""hi"""}
 * (an escaped quote), and a field can even contain a line break. Handling all of that
 * correctly is exactly what Apache Commons CSV is for.
 *
 * <p>{@code @Component} tells Spring to create one instance of this class at startup and
 * make it available to any other bean that asks for it in its constructor (this is
 * "dependency injection"). The class holds no mutable state, so sharing one instance is safe.
 */
@Component
public class CsvTransactionParser {

    /** U+FEFF, the invisible "byte-order mark" some programs put at the start of a file. */
    private static final int BYTE_ORDER_MARK = 0xFEFF;

    /** The columns we know how to read. The names are ours; the aliases below are the bank's. */
    private enum Column {
        DATE, DESCRIPTION, CATEGORY, AMOUNT, DEBIT, CREDIT
    }

    /**
     * Header names (lower-case) that we accept for each column, <strong>in priority order</strong>.
     * Banks disagree on naming, so supporting a new bank's export is usually a one-line change here.
     *
     * <p>Order matters because a file can match several aliases of one column. Your bank's file
     * has both "Transaction Date" and "Posted Date"; the schema stores the posted date (when the
     * money actually moved), so "posted date" is listed before "transaction date". A {@code Set}
     * has no order, which is why these are Lists.
     */
    private static final Map<Column, List<String>> ALIASES = new EnumMap<>(Map.of(
            Column.DATE, List.of("posted date", "posting date", "post date", "transaction date", "date"),
            Column.DESCRIPTION, List.of("description", "memo", "payee", "details"),
            Column.CATEGORY, List.of("category"),
            Column.AMOUNT, List.of("amount"),
            Column.DEBIT, List.of("debit", "withdrawal", "withdrawals"),
            Column.CREDIT, List.of("credit", "deposit", "deposits")));

    /**
     * CSVFormat is immutable configuration describing the dialect we read. It is built once
     * and reused, because it never changes.
     */
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()                      // no names given = "take the names from the first row"
            .setSkipHeaderRecord(true)        // ...and don't also return that row as data
            .setIgnoreEmptyLines(true)        // blank lines (often at the end of a file) are not rows
            .setTrim(true)                    // strip spaces around values, inside or outside quotes
            .setAllowMissingColumnNames(true) // a trailing comma in the header must not crash us
            .get();

    /** Reads a CSV file from disk. */
    public List<RawTransactionRow> parse(Path file) {
        // try-with-resources closes the reader for us even if an exception is thrown.
        // UTF-8 is assumed; some older bank exports are Windows-1252 and would need a
        // different charset here.
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader);
        } catch (IOException e) {
            throw new CsvParseException("Could not read file " + file, e);
        }
    }

    /**
     * Reads CSV from any {@link Reader}. Taking a Reader (rather than only a file path) is
     * what lets unit tests feed in a plain string with {@code new StringReader("...")}, no
     * temporary files needed. The caller owns the reader and closes it.
     */
    public List<RawTransactionRow> parse(Reader reader) {
        try (CSVParser parser = FORMAT.parse(skipByteOrderMark(reader))) {
            Map<Column, Integer> columns = mapColumns(parser.getHeaderNames());

            List<RawTransactionRow> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                rows.add(new RawTransactionRow(
                        record.getRecordNumber(),
                        field(record, columns.get(Column.DATE)),
                        field(record, columns.get(Column.DESCRIPTION)),
                        field(record, columns.get(Column.CATEGORY)),
                        field(record, columns.get(Column.AMOUNT)),
                        field(record, columns.get(Column.DEBIT)),
                        field(record, columns.get(Column.CREDIT))));
            }
            return rows;
        } catch (IOException | UncheckedIOException | IllegalStateException e) {
            // Broken quoting (e.g. an opening quote that never closes) makes it impossible to
            // tell where rows end, so there is nothing sensible to salvage: fail the whole file.
            throw new CsvParseException("Malformed CSV: " + e.getMessage(), e);
        }
    }

    /**
     * Excel's "CSV UTF-8" export starts the file with an invisible byte-order mark (U+FEFF).
     * If left in, the first header becomes "(BOM)Date" and no longer matches "date" (and a
     * quoted first header would not even be recognised as quoted). So we peek at the first
     * character and skip it only if it is a BOM.
     */
    private static BufferedReader skipByteOrderMark(Reader reader) throws IOException {
        BufferedReader buffered = new BufferedReader(reader);
        buffered.mark(1);                         // remember this position...
        if (buffered.read() != BYTE_ORDER_MARK) {
            buffered.reset();                     // ...and go back if it was a normal character
        }
        return buffered;
    }

    /**
     * Works out which column index holds each field. Only the date and description are
     * mandatory here; category is optional, and money may arrive as Amount or as Debit/Credit.
     * Returned map simply has no entry for columns the file doesn't have.
     */
    private static Map<Column, Integer> mapColumns(List<String> headerNames) {
        // header text -> position. putIfAbsent: if a header appears twice, the first one wins.
        Map<String, Integer> positionByHeader = new HashMap<>();
        for (int index = 0; index < headerNames.size(); index++) {
            positionByHeader.putIfAbsent(normalize(headerNames.get(index)), index);
        }

        Map<Column, Integer> columns = new EnumMap<>(Column.class);
        for (Column column : Column.values()) {
            // Walk the aliases in priority order and take the first one the file actually has.
            for (String alias : ALIASES.get(column)) {
                Integer position = positionByHeader.get(alias);
                if (position != null) {
                    columns.put(column, position);
                    break;
                }
            }
        }

        for (Column required : List.of(Column.DATE, Column.DESCRIPTION)) {
            if (!columns.containsKey(required)) {
                throw missingColumn(required.name(), required, headerNames);
            }
        }
        if (!columns.containsKey(Column.AMOUNT)
                && !columns.containsKey(Column.DEBIT)
                && !columns.containsKey(Column.CREDIT)) {
            throw new CsvParseException("No money column found: expected an Amount column or Debit/Credit columns. Found: "
                    + headerNames);
        }
        return columns;
    }

    private static CsvParseException missingColumn(String label, Column column, List<String> headerNames) {
        return new CsvParseException("Missing required %s column. Accepted headers: %s. Found: %s"
                .formatted(label, ALIASES.get(column), headerNames));
    }

    private static String normalize(String header) {
        // Locale.ROOT makes lower-casing behave the same on every machine (a Turkish-locale
        // computer would otherwise turn "I" into a dotless "ı").
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the cell text, or null if the file has no such column ({@code index == null})
     * or this row has fewer cells than the header (a "short" row).
     */
    private static String field(CSVRecord record, Integer index) {
        return index != null && index < record.size() ? record.get(index) : null;
    }
}
