-- Toe te voegen als een nieuwe Flyway-migratie in de gtfs-import-repo (volgende versienummer na
-- de laatste daar) — dit is een wijziging aan het gedeelde datamodel, niet aan de chb-frontend
-- applicatie zelf. Zie build-instruction.md §5.
--
-- chb.merge_stops() doet intern UPDATE chb.raw_stop SET stop_id = ...  Met een gewone
-- (SECURITY INVOKER) functie zou chb_editor daarvoor rechtstreeks UPDATE-rechten op
-- chb.raw_stop.stop_id nodig hebben, en dus zou de applicatie (of een bug erin) buiten de functie
-- om, ongeaudit, een raw_stop kunnen herkoppelen. SECURITY DEFINER + een vastgezette search_path
-- zorgt ervoor dat chb_editor enkel via deze ene, gecontroleerde en gelogde functie bij
-- raw_stop.stop_id kan.

ALTER FUNCTION chb.merge_stops(bigint, bigint) SECURITY DEFINER SET search_path = chb, pg_catalog;

-- Rolrechten voor de twee pools die de chb-frontend applicatie gebruikt (build-instruction.md §5).
-- Wachtwoorden hier bewust niet ingevuld: apart, buiten versiebeheer, aanmaken.

CREATE ROLE chb_viewer LOGIN PASSWORD 'REPLACE_ME';
GRANT USAGE ON SCHEMA chb TO chb_viewer;
GRANT SELECT ON ALL TABLES IN SCHEMA chb TO chb_viewer;
ALTER DEFAULT PRIVILEGES IN SCHEMA chb GRANT SELECT ON TABLES TO chb_viewer;

CREATE ROLE chb_editor LOGIN PASSWORD 'REPLACE_ME';
GRANT USAGE ON SCHEMA chb TO chb_editor;
GRANT SELECT, INSERT, UPDATE ON chb.stop TO chb_editor;
GRANT SELECT, INSERT, UPDATE, DELETE ON chb.stop_attribute TO chb_editor;
GRANT SELECT ON chb.raw_stop TO chb_editor;
-- Enkel SELECT zou hier logisch lijken (de applicatie schrijft zelf nooit naar deze tabel), maar
-- de audit-trigger chb.log_stop_change() op chb.stop/chb.stop_attribute is SECURITY INVOKER en
-- draait dus met de rechten van chb_editor zelf zodra die een INSERT/UPDATE doet — die trigger
-- heeft dus INSERT nodig, ook al roept de app die nooit rechtstreeks aan.
GRANT SELECT, INSERT ON chb.stop_change_log TO chb_editor;
GRANT SELECT ON chb.stop_sources TO chb_editor;
GRANT EXECUTE ON FUNCTION chb.merge_stops(bigint, bigint) TO chb_editor;
