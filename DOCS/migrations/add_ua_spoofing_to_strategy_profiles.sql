-- Sprint 15 Phase 2: Add ua_spoofing_active and persona_profile to stealth_max
-- Run in Supabase SQL editor. Requires persona_catalog to exist (run add_persona_catalog.sql first).

UPDATE public.strategy_profiles
SET definition = definition
  || '{"ua_spoofing_active": true, "persona_profile": "gecko_control"}'::jsonb
WHERE code = 'stealth_max';
