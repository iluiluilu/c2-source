-- chunked-file-upload-02-personal-chunked-upload.md §2.1: adds the one thing
-- upload_session (V16) is missing for the personal flow -
-- PersonalService.uploadFileInBatch(user, batchId, folderId, file) needs a
-- destination folderId, a concept DocumentsService.ingestBatchFile has no
-- equivalent for (WAREHOUSE uses relative_path instead). Column is always
-- NULL for a BatchKind.WAREHOUSE session.

ALTER TABLE upload_session
    ADD COLUMN target_personal_folder_id UUID NULL
        REFERENCES personal_folder (id);
