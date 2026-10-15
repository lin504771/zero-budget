package com.zachary.zero_budget.ingestion;

/**
 * Everything the repository needs to insert one row into {@code transaction}.
 *
 * <p>{@link ValidatedTransaction} only knows what came from the CSV. The database row also needs
 * facts the CSV can't supply (which account, which import run) and the computed dedup key. Bundling
 * them in one small record keeps {@code insertAll(List<NewTransaction>)} simple, and adding a
 * column later means adding a field here instead of changing every method signature.
 *
 * @param accountId    the account these rows belong to (must already exist: foreign key)
 * @param importFileId the {@code import_file} row for this run (must already exist: foreign key)
 * @param dedupKey     fingerprint from {@link DedupKeyGenerator#generate}
 * @param transaction  the validated CSV row
 */
public record NewTransaction(
        long accountId,
        long importFileId,
        String dedupKey,
        ValidatedTransaction transaction) {
}
