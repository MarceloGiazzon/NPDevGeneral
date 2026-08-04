ALTER TABLE s7_order_lines ADD COLUMN IF NOT EXISTS product_id UUID;
UPDATE s7_order_lines SET product_id = (SELECT m.id FROM s7_products m WHERE m.sku = s7_order_lines.product_sku) WHERE product_id IS NULL;
ALTER TABLE s7_order_lines ALTER COLUMN product_id SET NOT NULL;
