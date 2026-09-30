package com.aismartstore.backend.tags;

import java.util.List;

/**
 * classification-notes-01-tags.md §2 (V20): thu hẹp còn đúng 2 kind
 * {@code topic}/{@code doc_type} (bỏ {@code unit}/{@code secrecy}, đổi
 * {@code doc-type} -> {@code doc_type}).
 */
public final class TagKinds {
    public static final String TOPIC = "topic";
    public static final String DOC_TYPE = "doc_type";

    public static final List<String> ALL = List.of(TOPIC, DOC_TYPE);

    private TagKinds() {
    }
}
