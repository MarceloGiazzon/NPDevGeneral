ALTER TABLE s7_customers ADD COLUMN IF NOT EXISTS first_name VARCHAR(255);
ALTER TABLE s7_customers ADD COLUMN IF NOT EXISTS last_name VARCHAR(255);
UPDATE s7_customers SET first_name = SUBSTRING(full_name FROM 1 FOR POSITION(' ' IN full_name) - 1), last_name = SUBSTRING(full_name FROM POSITION(' ' IN full_name) + 1) WHERE (first_name IS NULL OR last_name IS NULL) AND full_name IS NOT NULL AND POSITION(' ' IN full_name) > 0;
ALTER TABLE s7_customers ALTER COLUMN first_name SET NOT NULL;
ALTER TABLE s7_customers ALTER COLUMN last_name SET NOT NULL;
