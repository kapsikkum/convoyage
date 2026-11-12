#!/usr/bin/env bash
# Backs up the Postgres database and the media volume into $BACKUP_DIR (default ./backups), keeping the newest $KEEP (default 14).
#   deploy/backup.sh                 # run from cron: 17 3 * * * /srv/convoyage/deploy/backup.sh
# Restore:
#   gunzip -c backups/db-YYYYmmdd-HHMMSS.sql.gz | docker compose -f deploy/compose.prod.yaml --env-file deploy/.env.prod exec -T db psql -U convoyage convoyage
#   docker run --rm -v convoyage_convoyage-media:/data -v "$PWD/backups":/b alpine tar xzf /b/media-YYYYmmdd-HHMMSS.tgz -C /data
set -euo pipefail
cd "$(dirname "$0")/.."
COMPOSE=${COMPOSE:-docker}
BACKUP_DIR=${BACKUP_DIR:-./backups}
KEEP=${KEEP:-14}
STAMP=$(date +%Y%m%d-%H%M%S)
mkdir -p "$BACKUP_DIR"

$COMPOSE compose -f deploy/compose.prod.yaml --env-file deploy/.env.prod exec -T db \
  pg_dump -U convoyage --no-owner convoyage | gzip > "$BACKUP_DIR/db-$STAMP.sql.gz"

# Skipped automatically when media lives in S3 (the volume is empty then).
$COMPOSE run --rm -v convoyage_convoyage-media:/data:ro -v "$(cd "$BACKUP_DIR" && pwd)":/b docker.io/library/alpine \
  tar czf "/b/media-$STAMP.tgz" -C /data . || true

ls -1t "$BACKUP_DIR"/db-*.sql.gz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm --
ls -1t "$BACKUP_DIR"/media-*.tgz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm --
echo "backup $STAMP done"
