package com.zachary.zero_budget.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zachary.zero_budget.PostgresContainerConfig;
import com.zachary.zero_budget.config.ClockConfig;

/**
 * Integration test of the whole pipeline (real parser, validator, SQL, real Postgres).
 *
 * <p>Same setup as {@link TransactionRepositoryTest}: {@code @JdbcTest} + real database + automatic
 * rollback after each test. Because {@code @JdbcTest} only loads database-related beans, every
 * class the service needs is listed in {@code @Import}.
 *
 * <p>One consequence of the automatic rollback: the service's own {@code @Transactional} just joins
 * the test's transaction, so this test can't prove "an exception rolls back the writes".
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({IngestionService.class, CsvTransactionParser.class, TransactionValidator.class, ClockConfig.class,
        AccountRepository.class, ImportFileRepository.class, TransactionRepository.class,
        PostgresContainerConfig.class})
class IngestionServiceTest {

    private static final String ACCOUNT = "test-account";

    @Autowired
    private IngestionService service;

    @Autowired
    private JdbcTemplate jdbc;

    private static Path fixture(String name) throws IOException {
        return new ClassPathResource("fixtures/" + name).getFile().toPath();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    void importsAValidFile() throws IOException {
        ImportResult result = service.importFile(fixture("valid.csv"), ACCOUNT);

        assertThat(result.rowsTotal()).isEqualTo(3);
        assertThat(result.rowsInserted()).isEqualTo(3);
        assertThat(result.rowsDuplicate()).isZero();
        assertThat(result.rowsRejected()).isZero();
        assertThat(result.fileAlreadySeen()).isFalse();
        assertThat(count("transaction")).isEqualTo(3);
    }

    @Test
    void importingTheSameFileTwiceInsertsNothingTheSecondTime() throws IOException {
        // The "Done when" condition from the guide.
        service.importFile(fixture("valid.csv"), ACCOUNT);

        ImportResult second = service.importFile(fixture("valid.csv"), ACCOUNT);

        assertThat(second.rowsInserted()).isZero();
        assertThat(second.rowsDuplicate()).isEqualTo(3);
        assertThat(second.fileAlreadySeen()).isTrue();
        assertThat(count("transaction")).isEqualTo(3);
        assertThat(count("import_file")).isEqualTo(1);
    }

    @Test
    void identicalPurchasesInOneFileAreAllStored() throws IOException {
        // Two coffees on the same day (one with messy spacing/case) plus a lunch: nothing is lost.
        ImportResult result = service.importFile(fixture("identical-purchases.csv"), ACCOUNT);

        assertThat(result.rowsInserted()).isEqualTo(3);
        assertThat(result.rowsDuplicate()).isZero();
        assertThat(count("transaction")).isEqualTo(3);
    }

    @Test
    void identicalPurchasesStayIdempotentOnReimport() throws IOException {
        service.importFile(fixture("identical-purchases.csv"), ACCOUNT);

        ImportResult second = service.importFile(fixture("identical-purchases.csv"), ACCOUNT);

        assertThat(second.rowsInserted()).isZero();
        assertThat(second.rowsDuplicate()).isEqualTo(3);
        assertThat(count("transaction")).isEqualTo(3);
    }

    @Test
    void badRowsAreReportedAndGoodRowsStillImport() throws IOException {
        ImportResult result = service.importFile(fixture("with-bad-rows.csv"), ACCOUNT);

        assertThat(result.rowsTotal()).isEqualTo(5);
        assertThat(result.rowsInserted()).isEqualTo(2);
        assertThat(result.rowsRejected()).isEqualTo(3);
        assertThat(result.rejections()).extracting(Rejection::rowNumber).containsExactly(2L, 3L, 5L);
        assertThat(count("transaction")).isEqualTo(2);
    }

    @Test
    void everyRowIsCountedInExactlyOneBucket() throws IOException {
        service.importFile(fixture("with-bad-rows.csv"), ACCOUNT);

        ImportResult result = service.importFile(fixture("with-bad-rows.csv"), ACCOUNT);

        assertThat(result.rowsInserted() + result.rowsRejected() + result.rowsDuplicate())
                .isEqualTo(result.rowsTotal());
    }

    @Test
    void recordsTheOutcomeInImportFileTable() throws IOException {
        ImportResult result = service.importFile(fixture("with-bad-rows.csv"), ACCOUNT);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM import_file");
        assertThat(row.get("filename")).isEqualTo("with-bad-rows.csv");
        assertThat(row.get("file_hash")).isEqualTo(result.fileHash());
        assertThat(row.get("rows_total")).isEqualTo(5);
        assertThat(row.get("rows_ok")).isEqualTo(2);
        assertThat(row.get("rows_rejected")).isEqualTo(3);
        assertThat(row.get("rows_duplicate")).isEqualTo(0);
    }

    @Test
    void reusesTheAccountWhenImportingAgainWithTheSameName() throws IOException {
        service.importFile(fixture("valid.csv"), ACCOUNT);
        service.importFile(fixture("identical-purchases.csv"), ACCOUNT);

        assertThat(count("account")).isEqualTo(1);
    }

    @Test
    void sameFileInTwoDifferentAccountsStoresRowsInBoth() throws IOException {
        // Account is part of the dedup key, so the same purchase in two accounts is two transactions.
        service.importFile(fixture("identical-purchases.csv"), "account-a");

        ImportResult other = service.importFile(fixture("identical-purchases.csv"), "account-b");

        assertThat(other.rowsInserted()).isEqualTo(3);
        assertThat(count("transaction")).isEqualTo(6);
        assertThat(count("account")).isEqualTo(2);
    }

    @Test
    void fileWithMissingRequiredColumnFailsAndWritesNothing(@TempDir Path tempDir) throws IOException {
        Path broken = Files.writeString(tempDir.resolve("broken.csv"), "foo,bar\n1,2\n");

        assertThatThrownBy(() -> service.importFile(broken, ACCOUNT))
                .isInstanceOf(CsvParseException.class);
        assertThat(count("import_file")).isZero();
        assertThat(count("account")).isZero();
    }

    @Test
    void missingFileFailsClearly(@TempDir Path tempDir) {
        assertThatThrownBy(() -> service.importFile(tempDir.resolve("nope.csv"), ACCOUNT))
                .isInstanceOf(CsvParseException.class);
    }
}
