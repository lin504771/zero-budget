package com.zachary.zero_budget.ingestion;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Stores transactions in Postgres. TODO: implement by hand (guide, Step 2).
 *
 * <p>Contract the tests expect:
 * <ul>
 *   <li>one {@code INSERT ... ON CONFLICT (dedup_key) DO NOTHING} per row, sent as a batch</li>
 *   <li>{@code normalized_description} is filled using {@link DedupKeyGenerator#normalizeDescription}</li>
 *   <li>{@code category_id} is left {@code null} for now</li>
 *   <li>returns how many rows were <em>actually inserted</em> (duplicates are not counted)</li>
 * </ul>
 */
@Repository
public class TransactionRepository {

    private final JdbcTemplate jdbcTemplate;

    public TransactionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insertAll(List<NewTransaction> rows) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
