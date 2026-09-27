#!/usr/bin/env bash
set -euo pipefail

# Houdt een SSH-tunnel naar de (nu externe) Postgres-server open via autossh, zodat
# chb-viewer gewoon tegen localhost:$PG_TUNNEL_LOCAL_PORT kan blijven praten
# (spring.datasource.*.url in het configbestand hoeft dan niet te wijzigen).
#
# Vereist: autossh geïnstalleerd, en SSH-key-based auth naar de Postgres-host (geen
# wachtwoordprompt — autossh kan die niet beantwoorden).
#
# Gebruik:
#   PG_TUNNEL_HOST=db.example.com PG_TUNNEL_USER=deploy ./pg-tunnel.sh
#
# Optionele env vars (defaults hieronder):
#   PG_TUNNEL_USER         SSH-gebruiker op de Postgres-host
#   PG_TUNNEL_HOST         hostnaam/IP van de Postgres-host        (verplicht)
#   PG_TUNNEL_SSH_PORT     SSH-poort op die host                   (default 22)
#   PG_TUNNEL_REMOTE_PORT  Postgres-poort op die host              (default 5432)
#   PG_TUNNEL_LOCAL_PORT   lokale poort waarop chb_viewer/editor    (default 5432)
#                          moeten connecteren (via localhost)
#   PG_TUNNEL_KEY          pad naar de private key                 (default ~/.ssh/id_ed25519)

PG_TUNNEL_USER="${PG_TUNNEL_USER:?zet PG_TUNNEL_USER (ssh-gebruiker op de postgres-host)}"
PG_TUNNEL_HOST="${PG_TUNNEL_HOST:?zet PG_TUNNEL_HOST (hostnaam/IP van de postgres-host)}"
PG_TUNNEL_SSH_PORT="${PG_TUNNEL_SSH_PORT:-22}"
PG_TUNNEL_REMOTE_PORT="${PG_TUNNEL_REMOTE_PORT:-5432}"
PG_TUNNEL_LOCAL_PORT="${PG_TUNNEL_LOCAL_PORT:-5432}"
PG_TUNNEL_KEY="${PG_TUNNEL_KEY:-$HOME/.ssh/id_ed25519}"

# autossh's eigen monitoring-poort uitzetten (-M 0) en in plaats daarvan op
# SSH-protocolniveau (ServerAlive*) laten detecteren of de verbinding weg is — geen aparte
# poort nodig, werkt ook door restrictievere firewalls heen.
export AUTOSSH_GATETIME=0

exec autossh -M 0 \
  -4 \
  -N \
  -o "ServerAliveInterval=30" \
  -o "ServerAliveCountMax=3" \
  -o "ExitOnForwardFailure=yes" \
  -o "StrictHostKeyChecking=accept-new" \
  -i "$PG_TUNNEL_KEY" \
  -p "$PG_TUNNEL_SSH_PORT" \
  -L "127.0.0.1:${PG_TUNNEL_LOCAL_PORT}:127.0.0.1:${PG_TUNNEL_REMOTE_PORT}" \
  "${PG_TUNNEL_USER}@${PG_TUNNEL_HOST}"
