-- Drops the per-zone "admin.<slug>.<BASE_DOMAIN>" console hostname. It routed
-- to the same ignition-control backend as the platform console and carried no
-- access-control meaning of its own (that's session/role-based now) — every
-- team's console is reached at /z?z=<slug> on the one console host instead.
alter table zone drop column zadmin_host;
alter table zone drop column zadmin_url;
