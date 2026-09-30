-- Free-text annotations on a document (FR-16). Add/edit only - no delete
-- path, so the annotation trail stays intact. Java port of the NestJS
-- backend's migrations/1783740000000-InitDocumentNotes.ts.

CREATE TABLE document_note (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id           UUID NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    body                  TEXT NOT NULL,
    created_by_sub        VARCHAR(255) NOT NULL,
    created_by_username   VARCHAR(255) NOT NULL,
    updated_by_sub        VARCHAR(255),
    updated_by_username   VARCHAR(255),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IDX_document_note_document_id ON document_note (document_id);
