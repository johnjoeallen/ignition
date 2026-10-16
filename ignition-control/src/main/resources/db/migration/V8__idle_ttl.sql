-- Per-app idle TTL. idle_ttl_minutes: null = the platform default
-- (ignition.sweep.ttl), 0 = never stop, otherwise minutes of no traffic
-- before the idle sweep stops the app. active_since is when the app last
-- (re)started — deploy or Start — so a fresh start is never judged on old traffic.
alter table app     add column idle_ttl_minutes integer;
alter table app     add column active_since timestamp with time zone not null default now();
alter table app_dev add column idle_ttl_minutes integer;
alter table app_dev add column active_since timestamp with time zone not null default now();

-- Last time Traefik's access log showed a (non-probe) request for a compose
-- project. Persisted so a controller restart doesn't forget it.
create table app_traffic (
    project         varchar(200) primary key,
    last_traffic_at timestamp with time zone not null
);
