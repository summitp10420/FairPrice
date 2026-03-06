import express, { Request, Response } from 'express';

const app = express();
app.use(express.json());

/** Inbound payload from Android client */
interface StrategyRequest {
  domain: string;
  detected_tactics: string[];
  anonymous_bucket: number; // 0-99
}

/** Outbound payload - maps 1:1 with Android StrategyResult (snake_case keys for JSON) */
interface StrategyResponse {
  strategyId: string | null;
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

/** Profile codes the engine can return. Add new codes here (or in DB later) and the app will use behavior mapping / default. */
const STRATEGY_PROFILE_CODES = ['yale_smart', 'clean_control_v1'] as const;
type StrategyProfileCode = (typeof STRATEGY_PROFILE_CODES)[number];

const STRICT_WAF_TACTICS = [
  'vendor_datadome',
  'vendor_perimeterx',
  'block_datadome',
  'hardware_fingerprinting',
];

app.post('/api/v1/strategy', (req: Request, res: Response) => {
  const body = req.body as StrategyRequest;
  const domain = typeof body?.domain === 'string' ? body.domain.trim().toLowerCase() : 'unknown-domain';
  const detected_tactics = Array.isArray(body?.detected_tactics)
    ? body.detected_tactics.map((t) => String(t))
    : [];
  const anonymous_bucket = typeof body?.anonymous_bucket === 'number'
    ? Math.floor(Math.max(0, Math.min(99, body.anonymous_bucket)))
    : 0;

  const requiresStrictAmnesia = STRICT_WAF_TACTICS.some((t) => detected_tactics.includes(t));

  let strategyProfile: StrategyProfileCode = 'clean_control_v1';
  let policy = 'railway_dynamic_v1_50_50';
  let reason = `bucket=${anonymous_bucket} domain=${domain}`;

  if (requiresStrictAmnesia) {
    strategyProfile = 'yale_smart';
    policy = 'railway_dynamic_v1_waf_override';
    reason += ' waf_override=true';
  } else if (anonymous_bucket < 50) {
    strategyProfile = 'yale_smart';
  }

  const response: StrategyResponse = {
    strategyId: 'strat_railway_alpha',
    strategyName: policy,
    strategyEngineName: 'railway_brain_v1.0',
    strategyVersion: '1.0',
    wireguardConfig: '',
    strategy_profile: strategyProfile,
    engineSelectionPolicy: policy,
    engineSelectionReason: reason,
    engineSelectionKeyScope: 'domain+anonymous_bucket',
    engineSelectionBucket: anonymous_bucket,
    proxyConfig: null,
  };

  res.status(200).json(response);
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log(`[FairPrice Brain] Active on port ${PORT}`);
});
