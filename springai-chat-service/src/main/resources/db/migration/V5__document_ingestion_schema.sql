ALTER TABLE attachment
    ADD COLUMN IF NOT EXISTS file_size BIGINT,
    ADD COLUMN IF NOT EXISTS checksum VARCHAR(128),
    ADD COLUMN IF NOT EXISTS category VARCHAR(50) DEFAULT 'GENERAL',
    ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS superseded_attachment_id UUID REFERENCES attachment(id),
    ADD COLUMN IF NOT EXISTS extracted_text TEXT;

CREATE INDEX IF NOT EXISTS idx_attachment_category_active ON attachment(user_id, category, is_active);

CREATE TABLE IF NOT EXISTS document_chunk (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attachment_id UUID NOT NULL REFERENCES attachment(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    conversation_id UUID REFERENCES conversation(id) ON DELETE SET NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    token_count INT,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_document_chunk_attachment_id ON document_chunk(attachment_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_document_chunk_user_id ON document_chunk(user_id);
