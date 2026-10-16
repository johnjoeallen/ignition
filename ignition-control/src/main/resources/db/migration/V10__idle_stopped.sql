-- Who stopped it. idle_stopped = the idle sweep stopped it, so a browser hit on
-- its URL may wake it. A manual Stop (console button) leaves it false and the
-- app stays stopped until someone presses Start.
alter table app     add column idle_stopped boolean not null default false;
alter table app_dev add column idle_stopped boolean not null default false;
