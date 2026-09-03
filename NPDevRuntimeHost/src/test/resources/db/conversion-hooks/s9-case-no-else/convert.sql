ALTER TABLE s9_orders ADD COLUMN IF NOT EXISTS region_label_strict VARCHAR(255);
UPDATE s9_orders SET region_label_strict = CASE WHEN region_code = 'N' THEN 'North' WHEN region_code = 'S' THEN 'South' END WHERE region_label_strict IS NULL;
ALTER TABLE s9_orders ALTER COLUMN region_label_strict SET NOT NULL;
