-- transaction_id is both the primary key and the idempotency key.
-- The named UNIQUE constraint is what a concurrent duplicate insert hits.
CREATE TABLE IF NOT EXISTS transactions (
    transaction_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'INR',
    merchant_id VARCHAR(64),
    merchant_category VARCHAR(64),
    location VARCHAR(128),
    device_id VARCHAR(128),
    channel VARCHAR(32),
    transaction_ts TIMESTAMP WITH TIME ZONE,
    risk_score DOUBLE PRECISION,
    risk_level VARCHAR(16),
    scoring_reasons TEXT,
    scoring_mode VARCHAR(16),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_transactions PRIMARY KEY (transaction_id),
    CONSTRAINT uq_transactions_transaction_id UNIQUE (transaction_id)
);

CREATE INDEX IF NOT EXISTS idx_transactions_user_id ON transactions (user_id);
CREATE INDEX IF NOT EXISTS idx_transactions_created_at ON transactions (created_at);

CREATE TABLE IF NOT EXISTS fraud_alerts (
    id BIGSERIAL PRIMARY KEY,
    transaction_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    risk_score DOUBLE PRECISION NOT NULL,
    reasons TEXT,
    amount NUMERIC(12, 2),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_alerts_created_at ON fraud_alerts (created_at);
CREATE INDEX IF NOT EXISTS idx_alerts_user_id ON fraud_alerts (user_id);
