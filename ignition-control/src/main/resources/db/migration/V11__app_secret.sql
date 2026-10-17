-- Write-only runtime secrets per app (zone + repo name) and environment, set
-- from the app page by a team admin and merged into the web container's
-- environment at deploy time (they win over the repo's .env). scope is which
-- deployment gets them: 'release' (a tagged release on .apps.), 'dev' (the
-- deploy-from-main host) or 'preview' (every PR preview of the repo). Values
-- are AES-GCM ciphertext (SecretCipher); the console only ever lists names.
create table app_secret (
    zone_slug  text not null references zone(slug) on delete cascade,
    app        text not null,
    scope      text not null check (scope in ('release', 'dev', 'preview')),
    name       text not null,
    value      text not null,
    updated_at timestamp with time zone not null default now(),
    updated_by text,
    primary key (zone_slug, app, scope, name)
);

-- When an environment's secrets last changed (a save or a delete), so the page
-- can tell whether its running deployment predates the change and offer a restart.
create table app_secret_change (
    zone_slug  text not null references zone(slug) on delete cascade,
    app        text not null,
    scope      text not null check (scope in ('release', 'dev', 'preview')),
    changed_at timestamp with time zone not null default now(),
    primary key (zone_slug, app, scope)
);
