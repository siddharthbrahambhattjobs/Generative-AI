CREATE TABLE IF NOT EXISTS user_memory_summary (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES app_user(id) ON DELETE CASCADE,
    summary_text TEXT NOT NULL,
    last_conversation_id UUID REFERENCES conversation(id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_memory_summary_user_id ON user_memory_summary(user_id);
