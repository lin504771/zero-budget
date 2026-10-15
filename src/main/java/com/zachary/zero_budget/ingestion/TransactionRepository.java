package com.zachary.zero_budget.ingestion;

import java.sql.Statement;
import java.sql.Types;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * All SQL for the {@code transaction} table.
 *
 * <p>Duplicates are filtered by the database, not by Java code: the {@code dedup_key} column is
 * UNIQUE, and {@code ON CONFLICT DO NOTHING} makes Postgres silently skip a row whose key already
 * exists. That keeps imports idempotent even if two imports run at the same time.
 *
 * <p>The repository stores the {@code dedupKey} it is given. It never computes it, so the key
 * rules live in exactly one place ({@link DedupKeyGenerator}).
 */
@Repository
public class TransactionRepository {

    // Rows sent to the database per round trip. Bounds memory and statement size on huge files.
    private static final int BATCH_SIZE = 500;

    private static final String INSERT_SQL = """
            INSERT INTO transaction (
                account_id, posted_date, amount, description, normalized_description,
                category_id, import_file_id, dedup_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (dedup_key) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public TransactionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts the rows, skipping any whose dedup key already exists.
     *
     * @return how many rows were actually inserted; the duplicates are
     *         {@code rows.size() - returnedValue}
     */
    public int insertAll(List<NewTransaction> rows) {
        if (rows.isEmpty()) {
            return 0;
        }

        // The lambda is called once per row. It receives the PreparedStatement and the row.
        // The result is one int[] per chunk of BATCH_SIZE rows, hence int[][].
        int[][] countsPerChunk = jdbcTemplate.batchUpdate(INSERT_SQL, rows, BATCH_SIZE, (ps, row) -> {
            ValidatedTransaction tx = row.transaction();
            ps.setLong(1, row.accountId());
            ps.setObject(2, tx.postedDate());
            ps.setBigDecimal(3, tx.amount());
            ps.setString(4, tx.description());
            ps.setString(5, DedupKeyGenerator.normalizeDescription(tx.description()));
            ps.setNull(6, Types.BIGINT); // category_id is null for now
            ps.setLong(7, row.importFileId());
            ps.setString(8, row.dedupKey());
        });

        int inserted = 0;
        for (int[] chunk : countsPerChunk) {
            for (int count : chunk) {
                // 1 = row inserted, 0 = skipped by ON CONFLICT. A driver may also answer
                // SUCCESS_NO_INFO (-2), meaning "it worked, but I won't say how many rows".
                // We can't tell inserted from skipped then, so fail loudly instead of guessing.
                if (count == Statement.SUCCESS_NO_INFO) {
                    throw new IllegalStateException(
                            "JDBC driver did not report per-row update counts; cannot count duplicates");
                }
                inserted += count;
            }
        }
        return inserted;
    }
}
