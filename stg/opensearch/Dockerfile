# search-fulltext-01-tim-kiem-co-ban.md Task 2: the bare opensearchproject
# image ships no analysis plugins. search-fulltext.md Quyết định 1 needs
# analysis-icu (icu_tokenizer/icu_normalizer/icu_folding) for the
# `segment`/`document` index mappings, plus a Vietnamese stopword list for the
# `vi_stop` filter those analyzers use.
FROM opensearchproject/opensearch:2.17.1

RUN /usr/share/opensearch/bin/opensearch-plugin install --batch analysis-icu

COPY vi_stopwords.txt /usr/share/opensearch/config/analysis/vi_stopwords.txt
