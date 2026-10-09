package com.contextlayer.backend.indexing.chunk;

import org.springframework.stereotype.Component;

/**
 * Rough estimate: about 4 characters per token for English and code.
 * Good enough for chunk sizing. On Day 14 we replace this with a real tokenizer
 * for exact budgeting.
 */
@Component
public class TokenEstimator {

    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 4.0);
    }
}