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
  tier: number;
  definition: {
    amnesia_wipe_required?: boolean;
    strict_tracking_protection?: boolean;
    canvas_spoofing_active?: boolean;
    url_sanitize?: boolean;
  };
}

/** Tactic registry entry - only required_countermeasures used on critical path */
interface TacticRegistryEntry {
  required_countermeasures: {
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
  selection_mode: 'exploit' | 'explore';
  proxyConfig: null;
}

const LEVERS = ['amnesia_wipe_required', 'strict_tracking_protection', 'canvas_spoofing_active', 'url_sanitize'] as const;

/** Fallback when cache is empty (cold boot / Supabase unreachable) - Tier 2 for WAF protection */
const FALLBACK_AMNESIA_STANDARD: Omit<StrategyResponse, 'engineSelectionPolicy' | 'engineSelectionReason' | 'engineSelectionKeyScope' | 'engineSelectionBucket' | 'selection_mode'> = {
  strategy_id: null,
  strategy_code: 'amnesia_standard',
  amnesia_wipe_required: true,
  strict_tracking_protection: true,
  canvas_spoofing_active: false,
  url_sanitize: true,
  strategyName: 'Amnesia Standard',
  strategyEngineName: 'railway_brain_v2.0',
  strategyVersion: '2.0',
  wireguardConfig: '',
  strategy_profile: 'amnesia_standard',
  proxyConfig: null,
};

let cachedTacticRegistry: Record<string, TacticRegistryEntry> = {};
let cachedProfiles: StrategyProfileRow[] = [];
let supabase: SupabaseClient | null = null;

function getSupabase(): SupabaseClient | null {
  if (supabase !== null) return supabase;
  const url = process.env.SUPABASE_URL;
  const key = process.env.SUPABASE_SERVICE_ROLE_KEY ?? process.env.SUPABASE_ANON_KEY;
  if (!url || !key) return null;
  supabase = createClient(url, key);
  return supabase;
}

/** Background refresh - no DB calls on critical path */
async function refreshDataCaches(): Promise<void> {
  const client = getSupabase();
  if (!client) return;
  try {
    const [profilesRes, registryRes] = await Promise.all([
      client
        .from('strategy_profiles')
        .select('id, code, name, tier, definition')
        .eq('is_active', true)
        .order('tier', { ascending: true }),
      client
        .from('tactic_registry')
        .select('tactic_code, required_countermeasures')
        .eq('has_active_countermeasure', true),
    ]);

    if (!profilesRes.error) {
      cachedProfiles = (profilesRes.data ?? []).map((row: { id: string; code: string; name: string; tier?: number; definition: object }) => ({
        id: row.id,
        code: row.code,
        name: row.name,
        tier: typeof row.tier === 'number' ? row.tier : 0,
        definition: (row.definition as StrategyProfileRow['definition']) ?? {},
      }));
      console.log(`[FairPrice Brain] Cached ${cachedProfiles.length} strategy profiles`);
    } else {
      console.warn('[FairPrice Brain] strategy_profiles fetch failed:', profilesRes.error.message);
    }

    if (!registryRes.error) {
      const lookup: Record<string, TacticRegistryEntry> = {};
      for (const row of registryRes.data ?? []) {
        const code = (row as { tactic_code: string }).tactic_code;
        const req = (row as { required_countermeasures: object }).required_countermeasures ?? {};
        lookup[code] = { required_countermeasures: req as TacticRegistryEntry['required_countermeasures'] };
      }
      cachedTacticRegistry = lookup;
      console.log(`[FairPrice Brain] Cached ${Object.keys(cachedTacticRegistry).length} tactic registry entries`);
    } else {
      console.warn('[FairPrice Brain] tactic_registry fetch failed:', registryRes.error.message);
    }
  } catch (err) {
    console.warn('[FairPrice Brain] refreshDataCaches error:', err instanceof Error ? err.message : String(err));
  }
}

function mergeRequiredCountermeasures(detected_tactics: string[]): Record<string, boolean> {
  const merged: Record<string, boolean> = {
    amnesia_wipe_required: false,
    strict_tracking_protection: false,
    canvas_spoofing_active: false,
    url_sanitize: false,
  };
  for (const tactic of detected_tactics) {
    const entry = cachedTacticRegistry[tactic];
    if (!entry?.required_countermeasures) continue;
    for (const lever of LEVERS) {
      if (entry.required_countermeasures[lever] === true) {
        merged[lever] = true;
      }
    }
  }
  return merged;
}

/** Profile qualifies if it has at least all required levers. */
function profileQualifies(profile: StrategyProfileRow, required: Record<string, boolean>): boolean {
  const def = profile.definition ?? {};
  for (const lever of LEVERS) {
    if (required[lever] === true && !(def[lever as keyof typeof def])) return false;
  }
  return true;
}

function selectLowestQualifyingProfile(required: Record<string, boolean>): StrategyProfileRow | null {
  for (const profile of cachedProfiles) {
    if (profileQualifies(profile, required)) return profile;
  }
  return null;
}

function selectProfileByTier(tier: number): StrategyProfileRow | null {
  return cachedProfiles.find((p) => p.tier === tier) ?? cachedProfiles[0] ?? null;
}

function buildResponse(
  row: StrategyProfileRow | null,
  policy: string,
  reason: string,
  bucket: number,
  selectionMode: 'exploit' | 'explore',
): StrategyResponse {
  if (row === null) {
    return {
      ...FALLBACK_AMNESIA_STANDARD,
      engineSelectionPolicy: policy,
      engineSelectionReason: reason,
      engineSelectionKeyScope: 'domain+anonymous_bucket',
      engineSelectionBucket: bucket,
      selection_mode: selectionMode,
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
    strategyEngineName: 'railway_brain_v2.0',
    strategyVersion: '2.0',
    wireguardConfig: '',
    strategy_profile: row.code,
    engineSelectionPolicy: policy,
    engineSelectionReason: reason,
    engineSelectionKeyScope: 'domain+anonymous_bucket',
    engineSelectionBucket: bucket,
    selection_mode: selectionMode,
    proxyConfig: null,
  };
}

/** Fully synchronous - zero DB calls on critical path */
app.post('/api/v1/strategy', (req: Request, res: Response) => {
  const body = req.body as StrategyRequest;
  const domain = typeof body?.domain === 'string' ? body.domain.trim().toLowerCase() : 'unknown-domain';
  const detected_tactics = Array.isArray(body?.detected_tactics)
    ? body.detected_tactics.map((t) => String(t))
    : [];
  const anonymous_bucket = typeof body?.anonymous_bucket === 'number'
    ? Math.floor(Math.max(0, Math.min(99, body.anonymous_bucket)))
    : 0;

  let policy = 'railway_tiered_v1_epsilon_greedy_exploit';
  let reason = `bucket=${anonymous_bucket} domain=${domain}`;
  let selectionMode: 'exploit' | 'explore' = 'exploit';
  let row: StrategyProfileRow | null = null;

  if (cachedProfiles.length === 0) {
    row = null;
    policy = 'railway_tiered_v1_cold_boot_fallback';
    reason += ' cache_empty=amnesia_standard';
  } else {
    const isExplore = anonymous_bucket >= 75;
    if (isExplore) {
      selectionMode = 'explore';
      policy = 'railway_tiered_v1_epsilon_greedy_explore';
      const randomTier = anonymous_bucket % 4;
      row = selectProfileByTier(randomTier);
      reason += ` explore_tier=${randomTier}`;
    } else {
      selectionMode = 'exploit';
      const required = mergeRequiredCountermeasures(detected_tactics);
      row = selectLowestQualifyingProfile(required);
      if (row) {
        reason += ` tactics=[${detected_tactics.join(',')}] tier=${row.tier}`;
      } else {
        row = selectProfileByTier(0);
        reason += ` no_qualify fallback_tier_0`;
      }
    }
  }

  const response = buildResponse(row, policy, reason, anonymous_bucket, selectionMode);
  res.status(200).json(response);
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log(`[FairPrice Brain] Active on port ${PORT}`);
  refreshDataCaches().then(() => {
    setInterval(refreshDataCaches, 60000);
  });
});
