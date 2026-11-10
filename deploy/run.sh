#!/usr/bin/env bash
set -euo pipefail

# Start chb-frontend met een configbestand buiten de app zelf (bv. de home-dir van de
# service-user op de server) — zie chb-frontend.properties.example voor de inhoud.
#
# Gebruik:
#   CONFIG_LOCATION=/pad/naar/chb-frontend.local.properties ./run.sh /pad/naar/chb-frontend.jar
#
# Zonder argumenten: werkt eerst bij via gh (zie hieronder), pakt dan de eerste chb-frontend-*.jar naast dit script, en
# $HOME/chb-frontend.local.properties als configbestand.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Zonder jar-argument en met gh (ingelogd, of GH_TOKEN met Actions: read): eerst de jar van de
# laatste geslaagde master-build ophalen, als die nieuwer is dan de vorige (.run-id).
# Lukt dat niet (GitHub onbereikbaar, geen token), dan start gewoon de jar die er al staat.
if [[ $# -eq 0 ]] && command -v gh >/dev/null; then
  if run=$(timeout 30 gh run list -R Muspah/chb -w build -b master -s success -L 1 --json databaseId -q '.[0].databaseId') \
     && [[ -n "$run" && "$run" != "$(cat "$SCRIPT_DIR/.run-id" 2>/dev/null)" ]]; then
    tmp="$(mktemp -d)"
    if timeout 120 gh run download "$run" -R Muspah/chb -n chb-frontend -D "$tmp" && compgen -G "$tmp/*.jar" >/dev/null; then
      rm -f "$SCRIPT_DIR"/chb-frontend-*.jar
      mv "$tmp"/*.jar "$SCRIPT_DIR"/
      echo "$run" > "$SCRIPT_DIR/.run-id"
      echo "Bijgewerkt naar build $run." >&2
    else
      echo "Bijwerken mislukt; start de bestaande jar." >&2
    fi
    rm -rf "$tmp"
  fi
fi

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
