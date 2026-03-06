-- Rename applied_levers.engine_profile -> strategy_profile for analytics consistency.
-- Apply in Supabase SQL editor only if price_check_attempts has an applied_levers column (jsonb).
-- If the column does not exist, skip this migration; new data will use strategy_profile from the app.

-- Ensure column exists (no-op if already present)
-- ALTER TABLE public.price_check_attempts ADD COLUMN IF NOT EXISTS applied_levers jsonb;

-- Rename key in existing rows: copy value from engine_profile to strategy_profile, remove engine_profile
UPDATE public.price_check_attempts
SET applied_levers = applied_levers - 'engine_profile' || jsonb_build_object('strategy_profile', applied_levers->'engine_profile')
WHERE applied_levers IS NOT NULL AND applied_levers ? 'engine_profile';
