-- DashTrack Survey: Supabase schema. Paste into Supabase → SQL Editor → Run.

create table if not exists public.surveys (
  id           uuid primary key default gen_random_uuid(),
  run_name     text not null unique,          -- e.g. RUN_20261024_101500 (also the video file name)
  device       text,
  started_utc  timestamptz,
  duration_ms  bigint,
  length_m     double precision,
  n_fixes      int,
  n_tags       int,
  title        text,                           -- optional, editable on dashboard (e.g. "LBS Marg NB")
  track        jsonb,                          -- [[t_ms,lat,lon,speed_kmh,heading,acc_m,utc_ms,dist_m], ...]
  uploaded_by  uuid default auth.uid(),
  uploaded_at  timestamptz default now()
);

create table if not exists public.tags (
  id          bigserial primary key,
  survey_id   uuid not null references public.surveys(id) on delete cascade,
  t_ms        bigint,                          -- position in the video (ms)
  type        text,
  lat         double precision,
  lon         double precision,
  chainage_m  double precision,
  utc         timestamptz,
  note        text
);
create index if not exists tags_survey_idx on public.tags(survey_id);
create index if not exists tags_type_idx   on public.tags(type);

-- Only signed-in team members can read/write. Nobody else can see anything.
alter table public.surveys enable row level security;
alter table public.tags    enable row level security;

drop policy if exists "team all surveys" on public.surveys;
create policy "team all surveys" on public.surveys
  for all to authenticated using (true) with check (true);

drop policy if exists "team all tags" on public.tags;
create policy "team all tags" on public.tags
  for all to authenticated using (true) with check (true);
