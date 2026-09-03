ALTER TABLE s9_orders ADD COLUMN IF NOT EXISTS region_label VARCHAR(255);
UPDATE s9_orders SET region_label = CASE WHEN region_code = 'N' THEN 'North' WHEN region_code = 'S' THEN 'South' ELSE 'Unknown' END WHERE region_label IS NULL;
ALTER TABLE s9_orders ALTER COLUMN region_label SET NOT NULL;
