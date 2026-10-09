package com.contextlayer.backend.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KeywordExtractorTest {

    private final KeywordExtractor extractor = new KeywordExtractor();

    @Test
    void dropsFillerAndTaskVerbs() {
        assertThat(extractor.extract("Fix the authentication timeout issue", 12))
                .containsExactly("authentication", "timeout");
    }

    @Test
    void splitsIdentifiersAndStems() {
        assertThat(extractor.extract("AuthService refreshToken sessions", 12))
                .containsExactly("auth", "service", "refresh", "token", "session");
    }

    @Test
    void respectsMaxAndDeduplicates() {
        assertThat(extractor.extract("token token Token tokens alpha beta gamma", 3))
                .containsExactly("token", "alpha", "beta");
    }

    @Test
    void emptyInputGivesNoKeywords() {
        assertThat(extractor.extract("   ", 12)).isEmpty();
        assertThat(extractor.extract("the and of", 12)).isEmpty();
    }

    @Test
    void buildsAnOrPrefixQuery() {
        assertThat(KeywordExtractor.toTsQuery(List.of("auth", "timeout"))).isEqualTo("auth:* | timeout:*");
    }
}