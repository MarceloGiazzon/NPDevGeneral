ALTER TABLE s9_customers ADD COLUMN IF NOT EXISTS contact_email VARCHAR(255);
UPDATE s9_customers SET contact_email = COALESCE(primary_email, secondary_email) WHERE contact_email IS NULL;
ALTER TABLE s9_customers ALTER COLUMN contact_email SET NOT NULL;
