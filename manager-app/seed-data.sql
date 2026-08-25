-- Seed data for the manager database (schema user_management).
-- Run manually, e.g.:
--   psql -h localhost -p 5433 -U manager -d manager -f manager-app/seed-data.sql

set search_path to user_management;

-- 1. Authority: ROLE_MANAGER
insert into t_authority (c_authority) values ('ROLE_MANAGER')
on conflict (c_authority) do nothing;

-- 2. User: j.dewar / password (plaintext via {noop})
insert into t_user (c_username, c_password) values ('j.dewar', '{noop}password')
on conflict (c_username) do nothing;

-- 3. Link user -> authority (id_user=1, id_authority=1)
insert into t_user_2_authority (id_user, id_authority) values (1, 1)
on conflict (id_user, id_authority) do nothing;
