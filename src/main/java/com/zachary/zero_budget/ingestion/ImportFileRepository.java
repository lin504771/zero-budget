package com.zachary.zero_budget.ingestion;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for the {@code import_file} table: one row per distinct file we have imported, holding the
 * file's hash and how its rows turned out.
 */
@Repository
public class ImportFileRepository {

    private final JdbcClient jdbc;

    public ImportFileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Looks up a previously imported file by its content hash.
     *
     * <p>{@code Optional} is Java's way of saying "there may be no answer" in the type itself,
     * instead of returning {@code null} and hoping callers remember to check.
     */
    public Optional<Long> findIdByHash(String fileHash) {
        return jdbc.sql("SELECT id FROM import_file WHERE file_hash = :hash")
                .param("hash", fileHash)
                .query(Long.class)
                .optional();
    }

    /**
     * Creates the row with all counts at zero and returns its id.
     *
     * <p>Why zeros and a later {@link #updateCounts}? Every transaction row has a foreign key to
     * this row, so it must exist <em>before</em> the transactions are inserted, but the real
     * counts are only known <em>after</em> inserting them.
     */
    public long create(String filename, String fileHash) {
        return jdbc.sql("""
                        INSERT INTO import_file
                            (filename, file_hash, rows_total, rows_ok, rows_rejected, rows_duplicate)
                        VALUES (:filename, :hash, 0, 0, 0, 0)
                        RETURNING id
                        """)
                .param("filename", filename)
                .param("hash", fileHash)
                .query(Long.class)
                .single();
    }

    public void updateCounts(long id, int rowsTotal, int rowsOk, int rowsRejected, int rowsDuplicate) {
        jdbc.sql("""
                        UPDATE import_file
                        SET rows_total = :total, rows_ok = :ok,
                            rows_rejected = :rejected, rows_duplicate = :duplicate
                        WHERE id = :id
                        """)
                .param("total", rowsTotal)
                .param("ok", rowsOk)
                .param("rejected", rowsRejected)
                .param("duplicate", rowsDuplicate)
                .param("id", id)
                .update();
    }
}
