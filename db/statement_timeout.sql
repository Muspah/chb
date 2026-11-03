-- Issue #19: een trage query mag een pool-verbinding niet onbeperkt bezet houden.
-- 30 s is ruim boven de namencache (~15 s bij opstarten) en de doorlichtingsrapporten.
-- Geldt pas voor nieuwe verbindingen: herstart de app na het uitvoeren.
ALTER ROLE chb_viewer SET statement_timeout = '30s';
ALTER ROLE chb_editor SET statement_timeout = '30s';
