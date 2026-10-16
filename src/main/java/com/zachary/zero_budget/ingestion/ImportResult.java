package com.zachary.zero_budget.ingestion;

import java.util.List;

/**
 * What an import did. Every data row in the file lands in exactly one bucket, so
 * {@code rowsTotal == rowsInserted + rowsRejected + rowsDuplicate} always holds.
 *
 * @param filename       the file's name (not its full path)
 * @param fileHash       SHA-256 of the file's contents
 * @param fileAlreadySeen true if a file with identical contents was imported before, in which case
 *                       the existing {@code import_file} record is reused and left unchanged
 * @param rowsTotal      all data rows in the file (the header is not counted)
 * @param rowsInserted   valid rows that were new and are now stored
 * @param rowsRejected   rows that failed validation (details in {@code rejections})
 * @param rowsDuplicate  valid rows skipped because the database already had them
 * @param rejections     one entry per rejected row: row number and reason
 */
public record ImportResult(
        String filename,
        String fileHash,
        boolean fileAlreadySeen,
        int rowsTotal,
        int rowsInserted,
        int rowsRejected,
        int rowsDuplicate,
        List<Rejection> rejections) {

    public ImportResult {
        rejections = List.copyOf(rejections);
    }
}
