-- Toe te voegen als een nieuwe Flyway-migratie in de gtfs-import-repo (volgende versienummer na
-- de laatste daar) — net als merge_stops_security_definer.sql is dit een wijziging aan het
-- gedeelde datamodel, niet aan de chb-frontend applicatie zelf.
--
-- Doel: de kaart in chb-frontend laadt momenteel bij elke actie de volledige ~50K-rijen
-- chb.stop op, wat >1s duurt. Dat wordt vervangen door een bbox-query o.b.v. het huidige
-- kaartviewport (zie StopViewerRepository/PublicStopController in chb-frontend). Een gewone
-- WHERE lat BETWEEN ... AND lon BETWEEN ... kan geen index gebruiken die beide kolommen
-- tegelijk afdekt zonder een samengestelde btree-index die minder efficiënt is voor
-- rechthoek-queries dan een ruimtelijke index; vandaar PostGIS.
--
-- PostGIS staat mogelijk nog niet aan op deze database — dit installeert de extensie indien nog
-- niet aanwezig (idempotent, dus onschadelijk als ze al actief is).
CREATE EXTENSION IF NOT EXISTS postgis;

-- Gegenereerde kolom i.p.v. een los bijgehouden veld + trigger: blijft door Postgres zelf
-- gegarandeerd in sync met lat/lon, geen extra triggercode nodig. ST_MakePoint/ST_SetSRID zijn
-- STRICT, dus geom wordt vanzelf NULL zodra lat of lon dat is (haltes zonder coördinaten) — geen
-- expliciete CASE nodig. STORED (i.p.v. virtueel) omdat er een index op moet kunnen staan.
--
-- Dit herschrijft de volledige tabel (nodig voor een gegenereerde kolom); bij ~50K rijen in de
-- praktijk een fractie van een seconde, dus geen CONCURRENTLY-aanpak nodig.
ALTER TABLE chb.stop
    ADD COLUMN geom geometry(Point, 4326)
    GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(lon::double precision, lat::double precision), 4326)) STORED;

-- GiST-index voor de bbox-query (ST_MakeEnvelope(...) && geom) die StopViewerRepository gebruikt.
CREATE INDEX idx_stop_geom ON chb.stop USING GIST (geom);

-- Geen extra GRANT nodig: chb_viewer/chb_editor hebben al (kolom-onafhankelijke) SELECT/UPDATE op
-- chb.stop via de bestaande grants in merge_stops_security_definer.sql — die dekken ook nieuw
-- toegevoegde kolommen.
