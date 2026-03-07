-- Sprint 14: Tactic Registry & Dynamic Rules Engine
-- Run in Supabase SQL editor. Adds tier column, tactic_registry, selection_mode, analytics view.

-- =============================================================================
-- 1. Strategy Profiles - Add Tier Column and Update/Rename
-- =============================================================================

ALTER TABLE public.strategy_profiles ADD COLUMN IF NOT EXISTS tier integer DEFAULT 0;

-- Rename legacy -> clean_baseline, set tier 0
UPDATE public.strategy_profiles
SET code = 'clean_baseline', name = 'Clean Baseline', tier = 0, description = 'Least aggressive: no countermeasures. Equivalent to incognito browser load.'
WHERE code = 'legacy';

-- Rename yale_smart -> stealth_max, set tier 3
UPDATE public.strategy_profiles
SET code = 'stealth_max', name = 'Stealth Max', tier = 3, description = 'Maximum countermeasures: amnesia + tracking + canvas spoofing + URL sanitize.'
WHERE code = 'yale_smart';

-- Insert new tier 1 and 2 profiles
INSERT INTO public.strategy_profiles (code, name, description, tier, definition)
VALUES (
  'shield_basic',
  'Shield Basic',
  'Strict tracking protection + URL sanitization.',
  1,
  '{"amnesia_wipe_required": false, "strict_tracking_protection": true, "canvas_spoofing_active": false, "url_sanitize": true}'::jsonb
),
(
  'amnesia_standard',
  'Amnesia Standard',
  'Shield Basic + full session amnesia wipe.',
  2,
  '{"amnesia_wipe_required": true, "strict_tracking_protection": true, "canvas_spoofing_active": false, "url_sanitize": true}'::jsonb
)
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  tier = EXCLUDED.tier,
  definition = EXCLUDED.definition;

-- Ensure clean_baseline has tier 0 (in case legacy did not exist)
UPDATE public.strategy_profiles SET tier = 0 WHERE code = 'clean_baseline';
-- Ensure stealth_max has tier 3
UPDATE public.strategy_profiles SET tier = 3 WHERE code = 'stealth_max';

-- =============================================================================
-- 2. Tactic Registry Table
-- =============================================================================

CREATE TABLE IF NOT EXISTS public.tactic_registry (
  tactic_code text PRIMARY KEY,
  category text,
  description text,
  severity_level integer DEFAULT 1,
  has_active_countermeasure boolean DEFAULT false,
  required_countermeasures jsonb DEFAULT '{}',
  created_at timestamptz DEFAULT timezone('utc', now())
);

-- =============================================================================
-- 3. Tactic Registry Seed Data
-- =============================================================================

