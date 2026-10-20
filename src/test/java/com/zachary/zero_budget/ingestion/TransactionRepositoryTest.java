package com.zachary.zero_budget.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zachary.zero_budget.PostgresContainerConfig;

/**
 * Integration test: it talks to a real Postgres that Testcontainers starts in Docker
 * (see {@link PostgresContainerConfig}); Docker must be running, but no manual setup is needed.
 *
 * <ul>
 *   <li>{@code @JdbcTest} starts only the database layer (DataSource, JdbcTemplate, Flyway), not
 *       the web layer, so it is fast. It also wraps every test in a transaction that is rolled back
 *       at the end, so tests leave no rows behind and can't affect each other.</li>
 *   <li>{@code replace = NONE} tells Spring not to swap in an in-memory database. We want real
 *       Postgres, because the thing under test is Postgres-specific SQL (ON CONFLICT).</li>
 *   <li>{@code @Import} is needed because {@code @JdbcTest} does not component-scan
 *       {@code @Repository} classes on its own.</li>
 * </ul>
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TransactionRepository.class, PostgresContainerConfig.class})
class TransactionRepositoryTest {

    @Autowired
    private TransactionRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private long accountId;
    private long importFileId;

    @BeforeEach
    void createParentRows() {
        // transaction has foreign keys to account and import_file, so those rows must exist first.
        accountId = jdbc.queryForObject(
                "INSERT INTO account (name, type) VALUES ('test-account', 'CHECKING') RETURNING id", Long.class);
        importFileId = jdbc.queryForObject(
                "INSERT INTO import_file (filename, file_hash, rows_total, rows_ok, rows_rejected, rows_duplicate) "
                        + "VALUES ('test.csv', 'hash-for-test', 0, 0, 0, 0) RETURNING id", Long.class);
    }

    /** Builds a row whose dedup key is derived the same way production code will derive it. */
    private NewTransaction row(String date, String amount, String description, int occurrence) {
        ValidatedTransaction tx = new ValidatedTransaction(
                1, LocalDate.parse(date), new BigDecimal(amount), description, null);
        String key = DedupKeyGenerator.generate(accountId, tx, occurrence);
        return new NewTransaction(accountId, importFileId, key, tx);
    }

    private int countRows() {
        return jdbc.queryForObject("SELECT count(*) FROM transaction", Integer.class);
    }

    @Test
    void insertsNewRowsAndReturnsHowManyWereInserted() {
        List<NewTransaction> rows = List.of(
                row("2026-10-01", "-4.50", "Coffee", 1),
                row("2026-10-02", "-12.00", "Lunch", 1),
                row("2026-10-03", "2500.00", "Paycheck", 1));

        assertThat(repository.insertAll(rows)).isEqualTo(3);
        assertThat(countRows()).isEqualTo(3);
    }

    @Test
    void importingTheSameRowsTwiceInsertsNothingTheSecondTime() {
        // The idempotency property from the guide.
        List<NewTransaction> rows = List.of(
                row("2026-10-01", "-4.50", "Coffee", 1),
                row("2026-10-02", "-12.00", "Lunch", 1));

        repository.insertAll(rows);

        assertThat(repository.insertAll(rows)).isZero();
        assertThat(countRows()).isEqualTo(2);
    }

    @Test
    void returnsOnlyTheNumberOfNewRowsWhenBatchMixesNewAndExisting() {
        NewTransaction existing = row("2026-10-01", "-4.50", "Coffee", 1);
        repository.insertAll(List.of(existing));

        int inserted = repository.insertAll(List.of(
                existing,
                row("2026-10-02", "-12.00", "Lunch", 1),
                row("2026-10-03", "-8.00", "Dinner", 1)));

        assertThat(inserted).isEqualTo(2);
        assertThat(countRows()).isEqualTo(3);
    }

    @Test
    void duplicateKeysInsideOneBatchAreStoredOnce() {
        NewTransaction coffee = row("2026-10-01", "-4.50", "Coffee", 1);

        assertThat(repository.insertAll(List.of(coffee, coffee))).isEqualTo(1);
        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    void twoIdenticalPurchasesWithDifferentOccurrenceAreBothStored() {
        // "Two identical coffees": same day/amount/description, told apart by occurrence number.
        List<NewTransaction> rows = List.of(
                row("2026-10-01", "-4.50", "Coffee", 1),
                row("2026-10-01", "-4.50", "Coffee", 2));

        assertThat(repository.insertAll(rows)).isEqualTo(2);
    }

    @Test
    void emptyListInsertsNothing() {
        assertThat(repository.insertAll(List.of())).isZero();
    }

    @Test
    void storesEveryFieldInTheRightColumn() {
        NewTransaction input = row("2026-10-01", "-4.50", "  Blue   BOTTLE Coffee ", 1);

        repository.insertAll(List.of(input));

        Map<String, Object> saved = jdbc.queryForMap("SELECT * FROM transaction");
        assertThat(saved.get("account_id")).isEqualTo(accountId);
        assertThat(saved.get("import_file_id")).isEqualTo(importFileId);
        assertThat(saved.get("posted_date")).isEqualTo(java.sql.Date.valueOf("2026-10-01"));
        assertThat((BigDecimal) saved.get("amount")).isEqualByComparingTo("-4.50");
        assertThat(saved.get("description")).isEqualTo(input.transaction().description());
        assertThat(saved.get("normalized_description")).isEqualTo("blue bottle coffee");
        assertThat(saved.get("dedup_key")).isEqualTo(input.dedupKey());
        assertThat(saved.get("category_id")).isNull();
    }

    @Test
    void unknownAccountFailsInsteadOfBeingSilentlySkipped() {
        // A foreign-key violation is a bug, not a duplicate, so it must not be swallowed by ON CONFLICT.
        NewTransaction orphan = new NewTransaction(
                -1L, importFileId, "some-key",
                new ValidatedTransaction(1, LocalDate.parse("2026-10-01"), new BigDecimal("-1.00"), "x", null));

        assertThatThrownBy(() -> repository.insertAll(List.of(orphan)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
