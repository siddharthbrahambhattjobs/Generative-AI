ALTER TABLE attachments
    ALTER COLUMN extracted_text TYPE TEXT,
    ALTER COLUMN storage_path TYPE TEXT,
    ALTER COLUMN file_name TYPE VARCHAR(512),
    ALTER COLUMN content_type TYPE VARCHAR(255),
    ALTER COLUMN processing_status TYPE VARCHAR(30),
    ALTER COLUMN content_hash TYPE VARCHAR(64);

ALTER TABLE attachments
    DROP CONSTRAINT IF EXISTS attachment_superseded_attachment_id_fkey;

ALTER TABLE attachments
    ADD CONSTRAINT attachment_superseded_attachment_id_fkey
    FOREIGN KEY (superseded_attachment_id)
    REFERENCES attachments(id)
    ON DELETE SET NULL;
ALTER TABLE attachments
ALTER COLUMN extracted_text TYPE TEXT;

CREATE INDEX IF NOT EXISTS idx_attachments_superseded_attachment_id
    ON attachments(superseded_attachment_id);

CREATE INDEX IF NOT EXISTS idx_attachments_user_filename_version
    ON attachments(user_id, file_name, version_number DESC);