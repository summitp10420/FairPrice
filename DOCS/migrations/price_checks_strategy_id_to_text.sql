-- strategy_id is an opaque id from the strategy engine (e.g. strat_railway_alpha), not a UUID.
-- Use TEXT for future flexibility. Must drop FK to strategies table first (key was uuid).

DO $$
BEGIN
    -- Drop FK if it exists (allows changing type from uuid to text)
    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = 'public'
          AND table_name = 'price_checks'
          AND constraint_name = 'price_checks_strategy_id_fkey'
    ) THEN
        ALTER TABLE public.price_checks DROP CONSTRAINT price_checks_strategy_id_fkey;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'price_checks' AND column_name = 'strategy_id'
    ) THEN
        ALTER TABLE public.price_checks ADD COLUMN strategy_id text;
    ELSIF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'price_checks' AND column_name = 'strategy_id' AND data_type = 'uuid'
    ) THEN
        ALTER TABLE public.price_checks ALTER COLUMN strategy_id TYPE text USING strategy_id::text;
    END IF;
END $$;
