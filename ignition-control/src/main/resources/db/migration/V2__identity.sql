-- Accounts, activation tokens, and zone membership.

create extension if not exists citext;

create table app_user (
    id                uuid        primary key,
    email             citext      unique not null,
    password_hash     text,                          -- null until activated
    status            text        not null,          -- PENDING_VERIFICATION | PENDING_APPROVAL | ACTIVE | DISABLED
    is_platform_admin boolean     not null default false,
    preapproved       boolean     not null default false,   -- admin-invited: activation goes straight to ACTIVE
    created_at        timestamptz not null default now(),
    activated_at      timestamptz
);

create table auth_token (
    token_hash text        primary key,              -- sha-256 hex of the raw token; raw only in the email
    user_id    uuid        not null references app_user(id) on delete cascade,
    purpose    text        not null,                 -- ACTIVATE | RESET
    expires_at timestamptz not null,
    used_at    timestamptz,
    created_at timestamptz not null default now()
);
create index auth_token_user_purpose on auth_token (user_id, purpose);

create table zone_member (
    zone_slug text        not null references zone(slug) on delete cascade,
    user_id   uuid        not null references app_user(id) on delete cascade,
    role      text        not null,                  -- MEMBER | ZONE_ADMIN
    added_at  timestamptz not null default now(),
    primary key (zone_slug, user_id)
);
create index zone_member_user on zone_member (user_id);

create table zone_viewer (
    zone_slug text        not null references zone(slug) on delete cascade,
    email     citext      not null,
    added_by  uuid        references app_user(id),
    added_at  timestamptz not null default now(),
    primary key (zone_slug, email)
);

alter table zone add column created_by uuid references app_user(id);
