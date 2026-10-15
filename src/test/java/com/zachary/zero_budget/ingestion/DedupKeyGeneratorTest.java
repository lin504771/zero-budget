package com.zachary.zero_budget.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DedupKeyGeneratorTest {

    private static final long ACCOUNT = 1L;

    // Small helper so each test only spells out the one field it cares about.
    private static ValidatedTransaction tx(String date, String amount, String description) {
        return new ValidatedTransaction(1, LocalDate.parse(date), new BigDecimal(amount), description, null);
    }

    private final ValidatedTransaction coffee = tx("2026-10-02", "-4.50", "Blue Bottle Coffee");

    @Test
    void sameInputGivesSameKey() {
        assertThat(DedupKeyGenerator.generate(ACCOUNT, coffee, 1))
                .isEqualTo(DedupKeyGenerator.generate(ACCOUNT, coffee, 1));
    }

    @Test
    void keyIsSixtyFourLowercaseHexCharacters() {
        assertThat(DedupKeyGenerator.generate(ACCOUNT, coffee, 1)).matches("[0-9a-f]{64}");
    }

    @Test
    void differentAmountGivesDifferentKey() {
        ValidatedTransaction other = tx("2026-10-02", "-4.51", "Blue Bottle Coffee");

        assertThat(DedupKeyGenerator.generate(ACCOUNT, other, 1))
                .isNotEqualTo(DedupKeyGenerator.generate(ACCOUNT, coffee, 1));
    }

    @Test
    void differentDateGivesDifferentKey() {
        ValidatedTransaction other = tx("2026-10-03", "-4.50", "Blue Bottle Coffee");

        assertThat(DedupKeyGenerator.generate(ACCOUNT, other, 1))
                .isNotEqualTo(DedupKeyGenerator.generate(ACCOUNT, coffee, 1));
    }

    @Test
    void differentAccountGivesDifferentKey() {
        assertThat(DedupKeyGenerator.generate(2L, coffee, 1))
                .isNotEqualTo(DedupKeyGenerator.generate(1L, coffee, 1));
    }

    @Test
    void whitespaceAndCaseDifferencesGiveSameKey() {
        ValidatedTransaction messy = tx("2026-10-02", "-4.50", "  BLUE   bottle\tcoffee ");

        assertThat(DedupKeyGenerator.generate(ACCOUNT, messy, 1))
                .isEqualTo(DedupKeyGenerator.generate(ACCOUNT, coffee, 1));
    }

    @Test
    void amountScaleDoesNotMatterAsLongAsValueIsTheSame() {
        // new BigDecimal("-4.5") and new BigDecimal("-4.50") are not equal() in Java,
        // but they are the same amount of money and must produce the same key.
        ValidatedTransaction shortScale = tx("2026-10-02", "-4.5", "Blue Bottle Coffee");

        assertThat(DedupKeyGenerator.generate(ACCOUNT, shortScale, 1))
                .isEqualTo(DedupKeyGenerator.generate(ACCOUNT, coffee, 1));
    }

    @Test
    void secondIdenticalPurchaseGetsDifferentKey() {
        // The "two identical coffees" case: same day, amount and description.
        assertThat(DedupKeyGenerator.generate(ACCOUNT, coffee, 2))
                .isNotEqualTo(DedupKeyGenerator.generate(ACCOUNT, coffee, 1));
    }

    @Test
    void occurrenceBelowOneIsRejected() {
        assertThatThrownBy(() -> DedupKeyGenerator.generate(ACCOUNT, coffee, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("occurrence");
    }

    @Test
    void normalizeDescriptionTrimsLowercasesAndCollapsesSpaces() {
        assertThat(DedupKeyGenerator.normalizeDescription("  Blue   BOTTLE\tCoffee "))
                .isEqualTo("blue bottle coffee");
    }
}
