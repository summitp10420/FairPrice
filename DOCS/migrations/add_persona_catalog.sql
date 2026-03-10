-- Sprint 15 Phase 2: Persona catalog for UA spoofing (The Camouflage & Control Test)
-- Run in Supabase SQL editor.

CREATE TABLE IF NOT EXISTS public.persona_catalog (
  code text PRIMARY KEY,
  user_agent text NOT NULL,
  name text,
  is_active boolean DEFAULT true,
  created_at timestamptz DEFAULT timezone('utc', now())
);

-- Seed gecko_control with a representative GeckoView/Firefox Android UA.
-- Update this string if needed to match actual GeckoView output.
INSERT INTO public.persona_catalog (code, user_agent, name, is_active)
VALUES (
  'gecko_control',
  'Mozilla/5.0 (Android 13; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0',
  'Gecko Control (baseline GeckoView)',
  true
)
ON CONFLICT (code) DO UPDATE SET
  user_agent = EXCLUDED.user_agent,
  name = EXCLUDED.name,
  is_active = EXCLUDED.is_active;
