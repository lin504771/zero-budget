package com.zachary.zero_budget.ingestion;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates one import: file in, rows stored, counts out.
 *
 * <p>This class contains almost no logic of its own. It calls the other layers in order
 * (parser, validator, repositories) and adds the one thing none of them can do alone: numbering
 * identical rows so that dedup keys are correct. Keeping each layer small and single-purpose is
 * what makes them individually testable, and the future REST API (Step 4) can call this same
 * service instead of duplicating the pipeline.
 */
@Service
public class IngestionService {

    /** The CSV doesn't say what kind of account it came from, so we record that we don't know. */
    static final String DEFAULT_ACCOUNT_TYPE = "UNKNOWN";

    private final CsvTransactionParser parser;
    private final TransactionValidator validator;
    private final AccountRepository accountRepository;
    private final ImportFileRepository importFileRepository;
    private final TransactionRepository transactionRepository;

    // Spring sees one constructor and supplies a bean for each parameter (dependency injection).
    public IngestionService(
            CsvTransactionParser parser,
            TransactionValidator validator,
            AccountRepository accountRepository,
            ImportFileRepository importFileRepository,
            TransactionRepository transactionRepository) {
        this.parser = parser;
        this.validator = validator;
        this.accountRepository = accountRepository;
        this.importFileRepository = importFileRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Imports a CSV file into the named account (created if it doesn't exist).
     *
     * <p>{@code @Transactional}: everything below happens in ONE database transaction. If any step
     * throws a RuntimeException, Spring rolls back every write, so we never end up with an
     * {@code import_file} row whose transactions are missing, or half a file's rows stored.
     * It works through a proxy Spring wraps around this bean, so it only applies to calls coming
     * <em>from other classes</em> (the CLI, the future controller), which is how this method is used.
     *
     * @throws CsvParseException if the file is unreadable or its structure is unusable (missing
     *                           column, broken quoting). Nothing is written in that case.
     */
    @Transactional
    public ImportResult importFile(Path file, String accountName) {
        // 1. Everything that needs no database comes first, so a bad file fails before we write.
        String filename = file.getFileName().toString();
        String fileHash = FileHasher.sha256Hex(file);
        List<RawTransactionRow> rawRows = parser.parse(file);
        ValidationResult validation = validator.validate(rawRows);

        // 2. Find or create the parent rows that transactions point to (foreign keys).
        long accountId = accountRepository.findOrCreate(accountName, DEFAULT_ACCOUNT_TYPE);
        Optional<Long> existingImport = importFileRepository.findIdByHash(fileHash);
        boolean fileAlreadySeen = existingImport.isPresent();
        long importFileId = existingImport.orElseGet(() -> importFileRepository.create(filename, fileHash));

        // 3. Turn validated rows into insertable rows with their dedup keys, then insert.
        List<NewTransaction> newTransactions = withDedupKeys(accountId, importFileId, validation.valid());
        int inserted = transactionRepository.insertAll(newTransactions);

        // 4. Anything valid that wasn't inserted was skipped by ON CONFLICT: a duplicate.
        int rejected = validation.rejections().size();
        int duplicates = validation.valid().size() - inserted;

        // A file we've seen before keeps its original counts; the record describes the first import.
        if (!fileAlreadySeen) {
            importFileRepository.updateCounts(importFileId, rawRows.size(), inserted, rejected, duplicates);
        }

        return new ImportResult(
                filename, fileHash, fileAlreadySeen,
                rawRows.size(), inserted, rejected, duplicates, validation.rejections());
    }

    /**
     * Attaches a dedup key to each row, numbering identical rows 1, 2, 3... in file order.
     *
     * <p>"Identical" means same account, date, amount and normalized description. We don't
     * rebuild that definition here. The key generated with occurrence 1 is already a fingerprint
     * of exactly those fields, so it doubles as the "identity" to count by. The first row with a
     * given identity keeps that key. A second identical row gets a key regenerated with
     * occurrence 2, and so on.
     *
     * <p>This depends on the order of {@code rows}: the validator returns them in file order, and
     * the same file always yields the same numbering, which is what keeps re-imports idempotent.
     */
    private List<NewTransaction> withDedupKeys(long accountId, long importFileId, List<ValidatedTransaction> rows) {
        Map<String, Integer> seenCount = new HashMap<>();
        List<NewTransaction> result = new ArrayList<>(rows.size());

        for (ValidatedTransaction tx : rows) {
            String identity = DedupKeyGenerator.generate(accountId, tx, 1);
            // merge: if the identity is new, store 1; otherwise add 1 to the existing count.
            // It returns the new value, which is this row's occurrence number.
            int occurrence = seenCount.merge(identity, 1, Integer::sum);
            String dedupKey = occurrence == 1 ? identity : DedupKeyGenerator.generate(accountId, tx, occurrence);
            result.add(new NewTransaction(accountId, importFileId, dedupKey, tx));
        }
        return result;
    }
}
