# Fallback for when `podman compose up` fails with a netavark/nftables error on the Podman machine.
# Runs the same db + server as compose.yaml, but as --network host containers (no bridge needed).
# Reads JWT_SECRET from the repo-root .env. Usage: pwsh scripts/up.ps1 [-NoBuild]
param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$envFile = Join-Path $root '.env'
$secret = (Get-Content $envFile | Where-Object { $_ -match '^JWT_SECRET=' } | Select-Object -First 1) -replace '^JWT_SECRET=', ''
if (-not $secret -or $secret.Length -lt 32) { throw "JWT_SECRET (32+ chars) missing in $envFile" }
function EnvVal($name, $default) {
  $line = Get-Content $envFile | Where-Object { $_ -match "^$name=.+" } | Select-Object -First 1
  if ($line) { return ($line -replace "^$name=", '').Trim() } else { return $default }
}
# PhotoScout link: only passed to the container when PHOTOSCOUT_URL is set in .env (partial config refuses to start).
$psArgs = @()
$psUrl = EnvVal 'PHOTOSCOUT_URL' ''
if ($psUrl) {
  $psArgs += '-e', "PHOTOSCOUT_URL=$psUrl", '-e', "PUBLIC_BASE_URL=$(EnvVal 'PUBLIC_BASE_URL' '')", '-e', "PHOTOSCOUT_TOKEN_KEY=$(EnvVal 'PHOTOSCOUT_TOKEN_KEY' '')", '-e', "PHOTOSCOUT_CLIENT_ID=$(EnvVal 'PHOTOSCOUT_CLIENT_ID' 'convoyage')"
  $psSecret = EnvVal 'PHOTOSCOUT_CLIENT_SECRET' ''
  if ($psSecret) { $psArgs += '-e', "PHOTOSCOUT_CLIENT_SECRET=$psSecret" }
}
# Route conditions: TfNSW live traffic key, and the Waze harvester (an SSH tunnel to data-server, see docs). Both optional.
$tfKey = EnvVal 'TFNSW_API_KEY' ''
if ($tfKey) { $psArgs += '-e', "TFNSW_API_KEY=$tfKey" }
$wazeUrl = EnvVal 'WAZE_HARVESTER_URL' ''
if ($wazeUrl) { $psArgs += '-e', "WAZE_HARVESTER_URL=$wazeUrl", '-e', "WAZE_HARVESTER_KEY=$(EnvVal 'WAZE_HARVESTER_KEY' '')" }
$lkKey = EnvVal 'LIVEKIT_API_KEY' 'devkey'
$lkSecret = EnvVal 'LIVEKIT_API_SECRET' 'convoyage-dev-livekit-secret-0123456789'
$osrm = EnvVal 'OSRM_URL' 'https://router.project-osrm.org'
$overpass = EnvVal 'OVERPASS_URL' 'https://overpass-api.de/api/interpreter,https://overpass.openstreetmap.fr/api/interpreter'
# LAN address phones reach this PC on (first Wi-Fi/Ethernet IPv4). LiveKit advertises it as the WebRTC candidate.
$lan = Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias 'Wi-Fi','Ethernet' -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -notlike '169.254.*' } | Select-Object -First 1 -ExpandProperty IPAddress
$lkNodeIp = EnvVal 'LIVEKIT_NODE_IP' $lan
if (-not $lkNodeIp) { $lkNodeIp = '127.0.0.1' }
$lkUrl = EnvVal 'LIVEKIT_URL' $(if ($lan) { "ws://${lan}:7880" } else { 'ws://localhost:7880' })

if (-not $NoBuild) { podman build --format docker --network host -t convoyage-server:local (Join-Path $root 'server'); if ($LASTEXITCODE) { throw 'build failed' } }

$ErrorActionPreference = 'Continue'   # native stderr (already exists / no such container) is not fatal
podman rm -f convoyage-server convoyage-livekit convoyage-db *> $null
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

# Push-to-talk: LiveKit SFU on the host network. 7880 ws signalling, 7881 TCP media, 50000-50100/udp media.
# Dev keys come from .env (defaults devkey / a dev secret). Host networking means nothing is published explicitly.
$lkConfig = "{port: 7880, rtc: {tcp_port: 7881, port_range_start: 50000, port_range_end: 50100, use_external_ip: false, node_ip: ${lkNodeIp}, allow_tcp_fallback: true}, keys: {${lkKey}: ${lkSecret}}}"
podman run -d --name convoyage-livekit --network host --restart unless-stopped `
  -e "LIVEKIT_CONFIG=$lkConfig" `
  docker.io/livekit/livekit-server:latest --node-ip $lkNodeIp
if ($LASTEXITCODE) { throw 'livekit start failed' }

# BETA ONLY: MAIL_DEV=1 returns magic links in API responses (no real mailer yet). No NODE_ENV=production.
podman run -d --name convoyage-server --network host --restart unless-stopped `
  -e DATABASE_URL=postgres://convoyage:convoyage@127.0.0.1:5432/convoyage `
  -e AUTO_MIGRATE=1 -e MAIL_DEV=1 -e "JWT_SECRET=$secret" -e PORT=8080 -e HOST=0.0.0.0 `
  -e "LIVEKIT_URL=$lkUrl" -e "LIVEKIT_API_KEY=$lkKey" -e "LIVEKIT_API_SECRET=$lkSecret" -e "OSRM_URL=$osrm" -e "OVERPASS_URL=$overpass" `
  @psArgs `
  -v convoyage-media:/data/media `
  convoyage-server:local
if ($LASTEXITCODE) { throw 'server start failed' }
Write-Host "Up. http://localhost:8080/health   LiveKit signal for phones: $lkUrl (node ip $lkNodeIp; relayed to the Podman VM, TCP media only)"

# LAN: the Podman machine only publishes on loopback, so forward 0.0.0.0:8080 -> 127.0.0.1:8080 for phones,
# and 7880/7881 -> the Podman VM IP for LiveKit (see lan-relay.mjs).
Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object { $_.CommandLine -match 'lan-relay\.mjs' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Start-Process node -ArgumentList (Join-Path $PSScriptRoot 'lan-relay.mjs') -WindowStyle Hidden
Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias 'Wi-Fi','Ethernet' -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "LAN: http://$($_.IPAddress):8080" }
# Phones also need inbound 7880/tcp, 7881/tcp (and 8080/tcp) on Windows Firewall (Private profile, admin shell):
#   New-NetFirewallRule -DisplayName "Convoyage LiveKit" -Direction Inbound -Protocol TCP -LocalPort 7880,7881 -Action Allow -Profile Private
# LiveKit runs --network host inside the Podman (WSL) VM, so it answers on the VM IP only. lan-relay.mjs (started above) finds that IP
# with `podman machine ssh` and forwards Windows 0.0.0.0:7880 and :7881 to it. UDP media (50000-50100) is not forwarded:
# phones fall back to ICE over TCP on 7881 (rtc.node_ip / --node-ip is the LAN address advertised in the candidates).
