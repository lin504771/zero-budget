package com.zachary.zero_budget.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zachary.zero_budget.PostgresContainerConfig;

/**
 * End-to-end ingestion tests against a real Postgres (Step 3 of the guide).
 *
 * <p>How this differs from {@link IngestionServiceTest}:
 * <ul>
 *   <li>{@code @SpringBootTest} starts the <em>whole</em> application, not just the database
 *       layer, so it is the closest thing to production.</li>
 *   <li>There is no automatic rollback here. Data is really committed, as in production. That is
 *       what makes it possible to test commit behaviour and concurrent imports, which a
 *       rolled-back test transaction can't show. The price: tests must clean up after
 *       themselves, which {@link #cleanDatabase()} does.</li>
 * </ul>
 *
 * <p>TODO (yours): fill in the test bodies and remove {@code @Disabled}. Put any extra CSV files
 * you need in {@code src/test/resources/fixtures/}.
 */
@SpringBootTest
@Import(PostgresContainerConfig.class)
class IngestionIntegrationTest {

    @Autowired
    private IngestionService service;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Test isolation: every test starts from empty tables. All four tables are listed in one
     * TRUNCATE. {@code RESTART IDENTITY} resets the auto-generated ids to 1, and {@code CASCADE}
     * also empties tables that reference these through foreign keys.
     */
    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE transaction, import_file, category, account RESTART IDENTITY CASCADE");
    }

    @Test
    @Disabled("TODO: write assertions")
    void importingAFileInsertsTheRightRows() {
        // Import a fixture, then check the stored rows (count, and spot-check a few values).
    }

    @Test
    @Disabled("TODO: write assertions")
    void reimportingTheSameFileInsertsZeroRows() {
        // Import the same fixture twice; the second result should report only duplicates.
    }

    @Test
    @Disabled("TODO: write assertions (needs two overlapping fixtures)")
    void twoFilesWithOverlappingRowsInsertOnlyTheNewOnes() {
        // File B repeats some rows of file A and adds new ones. Different files, so different
        // hashes, so this is dedup by transaction key and not by file hash.
    }

    @Test
    @Disabled("TODO: write assertions")
    void migrationsApplyCleanlyOnAnEmptyDatabase() {
        // The container starts empty and Flyway runs on startup, so reaching this point already
        // means the migrations did not fail. Assert that the expected tables exist, and look at
        // the flyway_schema_history table to see which versions were applied.
    }

    @Test
    @Disabled("TODO (stretch): write assertions")
    void concurrentImportsOfTheSameFileDoNotCreateDuplicates() {
        // Run the same import from several threads at once (ExecutorService), wait for all of them,
        // then check the transaction count. Think about what happens to the import_file row.
    }
}
