-- Sprint 15 Phase 3a: Proxy location catalog and geolocation tactic update
-- Run in Supabase SQL editor.

CREATE TABLE IF NOT EXISTS public.proxy_location_catalog (
  zip_code text PRIMARY KEY,
  metro_area text NOT NULL,
  income_tier text NOT NULL,
  is_active boolean DEFAULT true,
  created_at timestamptz DEFAULT timezone('utc', now())
);

-- Seed with Salt Lake City metro test data
INSERT INTO public.proxy_location_catalog (zip_code, metro_area, income_tier, is_active)
VALUES
  ('84103', 'SLC', 'high', true),  -- Avenues/Capitol Hill
  ('84060', 'SLC', 'high', true),  -- Park City
  ('84119', 'SLC', 'low', true),   -- West Valley City
  ('84116', 'SLC', 'low', true)    -- Rose Park
ON CONFLICT (zip_code) DO UPDATE SET
  metro_area = EXCLUDED.metro_area,
  income_tier = EXCLUDED.income_tier,
  is_active = EXCLUDED.is_active;

-- Update legacy geolocation tactic to use proxy_routing_active lever
UPDATE public.tactic_registry
SET required_countermeasures = '{"proxy_routing_active": true}'::jsonb
WHERE tactic_code = 'geolocation_profiling';
