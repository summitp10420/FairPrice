import express, { Request, Response } from 'express';
import { createClient, SupabaseClient } from '@supabase/supabase-js';

const app = express();
app.use(express.json());

/** Inbound payload from Android client */
interface StrategyRequest {
  domain: string;
  detected_tactics: string[];
  anonymous_bucket: number; // 0-99
}

/** Row from strategy_profiles */
interface StrategyProfileRow {
  id: string;
  code: string;
  name: string;
  definition: {
    amnesia_wipe_required?: boolean;
    strict_tracking_protection?: boolean;
    canvas_spoofing_active?: boolean;
    url_sanitize?: boolean;
  };
}

/** Outbound payload - flat definition + telemetry. Maps to Android StrategyResult (snake_case keys). */
interface StrategyResponse {
  strategy_id: string | null;
  strategy_code: string;
  amnesia_wipe_required: boolean;
  strict_tracking_protection: boolean;
  canvas_spoofing_active: boolean;
  url_sanitize: boolean;
  strategyName: string;
  strategyEngineName: string;
  strategyVersion: string;
  wireguardConfig: string;
  strategy_profile: string;
  engineSelectionPolicy: string;
  engineSelectionReason: string;
  engineSelectionKeyScope: string;
  engineSelectionBucket: number;
  proxyConfig: null;
}

const STRICT_WAF_TACTICS = [
  'vendor_datadome',
  'vendor_perimeterx',
  'block_datadome',
  'hardware_fingerprinting',
];

/** Fallback legacy definition when Supabase is unreachable */
const FALLBACK_LEGACY: Omit<StrategyResponse, 'engineSelectionPolicy' | 'engineSelectionReason' | 'engineSelectionKeyScope' | 'engineSelectionBucket'> = {
  strategy_id: null,
  strategy_code: 'legacy',
  amnesia_wipe_required: false,
  strict_tracking_protection: false,
  canvas_spoofing_active: false,
  url_sanitize: false,
  strategyName: 'fallback_legacy',
  strategyEngineName: 'railway_brain_v1.0',
  strategyVersion: '1.0',
  wireguardConfig: '',
  strategy_profile: 'legacy',
  proxyConfig: null,
};

let cachedProfiles: StrategyProfileRow[] | null = null;
let supabase: SupabaseClient | null = null;

function getSupabase(): SupabaseClient | null {
  if (supabase !== null) return supabase;
  const url = process.env.SUPABASE_URL;
  const key = process.env.SUPABASE_SERVICE_ROLE_KEY ?? process.env.SUPABASE_ANON_KEY;
  if (!url || !key) return null;
  supabase = createClient(url, key);
  return supabase;
}

async function fetchStrategyProfiles(): Promise<StrategyProfileRow[]> {
  if (cachedProfiles !== null) return cachedProfiles;
  const client = getSupabase();
  if (!client) return [];
  const { data, error } = await client
    .from('strategy_profiles')
    .select('id, code, name, definition')
    .eq('is_active', true);
  if (error) {
    console.warn('[FairPrice Brain] Supabase strategy_profiles fetch failed:', error.message);
    return [];
  }
  cachedProfiles = (data ?? []).map((row: { id: string; code: string; name: string; definition: object }) => ({
    id: row.id,
    code: row.code,
    name: row.name,
    definition: (row.definition as StrategyProfileRow['definition']) ?? {},
  }));
  return cachedProfiles;
}

function buildResponse(
  row: StrategyProfileRow | null,
  policy: string,
  reason: string,
  bucket: number,
): StrategyResponse {
  if (row === null) {
    return {
      ...FALLBACK_LEGACY,
      engineSelectionPolicy: policy,
      engineSelectionReason: reason,
      engineSelectionKeyScope: 'domain+anonymous_bucket',
      engineSelectionBucket: bucket,
    };
  }
  const def = row.definition ?? {};
  return {
    strategy_id: row.id,
    strategy_code: row.code,
    amnesia_wipe_required: def.amnesia_wipe_required ?? false,
    strict_tracking_protection: def.strict_tracking_protection ?? false,
    canvas_spoofing_active: def.canvas_spoofing_active ?? false,
    url_sanitize: def.url_sanitize ?? false,
    strategyName: row.name,
    strategyEngineName: 'railway_brain_v1.0',
    strategyVersion: '1.0',
    wireguardConfig: '',
    strategy_profile: row.code,
    engineSelectionPolicy: policy,
    engineSelectionReason: reason,
    engineSelectionKeyScope: 'domain+anonymous_bucket',
    engineSelectionBucket: bucket,
    proxyConfig: null,
  };
}

app.post('/api/v1/strategy', async (req: Request, res: Response) => {
  const body = req.body as StrategyRequest;
  const domain = typeof body?.domain === 'string' ? body.domain.trim().toLowerCase() : 'unknown-domain';
  const detected_tactics = Array.isArray(body?.detected_tactics)
    ? body.detected_tactics.map((t) => String(t))
    : [];
  const anonymous_bucket = typeof body?.anonymous_bucket === 'number'
    ? Math.floor(Math.max(0, Math.min(99, body.anonymous_bucket)))
    : 0;

  const requiresStrictAmnesia = STRICT_WAF_TACTICS.some((t) => detected_tactics.includes(t));

  let selectedCode: 'legacy' | 'yale_smart' = 'legacy';
  let policy = 'railway_dynamic_v1_50_50';
  let reason = `bucket=${anonymous_bucket} domain=${domain}`;

  if (requiresStrictAmnesia) {
    selectedCode = 'yale_smart';
    policy = 'railway_dynamic_v1_waf_override';
    reason += ' waf_override=true';
  } else if (anonymous_bucket < 50) {
    selectedCode = 'yale_smart';
  }

  const profiles = await fetchStrategyProfiles();
  const row = profiles.find((p) => p.code === selectedCode) ?? null;

  const response = buildResponse(row, policy, reason, anonymous_bucket);
  res.status(200).json(response);
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log(`[FairPrice Brain] Active on port ${PORT}`);
});
