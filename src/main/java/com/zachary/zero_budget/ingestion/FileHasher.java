package com.zachary.zero_budget.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Fingerprints a file's exact bytes, so we can recognise "this is the same file I imported before",
 * even if it has been renamed.
 *
 * <p>A static utility class like {@link DedupKeyGenerator}. Note the two are about different
 * things: this identifies a whole <em>file</em>, the dedup key identifies one <em>transaction</em>.
 */
public final class FileHasher {

    private FileHasher() {
    }

    /**
     * @return 64 lowercase hex characters (SHA-256 of the file's bytes)
     * @throws CsvParseException if the file can't be read
     */
    public static String sha256Hex(Path file) {
        try {
            // Reads the whole file into memory. Fine for bank CSVs (kilobytes to a few MB); a file
            // of many gigabytes would need streaming through the digest in chunks instead.
            byte[] bytes = Files.readAllBytes(file);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (IOException e) {
            throw new CsvParseException("Could not read file " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
