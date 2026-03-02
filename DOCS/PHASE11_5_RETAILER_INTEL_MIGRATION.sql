-- Phase 11.5 retailer intelligence schema hardening.
-- This migration is safe to run multiple times.

create table if not exists public.retailers (
    domain text primary key,
    active_tracking boolean not null default true,
    first_seen_at timestamptz not null default timezone('utc', now()),
    last_seen_at timestamptz not null default timezone('utc', now()),
    created_at timestamptz not null default timezone('utc', now())
);

create table if not exists public.retailer_strategies (
    id uuid primary key default gen_random_uuid(),
    retailer_domain text not null references public.retailers(domain) on delete cascade,
    tactic text not null,
    source_phase text not null default 'baseline',
    observed_at timestamptz not null default timezone('utc', now()),
    created_at timestamptz not null default timezone('utc', now())
);

create index if not exists idx_retailer_strategies_domain_observed_at
    on public.retailer_strategies (retailer_domain, observed_at desc);

create index if not exists idx_retailer_strategies_tactic_observed_at
    on public.retailer_strategies (tactic, observed_at desc);

-- Optional view for quick analytics dashboards.
create or replace view public.retailer_tactic_rollup as
select
    retailer_domain as domain,
    tactic,
    count(*) as observations,
    min(observed_at) as first_seen_at,
    max(observed_at) as last_seen_at
from public.retailer_strategies
group by retailer_domain, tactic;
