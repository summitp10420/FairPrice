-- Sprint 15 Phase 3a: Add proxy_routing_active and target_income_tier to stealth_max
-- Run after add_proxy_location_catalog.sql.

UPDATE public.strategy_profiles
SET definition = definition
  || '{"proxy_routing_active": true, "target_income_tier": "low"}'::jsonb
WHERE code = 'stealth_max';
