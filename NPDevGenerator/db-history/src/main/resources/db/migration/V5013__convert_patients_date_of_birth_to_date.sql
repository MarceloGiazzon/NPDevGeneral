-- NPDev Patch: Convert patients.date_of_birth to DATE safely (handles old timestamptz/text)
-- Why:
--   Older project versions stored date_of_birth as timestamp/text (may include timezone like '-02'/'-03').
--   New model projects dateOfBirth as LocalDate (DATE). Postgres needs an explicit USING cast.
--
-- Safe behavior:
--   - Works even if the table/column doesn't exist yet (first run).
--   - Converts by taking the first 10 chars of the textual form: 'YYYY-MM-DD'.
--
-- Notes:
--   If you have any rows where the first 10 chars are not a valid date, this migration will fail.
--   In dev, the simplest recovery is to reset the schema using reset-db.ps1 and re-run.

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = 'public'
      AND table_name   = 'patients'
      AND column_name  = 'date_of_birth'
  ) THEN
    -- Convert to DATE using an explicit cast.
    EXECUTE $SQL$
      ALTER TABLE IF EXISTS patients
      ALTER COLUMN date_of_birth
      TYPE date
      USING (substring(date_of_birth::text from 1 for 10))::date
    $SQL$;
  END IF;
END
$$;