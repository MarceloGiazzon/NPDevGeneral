ALTER TABLE p75_unguardable ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'unknown';
ALTER TABLE p75_unguardable DROP COLUMN legacy;
