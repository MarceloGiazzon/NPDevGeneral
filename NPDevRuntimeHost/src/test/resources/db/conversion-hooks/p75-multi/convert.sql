ALTER TABLE p75_multi ADD COLUMN status VARCHAR(20);
UPDATE p75_multi SET status = 'unknown' WHERE status IS NULL;
ALTER TABLE p75_multi ALTER COLUMN status SET NOT NULL;
