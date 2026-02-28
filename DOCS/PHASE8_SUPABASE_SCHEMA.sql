-- Phase 8: VPN Rotation v1 + Training-Grade Telemetry
-- Apply these statements in Supabase SQL editor.

alter table public.price_checks
    add column if not exists strategy_name text,
    add column if not exists attempted_configs text[] default '{}'::text[],
    add column if not exists final_config text,
    add column if not exists retry_count integer not null default 0,
    add column if not exists outcome text,
    add column if not exists degraded boolean not null default false,
    add column if not exists baseline_success boolean not null default false,
    add column if not exists spoof_success boolean not null default false,
    add column if not exists dirty_baseline_price_cents integer;

create table if not exists public.price_check_attempts (
    id uuid primary key default gen_random_uuid(),
    price_check_id uuid null references public.price_checks(id) on delete set null,
    phase text not null,
    attempt_index integer not null,
    vpn_config text,
    success boolean not null,
    error_type text,
    error_message text,
    extracted_price_cents integer,
    detected_tactics text[] default '{}'::text[],
    debug_extraction_path text,
    latency_ms bigint,
    created_at timestamptz not null default now()
);

create index if not exists idx_price_check_attempts_price_check_id
    on public.price_check_attempts (price_check_id);

create index if not exists idx_price_check_attempts_phase
    on public.price_check_attempts (phase);
