#!/usr/bin/env bash
set -euo pipefail

# Start chb-frontend met een configbestand buiten de app zelf (bv. de home-dir van de
# service-user op de server) — zie chb-frontend.properties.example voor de inhoud.
#
# Gebruik:
#   CONFIG_LOCATION=/pad/naar/chb-frontend.local.properties ./run.sh /pad/naar/chb-frontend.jar
#
# Zonder argumenten: pakt de eerste chb-frontend-*.jar naast dit script, en
# $HOME/chb-frontend.local.properties als configbestand.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${1:-$(ls "$SCRIPT_DIR"/chb-frontend-*.jar 2>/dev/null | head -n1)}"
CONFIG_LOCATION="${CONFIG_LOCATION:-$HOME/chb-frontend.local.properties}"

if [[ -z "$JAR" || ! -f "$JAR" ]]; then
  echo "Geen jar gevonden. Geef het pad mee als eerste argument." >&2
  exit 1
fi
if [[ ! -f "$CONFIG_LOCATION" ]]; then
  echo "Configbestand niet gevonden: $CONFIG_LOCATION" >&2
  echo "Zie chb-frontend.properties.example om er een aan te maken." >&2
  exit 1
fi

exec java -jar "$JAR" --spring.config.additional-location="file:${CONFIG_LOCATION}"
