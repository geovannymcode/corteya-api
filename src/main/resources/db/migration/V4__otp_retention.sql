-- Retention indexes for global credentials; V3 remains immutable.
CREATE INDEX idx_otp_challenge_expires_at ON otp_challenge(expires_at);
CREATE INDEX idx_refresh_token_expires_at ON refresh_token(expires_at);
CREATE INDEX idx_refresh_token_revoked_at ON refresh_token(revoked_at) WHERE revoked_at IS NOT NULL;
