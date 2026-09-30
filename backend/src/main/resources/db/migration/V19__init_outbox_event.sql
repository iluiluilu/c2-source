-- classification-notes-01-tags.md / classification-notes-02-notes.md §2:
-- generic outbox table shared by document.classification.changed (this spec)
-- and document.note.changed (classification-notes-02-notes.md). Whoever
-- implements first creates this migration; the other spec reuses the table
-- without recreating it (see the "quyết định số hiệu Flyway" note in
-- classification-notes-01-tags.md §2).
--
-- No 'status'/'failed' column: this stage does not build a real publish/retry
-- job (no consumer yet), so 'processed_at IS NULL/NOT NULL' is enough to tell
-- "not yet handled" apart from "handled" for the retention job (Task 9b).
CREATE TABLE outbox_event (
    id            BIGSERIAL PRIMARY KEY,
    event_type    VARCHAR(128) NOT NULL,
    payload       JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at  TIMESTAMPTZ,
    attempts      INT NOT NULL DEFAULT 0,
    last_error    TEXT
);

-- Backlog check (retention job) scans unprocessed rows oldest-first.
CREATE INDEX IDX_outbox_event_unprocessed ON outbox_event (created_at) WHERE processed_at IS NULL;
-- Retention sweep scans processed rows by processed_at.
CREATE INDEX IDX_outbox_event_processed ON outbox_event (processed_at) WHERE processed_at IS NOT NULL;
CREATE INDEX IDX_outbox_event_type ON outbox_event (event_type);
