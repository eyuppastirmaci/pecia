package dev.eyuppastirmaci.pecia.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class SearchModeTest {

    @Test
    void configValuesAreLowercaseNamesInDeclarationOrder() {
        assertEquals(List.of("lexical", "semantic", "hybrid", "auto"), SearchMode.configValues());
    }

    @ParameterizedTest
    @EnumSource(SearchMode.class)
    void eachModeIsFoundByItsConfigValue(SearchMode mode) {
        assertEquals(Optional.of(mode), SearchMode.fromConfigValue(mode.configValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Hybrid", "LEXICAL", " auto", "auto ", "", "fuzzy", "semantic-search"})
    void otherSpellingsAreNotModes(String value) {
        assertEquals(Optional.empty(), SearchMode.fromConfigValue(value));
    }

    @Test
    void nullIsRejected() {
        assertThrows(NullPointerException.class, () -> SearchMode.fromConfigValue(null));
    }
}
