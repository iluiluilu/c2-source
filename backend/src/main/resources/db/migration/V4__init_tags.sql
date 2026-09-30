-- Document tagging (FR-14/15 topic/unit/doc-type/secrecy labels). Java port
-- of the NestJS backend's migrations/1783730000000-InitTags.ts.

CREATE TABLE tag (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind                 VARCHAR(16) NOT NULL,
    label                VARCHAR(128) NOT NULL,
    created_by_sub       VARCHAR(255) NOT NULL,
    created_by_username  VARCHAR(255) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT UQ_tag_kind_label UNIQUE (kind, label)
);

CREATE INDEX IDX_tag_kind ON tag (kind);

-- True many-to-many: a tag may be on many documents and vice-versa.
CREATE TABLE document_tag (
    document_id          UUID NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    tag_id               UUID NOT NULL REFERENCES tag (id) ON DELETE RESTRICT,
    assigned_by_sub      VARCHAR(255) NOT NULL,
    assigned_by_username VARCHAR(255) NOT NULL,
    assigned_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, tag_id)
);

CREATE INDEX IDX_document_tag_tag_id ON document_tag (tag_id);
