-- EONA search, step 3: publish the checked build in one transaction. The backend reads schema
-- "search"; the previous version stays as "search_prev" for a rollback.

\set ON_ERROR_STOP on

BEGIN;
DROP SCHEMA IF EXISTS search_prev CASCADE;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'search') THEN
        ALTER SCHEMA search RENAME TO search_prev;
    END IF;
END $$;
ALTER SCHEMA search_next RENAME TO search;
COMMIT;
