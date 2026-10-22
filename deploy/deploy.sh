#!/usr/bin/env bash
# Runs ON the server (invoked by .github/workflows/deploy.yml over SSH, or by hand).
# Usage: ./deploy.sh <image-tag>
set -euo pipefail
cd "$(dirname "$0")"
TAG="${1:?image tag required (e.g. a commit SHA)}"
COMPOSE=(docker compose -f docker-compose.prod.yml --env-file .env.production)

PREVIOUS=$(grep '^FINFLOW_IMAGE_TAG=' .env.production | cut -d= -f2)
echo "Deploying $TAG (previous: $PREVIOUS)"

# 1. Backup before migrations run (Flyway migrates on backend start).
mkdir -p backups
"${COMPOSE[@]}" exec -T postgres pg_dump -U "$(grep '^DB_USERNAME=' .env.production | cut -d= -f2)" -Fc finflow \
  > "backups/finflow-$(date -u +%Y%m%dT%H%M%SZ)-before-$TAG.dump" || echo "No running database yet; skipping backup"
find backups -name '*.dump' -type f | sort -r | tail -n +8 | xargs -r rm -f   # keep the 7 newest (names sort by time)

# 2. Pull and switch.
sed -i.bak "s/^FINFLOW_IMAGE_TAG=.*/FINFLOW_IMAGE_TAG=$TAG/" .env.production
"${COMPOSE[@]}" pull --quiet backend frontend
"${COMPOSE[@]}" up -d --remove-orphans

# 3. Smoke test through the public entry point; roll back the image tag if it fails.
DOMAIN=$(grep '^FINFLOW_DOMAIN=' .env.production | cut -d= -f2)
for _ in $(seq 1 40); do
  if curl -fsS -X POST "https://$DOMAIN/api/v1/demo/session" >/dev/null 2>&1; then
    echo "Smoke test passed: https://$DOMAIN"
    exit 0
  fi
  sleep 5
done
echo "Smoke test failed; rolling back to $PREVIOUS" >&2
sed -i "s/^FINFLOW_IMAGE_TAG=.*/FINFLOW_IMAGE_TAG=$PREVIOUS/" .env.production
"${COMPOSE[@]}" up -d
exit 1
