-- Runs once, on first initialization of the postgres data volume (scripts in
-- /docker-entrypoint-initdb.d are only executed when the data dir is empty).
--
-- Keycloak manages its own schema, so it gets its own database + least-
-- privilege role inside the shared Postgres instance rather than sharing the
-- application's `aismartstore` database. If the volume already exists this
-- script does NOT re-run; recreate with `docker compose down -v` to re-init.
CREATE ROLE keycloak WITH LOGIN PASSWORD 'keycloak';
CREATE DATABASE keycloak OWNER keycloak;
GRANT ALL PRIVILEGES ON DATABASE keycloak TO keycloak;
