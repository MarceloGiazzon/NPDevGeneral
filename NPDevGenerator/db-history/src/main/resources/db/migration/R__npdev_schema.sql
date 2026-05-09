-- NPDev generated repeatable schema migration
-- File: R__npdev_schema.sql
-- Strategy:
--  - CREATE TABLE IF NOT EXISTS
--  - ALTER TABLE ADD COLUMN IF NOT EXISTS
--  - SET NOT NULL for required fields
--  - CREATE UNIQUE INDEX IF NOT EXISTS for unique fields
--
-- WARNING:
--  - If required fields exist with NULLs, SET NOT NULL may fail.
--  - Destructive changes (DROP COLUMN) are not generated.

CREATE TABLE IF NOT EXISTS users (
  id UUID PRIMARY KEY
);

ALTER TABLE users ADD COLUMN IF NOT EXISTS active BOOLEAN;
ALTER TABLE users ADD COLUMN IF NOT EXISTS email VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS name VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(255);

ALTER TABLE users ALTER COLUMN active SET NOT NULL;
ALTER TABLE users ALTER COLUMN email SET NOT NULL;
ALTER TABLE users ALTER COLUMN name SET NOT NULL;
ALTER TABLE users ALTER COLUMN tenant_id SET NOT NULL;


-- ----

