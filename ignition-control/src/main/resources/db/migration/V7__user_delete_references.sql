-- A deleted account must not prevent its zones or private-zone allow-lists
-- from being deleted/updated. The references are audit context, not ownership.
alter table zone drop constraint if exists zone_created_by_fkey;
alter table zone
    add constraint zone_created_by_fkey foreign key (created_by)
        references app_user(id) on delete set null;

alter table zone_viewer drop constraint if exists zone_viewer_added_by_fkey;
alter table zone_viewer
    add constraint zone_viewer_added_by_fkey foreign key (added_by)
        references app_user(id) on delete set null;
