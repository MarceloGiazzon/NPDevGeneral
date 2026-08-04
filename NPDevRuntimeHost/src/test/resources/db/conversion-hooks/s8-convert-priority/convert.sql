ALTER TABLE s8_orders ADD COLUMN IF NOT EXISTS priority_number INTEGER;
UPDATE s8_orders SET priority_number = CAST(priority_text AS INTEGER) WHERE priority_number IS NULL;
ALTER TABLE s8_orders ALTER COLUMN priority_number SET NOT NULL;
