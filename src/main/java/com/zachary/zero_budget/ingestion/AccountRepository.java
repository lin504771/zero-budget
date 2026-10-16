package com.zachary.zero_budget.ingestion;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for the {@code account} table.
 *
 * <p>This class uses {@link JdbcClient}, Spring's newer fluent API: you write the SQL with named
 * parameters ({@code :name}) and chain {@code .param(...)} calls. It is easier to read than the
 * positional {@code ?} placeholders {@code TransactionRepository} uses. Spring provides a
 * {@code JdbcClient} bean automatically, just like it provides {@code JdbcTemplate}.
 */
@Repository
public class AccountRepository {

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the id of the account with this name, creating it if it doesn't exist yet.
     *
     * <p>This is done in a <em>single</em> statement on purpose. The naive version is
     * "SELECT; if missing, INSERT", but two imports running at once could both see "missing" and
     * both INSERT, and one would crash on the UNIQUE(name) constraint. Letting the database do
     * the check-and-insert atomically removes that race.
     *
     * <p>{@code ON CONFLICT ... DO UPDATE SET name = EXCLUDED.name} is a deliberate no-op update.
     * Plain {@code DO NOTHING} would return <em>no row</em> when the account already exists,
     * and {@code RETURNING} only returns rows that were inserted or updated. Re-setting the name
     * to itself makes Postgres return the existing row's id. ({@code EXCLUDED} is Postgres's
     * name for "the row we tried to insert".)
     */
    public long findOrCreate(String name, String type) {
        return jdbc.sql("""
                        INSERT INTO account (name, type) VALUES (:name, :type)
                        ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name
                        RETURNING id
                        """)
                .param("name", name)
                .param("type", type)
                .query(Long.class)
                .single();
    }
}
