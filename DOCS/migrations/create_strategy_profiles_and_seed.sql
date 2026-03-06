-- Strategy catalog: defines "recipes" the engine references and Android executes.
-- Run in Supabase SQL editor. Then fix price_checks.strategy_id to reference this table.

-- 1.1 Create strategy_profiles table
CREATE TABLE IF NOT EXISTS public.strategy_profiles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code text UNIQUE NOT NULL,
    name text NOT NULL,
    description text,
    is_active boolean DEFAULT true,
    definition jsonb NOT NULL,
    created_at timestamptz DEFAULT timezone('utc', now())
);

-- Seed legacy profile
INSERT INTO public.strategy_profiles (code, name, description, definition)
VALUES (
    'legacy',
    'Legacy Control',
    'Standard execution with no tracking protection or amnesia wipe.',
    '{"amnesia_wipe_required": false, "strict_tracking_protection": false, "canvas_spoofing_active": false, "url_sanitize": false}'::jsonb
)
ON CONFLICT (code) DO NOTHING;

-- Seed yale_smart profile
INSERT INTO public.strategy_profiles (code, name, description, definition)
VALUES (
    'yale_smart',
    'Yale Smart Enhanced',
    'Aggressive privacy profile. Clears session, enables strict tracking, and spoofs canvas.',
    '{"amnesia_wipe_required": true, "strict_tracking_protection": true, "canvas_spoofing_active": true, "url_sanitize": true}'::jsonb
)
ON CONFLICT (code) DO NOTHING;

-- 1.2 Fix price_checks.strategy_id: point to strategy_profiles
ALTER TABLE public.price_checks
    DROP CONSTRAINT IF EXISTS price_checks_strategy_id_fkey;

-- If column is text (e.g. after price_checks_strategy_id_to_text): clear non-UUIDs then convert to uuid
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'price_checks' AND column_name = 'strategy_id'
    ) THEN
        ALTER TABLE public.price_checks ADD COLUMN strategy_id uuid REFERENCES public.strategy_profiles(id);
    ELSIF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'price_checks' AND column_name = 'strategy_id' AND data_type = 'text'
    ) THEN
        UPDATE public.price_checks SET strategy_id = NULL
        WHERE strategy_id IS NOT NULL AND strategy_id !~ '^[0-9a-fA-F-]{8}-[0-9a-fA-F-]{4}-[0-9a-fA-F-]{4}-[0-9a-fA-F-]{4}-[0-9a-fA-F-]{12}$';
        ALTER TABLE public.price_checks ALTER COLUMN strategy_id TYPE uuid USING strategy_id::uuid;
    END IF;
END $$;

-- Add FK when strategy_id already existed (column creation above already has REFERENCES)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = 'public' AND table_name = 'price_checks' AND constraint_name = 'price_checks_strategy_id_fkey'
    ) THEN
        ALTER TABLE public.price_checks
            ADD CONSTRAINT price_checks_strategy_id_fkey
            FOREIGN KEY (strategy_id) REFERENCES public.strategy_profiles(id);
    END IF;
END $$;
