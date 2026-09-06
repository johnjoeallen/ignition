-- "Stop" an app without tearing it down. Before this, the console's Stop button
-- ran `docker compose down -v` and deleted the `app` row — the only way back was
-- to cut another release. Stop/Start now map to `docker compose stop` / `start`:
-- the containers, networks and volumes stay, the row stays, and `running` tracks
-- which state it's in. Full teardown (`down -v` + row delete) is now only Delete
-- and zone destroy. Existing rows are live, so default true.
alter table app add column running boolean not null default true;
