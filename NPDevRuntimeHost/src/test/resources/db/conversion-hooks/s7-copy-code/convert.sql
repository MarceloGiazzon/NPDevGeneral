ALTER TABLE s7_orders ADD COLUMN IF NOT EXISTS external_ref VARCHAR(255);
UPDATE s7_orders SET external_ref = legacy_code WHERE external_ref IS NULL;
ALTER TABLE s7_orders ALTER COLUMN external_ref SET NOT NULL;
