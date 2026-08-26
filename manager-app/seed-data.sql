.-- Seed data for the manager database (schema user_management).
-- Run manually, e.g.:
--   psql -h localhost -p 5433 -U manager -d manager -f manager-app/seed-data.sql

set search_path to user_management;

-- 1. Authority: ROLE_MANAGER
insert into user_management.t_authority (c_authority) values ('ROLE_MANAGER')
on conflict (c_authority) do nothing;

-- 2. User: j.dewar / password (plaintext via {noop})
insert into user_management.t_user (c_username, c_password) values ('j.dewar', '{bcrypt}$2a$10$xIz/y5.Ryqt0vkPQCZFiTuoW754shKkaQQxWmMaMpeSF0GuFEL4n6')
on conflict (c_username) do nothing;

-- 3. Link user -> authority (id_user=1, id_authority=1)
insert into user_management.t_user_2_authority (id_user, id_authority) values (1, 1)
on conflict (id_user, id_authority) do nothing;
