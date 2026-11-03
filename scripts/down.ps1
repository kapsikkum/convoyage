# Stops and removes the fallback containers started by up.ps1. Volumes (data) are kept; add -Volumes to delete them.
param([switch]$Volumes)
podman rm -f convoyage-server convoyage-db
if ($Volumes) { podman volume rm convoyage-db convoyage-media }
Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object { $_.CommandLine -match 'lan-relay\.mjs' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
