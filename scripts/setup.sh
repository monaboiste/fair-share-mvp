#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

if docker compose version >/dev/null 2>&1; then
    compose=(docker compose)
else
    compose=(docker-compose)
fi

sql="$(cat "$root/scripts/postgres/fresh.sql" "$root/scripts/postgres/schema.sql" "$root/scripts/postgres/sample.sql")"
printf '%s\n' "$sql" |
    "${compose[@]}" exec -T postgres sh -c 'exec psql -X --single-transaction --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"'
