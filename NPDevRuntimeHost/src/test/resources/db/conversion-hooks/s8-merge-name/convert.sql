ALTER TABLE s8_customers ADD COLUMN IF NOT EXISTS display_name VARCHAR(255);
UPDATE s8_customers SET display_name = CONCAT(first_name, ' ', last_name) WHERE display_name IS NULL AND first_name IS NOT NULL AND last_name IS NOT NULL;
ALTER TABLE s8_customers ALTER COLUMN display_name SET NOT NULL;
