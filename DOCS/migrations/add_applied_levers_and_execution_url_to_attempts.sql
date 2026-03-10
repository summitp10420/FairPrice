-- Add applied_levers and execution_url to price_check_attempts so app logging succeeds.
-- Run this in Supabase SQL editor if you get insert errors on price_check_attempts.

ALTER TABLE public.price_check_attempts
    ADD COLUMN IF NOT EXISTS applied_levers jsonb,
    ADD COLUMN IF NOT EXISTS execution_url text;
