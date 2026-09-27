-- Manueel uit te voeren tegen de chb-frontend-database (geen Flyway hier, zelfde conventie als de
-- andere bestanden in db/) — vóór je een build met Spring Session JDBC ingeschakeld
-- (spring.session.store-type=jdbc, zie application.properties) uitrolt.
--
-- Doel: HTTP-sessies (Entra ID-login) overleven een app-herstart/deploy. Zonder dit zit de
-- sessie enkel in JVM-geheugen en verliest iedereen zijn login bij elke herstart — de
-- "continuous logouts from Office365" die dit oplost.
--
-- Schema chb (niet public), zodat dit netjes naast de rest van het datamodel staat. Namen in
-- kleine letters, in lijn met de rest van chb.* (afwijkend van Spring Session's eigen
-- referentieschema, dat hoofdletters gebruikt — Postgres vouwt ongequote identifiers toch plat
-- naar kleine letters, dus dit is functioneel identiek). Gebaseerd op Spring Session's officiële
-- org/springframework/session/jdbc/schema-postgresql.sql (versie voor Spring Session 3.x).
--
-- app.datasource.editor.* (chb_editor-rol) is de pool die Spring Session gebruikt (zie
-- DataSourceConfig, @SpringSessionDataSource) — chb_editor heeft dus INSERT/UPDATE/DELETE/SELECT
-- nodig op deze twee tabellen. chb_viewer raakt deze tabellen nooit en krijgt hier bewust geen
-- rechten op.

CREATE TABLE IF NOT EXISTS chb.spring_session (
    primary_id            CHAR(36) NOT NULL,
    session_id            CHAR(36) NOT NULL,
    creation_time         BIGINT NOT NULL,
    last_access_time      BIGINT NOT NULL,
    max_inactive_interval INT NOT NULL,
    expiry_time           BIGINT NOT NULL,
    principal_name        VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS spring_session_ix1 ON chb.spring_session (session_id);
CREATE INDEX IF NOT EXISTS spring_session_ix2 ON chb.spring_session (expiry_time);
CREATE INDEX IF NOT EXISTS spring_session_ix3 ON chb.spring_session (principal_name);

CREATE TABLE IF NOT EXISTS chb.spring_session_attributes (
    session_primary_id CHAR(36) NOT NULL,
    attribute_name      VARCHAR(200) NOT NULL,
    attribute_bytes     BYTEA NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES chb.spring_session (primary_id) ON DELETE CASCADE
);

GRANT SELECT, INSERT, UPDATE, DELETE ON chb.spring_session, chb.spring_session_attributes TO chb_editor;
