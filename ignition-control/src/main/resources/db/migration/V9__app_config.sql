-- Per-app settings that exist whether or not the app is deployed (V8 put the
-- idle TTL on the deployment rows, so it couldn't be set before a release).
-- One row per (zone, app name), applies to the release and its dev deployment.
-- idle_ttl_minutes: null = platform default, 0 = never stop, else minutes.
create table app_config (
    zone_slug        text    not null references zone(slug) on delete cascade,
    name             text    not null,
    idle_ttl_minutes integer,
    primary key (zone_slug, name)
);

insert into app_config (zone_slug, name, idle_ttl_minutes)
select zone_slug, name, idle_ttl_minutes from app where idle_ttl_minutes is not null;
insert into app_config (zone_slug, name, idle_ttl_minutes)
select zone_slug, name, idle_ttl_minutes from app_dev where idle_ttl_minutes is not null
on conflict (zone_slug, name) do nothing;

alter table app     drop column idle_ttl_minutes;
alter table app_dev drop column idle_ttl_minutes;
