package com.aismartstore.backend.search;

/**
 * SPEC-search-semantic T5: the embedding endpoint failed, timed out or returned a response that
 * fails validation (wrong element count / wrong dimension). Never carries the input text.
 */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
