-- Case files group documents under an investigation (FR-28/29). Java port of
-- the NestJS backend's migrations/1783720000000-InitCaseFiles.ts.

CREATE TABLE case_file (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_code            VARCHAR(64) NOT NULL,
    title                VARCHAR(512) NOT NULL,
    handling_unit        VARCHAR(255) NOT NULL,
    assigned_officer     VARCHAR(255) NOT NULL,
    status               VARCHAR(16) NOT NULL,
    description          TEXT,
    opened_at            TIMESTAMPTZ NOT NULL,
    closed_at            TIMESTAMPTZ,
    created_by_sub       VARCHAR(255) NOT NULL,
    created_by_username  VARCHAR(255) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT UQ_case_file_case_code UNIQUE (case_code)
);

CREATE INDEX IDX_case_file_handling_unit ON case_file (handling_unit);
CREATE INDEX IDX_case_file_status ON case_file (status);

-- A document may belong to many case files and vice-versa; a document may be
-- attached to a given case at most once (composite PK).
CREATE TABLE case_file_document (
    case_file_id          UUID NOT NULL REFERENCES case_file (id) ON DELETE RESTRICT,
    document_id           UUID NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    attached_by_sub       VARCHAR(255) NOT NULL,
    attached_by_username  VARCHAR(255) NOT NULL,
    attached_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (case_file_id, document_id)
);

CREATE INDEX IDX_case_file_document_document_id ON case_file_document (document_id);