INSERT INTO public.tactic_registry (tactic_code, category, description, severity_level, has_active_countermeasure, required_countermeasures) VALUES
  -- WAF presence (vendor_*)
  ('vendor_perimeterx', 'waf', 'PerimeterX WAF vendor', 4, true, '{"strict_tracking_protection": true, "url_sanitize": true}'::jsonb),
  ('vendor_datadome', 'waf', 'DataDome WAF vendor', 5, true, '{"strict_tracking_protection": true, "url_sanitize": true}'::jsonb),
  ('vendor_akamai', 'waf', 'Akamai Bot Manager', 4, true, '{"strict_tracking_protection": true, "url_sanitize": true}'::jsonb),
  ('vendor_cloudflare', 'waf', 'Cloudflare', 3, true, '{"strict_tracking_protection": true, "url_sanitize": true}'::jsonb),
  -- WAF block (block_*)
  ('block_perimeterx', 'waf', 'PerimeterX block page', 5, true, '{"amnesia_wipe_required": true, "strict_tracking_protection": true, "canvas_spoofing_active": true, "url_sanitize": true}'::jsonb),
  ('block_datadome', 'waf', 'DataDome block page', 5, true, '{"amnesia_wipe_required": true, "strict_tracking_protection": true, "canvas_spoofing_active": true, "url_sanitize": true}'::jsonb),
  ('block_akamai', 'waf', 'Akamai block page', 5, true, '{"amnesia_wipe_required": true, "strict_tracking_protection": true, "canvas_spoofing_active": true, "url_sanitize": true}'::jsonb),
  ('block_cloudflare', 'waf', 'Cloudflare challenge', 4, true, '{"amnesia_wipe_required": true, "strict_tracking_protection": true, "canvas_spoofing_active": true, "url_sanitize": true}'::jsonb),
  -- Persistence
  ('cookie_tracking', 'persistence', 'Cookie/supercookie persistence', 2, true, '{"strict_tracking_protection": true, "url_sanitize": true}'::jsonb),
  ('hsts_supercookie', 'persistence', 'HSTS supercookie', 4, true, '{"amnesia_wipe_required": true, "strict_tracking_protection": true, "url_sanitize": true}'::jsonb),
  ('tls_session_resumption', 'persistence', 'TLS session resumption tracking', 4, true, '{"amnesia_wipe_required": true, "strict_tracking_protection": true, "url_sanitize": true}'::jsonb),
  ('etag_cache_tracking', 'persistence', 'ETag/cache respawning', 4, true, '{"amnesia_wipe_required": true, "strict_tracking_protection": true, "url_sanitize": true}'::jsonb),
  -- Hardware fingerprinting
  ('hidden_canvas', 'hardware', 'Hidden canvas fingerprinting', 3, true, '{"canvas_spoofing_active": true}'::jsonb),
  ('hardware_fingerprinting', 'hardware', 'Canvas/WebGL hardware fingerprinting', 4, true, '{"amnesia_wipe_required": true, "strict_tracking_protection": true, "canvas_spoofing_active": true}'::jsonb),
  ('webgl_hardware_snapshot', 'hardware', 'WebGL hardware snapshot', 4, true, '{"amnesia_wipe_required": true, "strict_tracking_protection": true, "canvas_spoofing_active": true}'::jsonb),
  ('audio_context_fingerprinting', 'hardware', 'AudioContext fingerprinting', 3, false, '{}'::jsonb),
  -- Behavioral / contextual
  ('surveillance_active', 'behavioral', 'Surveillance/script monitoring', 2, true, '{"strict_tracking_protection": true}'::jsonb),
  ('behavioral_biometrics', 'behavioral', 'Keystroke/mouse biometrics', 3, false, '{}'::jsonb),
  ('battery_status_sniffing', 'behavioral', 'Battery Status API', 2, false, '{}'::jsonb),
  ('url_campaign_tracking', 'contextual', 'URL campaign parameters', 2, true, '{"url_sanitize": true}'::jsonb),
  -- Identity (future)
  ('user_agent_profiling', 'identity', 'User-Agent profiling', 3, false, '{"ua_spoofing_active": true}'::jsonb),
  ('geolocation_profiling', 'identity', 'IP/ZIP geolocation', 4, false, '{"proxyConfig": {}}'::jsonb)
ON CONFLICT (tactic_code) DO UPDATE SET
  category = EXCLUDED.category,
  description = EXCLUDED.description,
  severity_level = EXCLUDED.severity_level,
  has_active_countermeasure = EXCLUDED.has_active_countermeasure,
  required_countermeasures = EXCLUDED.required_countermeasures;

-- =============================================================================
-- 4. Price Checks - selection_mode Column (exploit/explore for epsilon-greedy)
-- =============================================================================

ALTER TABLE public.price_checks ADD COLUMN IF NOT EXISTS selection_mode text;

-- =============================================================================
-- 5. Analytics View - strategy_win_rates
-- Success = found_price_cents < dirty_baseline_price_cents (we beat user-entered price)
-- =============================================================================

CREATE OR REPLACE VIEW public.strategy_win_rates AS
SELECT
  domain,
  strategy_name,
  COUNT(*) AS total_runs,
  COUNT(*) FILTER (
    WHERE dirty_baseline_price_cents IS NOT NULL
      AND found_price_cents IS NOT NULL
      AND found_price_cents < dirty_baseline_price_cents
  ) AS successful_runs,
  ROUND(
    100.0 * COUNT(*) FILTER (
      WHERE dirty_baseline_price_cents IS NOT NULL
        AND found_price_cents IS NOT NULL
        AND found_price_cents < dirty_baseline_price_cents
    ) / NULLIF(COUNT(*), 0),
    2
  ) AS win_rate_percentage,
  ROUND(
    AVG(dirty_baseline_price_cents - found_price_cents) FILTER (
      WHERE dirty_baseline_price_cents IS NOT NULL
        AND found_price_cents IS NOT NULL
        AND found_price_cents < dirty_baseline_price_cents
    ),
    0
  )::integer AS avg_savings_cents
FROM public.price_checks
WHERE degraded = false
GROUP BY domain, strategy_name
HAVING COUNT(*) > 3;
