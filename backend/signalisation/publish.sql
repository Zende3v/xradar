-- x_radar signalisation v2, step 4: publish the checked build in one transaction. The backend
-- reads schema "signs"; the previous version stays as "signs_prev" for a rollback.

\set ON_ERROR_STOP on

BEGIN;
DROP SCHEMA IF EXISTS signs_prev CASCADE;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'signs') THEN
        ALTER SCHEMA signs RENAME TO signs_prev;
    END IF;
END $$;
ALTER SCHEMA signs_next RENAME TO signs;
COMMIT;
