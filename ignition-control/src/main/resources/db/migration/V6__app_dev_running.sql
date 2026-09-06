-- Same as V5 for the release channel: the repo page's dev deployment gets
-- Stop / Start (`docker compose stop` / `start`, stack and volumes kept, row
-- kept) alongside Delete (`down -v` + row delete). `running` tracks the state.
alter table app_dev add column running boolean not null default true;
