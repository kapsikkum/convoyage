# Fallback for when `podman compose up` fails with a netavark/nftables error on the Podman machine.
# Runs the same db + server as compose.yaml, but as --network host containers (no bridge needed).
# Reads JWT_SECRET from the repo-root .env. Usage: pwsh scripts/up.ps1 [-NoBuild]
param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$envFile = Join-Path $root '.env'
$secret = (Get-Content $envFile | Where-Object { $_ -match '^JWT_SECRET=' } | Select-Object -First 1) -replace '^JWT_SECRET=', ''
if (-not $secret -or $secret.Length -lt 32) { throw "JWT_SECRET (32+ chars) missing in $envFile" }

if (-not $NoBuild) { podman build --format docker --network host -t convoyage-server:local (Join-Path $root 'server'); if ($LASTEXITCODE) { throw 'build failed' } }

$ErrorActionPreference = 'Continue'   # native stderr (already exists / no such container) is not fatal
podman rm -f convoyage-server convoyage-db *> $null
foreach ($v in 'convoyage-db', 'convoyage-media') { podman volume exists $v; if ($LASTEXITCODE) { podman volume create $v | Out-Null } }

podman run -d --name convoyage-db --network host --restart unless-stopped `
  -e POSTGRES_USER=convoyage -e POSTGRES_PASSWORD=convoyage -e POSTGRES_DB=convoyage `
  -v convoyage-db:/var/lib/postgresql/data `
  docker.io/postgis/postgis:16-3.4
if ($LASTEXITCODE) { throw 'db start failed' }

for ($i = 0; $i -lt 40; $i++) {
  podman exec convoyage-db pg_isready -U convoyage -d convoyage 2>$null | Out-Null
  if ($LASTEXITCODE -eq 0) { break }
  Start-Sleep -Seconds 2
}

# BETA ONLY: MAIL_DEV=1 returns magic links in API responses (no real mailer yet). No NODE_ENV=production.
podman run -d --name convoyage-server --network host --restart unless-stopped `
  -e DATABASE_URL=postgres://convoyage:convoyage@127.0.0.1:5432/convoyage `
  -e AUTO_MIGRATE=1 -e MAIL_DEV=1 -e "JWT_SECRET=$secret" -e PORT=8080 -e HOST=0.0.0.0 `
  -v convoyage-media:/data/media `
  convoyage-server:local
if ($LASTEXITCODE) { throw 'server start failed' }
Write-Host 'Up. http://localhost:8080/health'

# LAN: the Podman machine only publishes on loopback, so forward 0.0.0.0:8080 -> 127.0.0.1:8080 for phones.
Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object { $_.CommandLine -match 'lan-relay\.mjs' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Start-Process node -ArgumentList (Join-Path $PSScriptRoot 'lan-relay.mjs') -WindowStyle Hidden
Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias 'Wi-Fi','Ethernet' -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "LAN: http://$($_.IPAddress):8080" }
