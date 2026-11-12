#!/usr/bin/env bash
# Builds the routing graph for the bundled OSRM from an OpenStreetMap extract (one-off, repeat to refresh).
#   deploy/osrm-prepare.sh https://download.geofabrik.de/australia-oceania/australia-latest.osm.pbf
# Needs roughly 16 GB RAM for a country-sized extract; use a state/region extract on a small host.
# Then: docker compose -f deploy/compose.prod.yaml --env-file deploy/.env.prod --profile osrm up -d osrm
set -euo pipefail
PBF_URL=${1:?usage: osrm-prepare.sh <extract .osm.pbf URL>}
IMAGE=docker.io/osrm/osrm-backend:latest
RUNTIME=${RUNTIME:-docker}
VOL=convoyage_osrm-data

$RUNTIME volume create "$VOL" >/dev/null
$RUNTIME run --rm -v "$VOL":/data docker.io/library/alpine sh -c "wget -O /data/region.osm.pbf '$PBF_URL'"
$RUNTIME run --rm -v "$VOL":/data "$IMAGE" osrm-extract -p /opt/car.lua /data/region.osm.pbf
$RUNTIME run --rm -v "$VOL":/data "$IMAGE" osrm-partition /data/region.osrm
$RUNTIME run --rm -v "$VOL":/data "$IMAGE" osrm-customize /data/region.osrm
echo "OSRM graph ready in volume $VOL"
