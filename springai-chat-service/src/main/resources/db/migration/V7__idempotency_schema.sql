CREATE TABLE IF NOT EXISTS api_idempotency_record (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(200) NOT NULL,
    user_email VARCHAR(320) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    status VARCHAR(30) NOT NULL,
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_api_idempotency_record UNIQUE (idempotency_key, user_email)
);

CREATE INDEX IF NOT EXISTS idx_api_idempotency_record_expires_at ON api_idempotency_record(expires_at);
