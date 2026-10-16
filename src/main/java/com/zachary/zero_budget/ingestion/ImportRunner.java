package com.zachary.zero_budget.ingestion;

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Command-line entry point for importing a file.
 *
 * <p>An {@link ApplicationRunner} is a bean whose {@code run} method Spring calls once, right
 * after the application has started. Usage (no web server needed, so the app exits when done):
 * <pre>
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments="--import=data/file.csv --account=checking --spring.main.web-application-type=none"
 * </pre>
 *
 * <p>When there is no {@code --import} argument (normal startup, and every test), it does
 * nothing. Without that check, the runner would try to import on every launch.
 */
@Component
public class ImportRunner implements ApplicationRunner {

    private static final String DEFAULT_ACCOUNT = "default";
    private static final int MAX_REJECTIONS_SHOWN = 50;

    private final IngestionService ingestionService;

    public ImportRunner(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> importPaths = args.getOptionValues("import");
        if (importPaths == null || importPaths.isEmpty()) {
            return;
        }
        List<String> accounts = args.getOptionValues("account");
        String account = (accounts == null || accounts.isEmpty()) ? DEFAULT_ACCOUNT : accounts.get(0);

        for (String path : importPaths) {
            print(ingestionService.importFile(Path.of(path), account));
        }
    }

    // System.out is appropriate here: this class's whole job is talking to a person at a terminal.
    private static void print(ImportResult result) {
        System.out.printf("Imported %s%s%n", result.filename(),
                result.fileAlreadySeen() ? " (identical file was imported before)" : "");
        System.out.printf("  total rows : %d%n", result.rowsTotal());
        System.out.printf("  inserted   : %d%n", result.rowsInserted());
        System.out.printf("  duplicates : %d%n", result.rowsDuplicate());
        System.out.printf("  rejected   : %d%n", result.rowsRejected());

        result.rejections().stream().limit(MAX_REJECTIONS_SHOWN).forEach(rejection ->
                System.out.printf("    row %d: %s%n", rejection.rowNumber(), rejection.reason()));
        if (result.rejections().size() > MAX_REJECTIONS_SHOWN) {
            System.out.printf("    ... and %d more%n", result.rejections().size() - MAX_REJECTIONS_SHOWN);
        }
    }
}
