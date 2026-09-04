-- Ignition infra state — replaces the state/*.env file tree.
-- Identity tables (app_user, auth_token, zone_member, zone_viewer) arrive in V2.

create table node (
    name        text primary key,
    docker_host text             not null,
    cpus        double precision not null,
    mem_gb      double precision not null,
    labels      text             not null default '',   -- comma-joined
    state       text             not null default 'ACTIVE',
    created_at  timestamptz      not null default now()
);

create table zone (
    slug          text             primary key,
    node_name     text             not null references node(name) on delete restrict,
    base_domain   text             not null,
    zone_cpus     double precision not null,
    zone_mem_gb   double precision not null,
    git_host      text             not null,
    zadmin_host   text             not null,
    forgejo_url   text             not null,
    zadmin_url    text             not null,
    apps_base     text             not null,
    visibility    text             not null default 'PUBLIC',
    last_activity timestamptz      not null default now(),
    created_at    timestamptz      not null default now()
);

create table zone_secret (
    zone_slug text not null references zone(slug) on delete cascade,
    name      text not null,
    value     text not null,   -- AES-GCM ciphertext, base64 (see SecretCipher)
    primary key (zone_slug, name)
);

create table app (
    zone_slug   text        not null references zone(slug) on delete cascade,
    name        text        not null,
    node_name   text        not null,
    image       text        not null,
    port        integer     not null,
    deploy_id   text        not null,
    deployed_at timestamptz not null default now(),
    primary key (zone_slug, name)
);

create table provisioning_status (
    zone_slug  text        primary key,
    state      text        not null,     -- RUNNING | DONE | FAILED
    message    text,
    updated_at timestamptz not null default now()
);
