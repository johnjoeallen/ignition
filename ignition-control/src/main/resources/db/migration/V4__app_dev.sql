-- A per-app "dev" deployment: the latest `main` HEAD, built and shipped only
-- when someone clicks "Deploy from main" in the team console (never on a plain
-- push). Lives at <name>.dev.<slug>.<BASE_DOMAIN>, alongside the release at
-- <name>.apps.<slug>.<BASE_DOMAIN>. It's a separate table, not columns on
-- `app`, because a dev deployment can exist before the app's first release
-- (and vice versa). Torn down with the app and with the zone.
create table app_dev (
    zone_slug   text        not null references zone(slug) on delete cascade,
    name        text        not null,
    node_name   text        not null,
    image       text        not null,
    port        integer     not null,
    deploy_id   text        not null,
    deployed_at timestamptz not null default now(),
    primary key (zone_slug, name)
);
