// Windows + Podman machine (WSL) only publishes container ports on loopback (wslrelay), and --network host
// containers live inside the WSL VM. This forwards, from every Windows interface (0.0.0.0), so phones on the LAN can connect:
//   8080  -> 127.0.0.1:8080            the API (wslrelay publishes it on loopback)
//   7880  -> <WSL VM IP>:7880          LiveKit signalling (ws)
//   7881  -> <WSL VM IP>:7881          LiveKit WebRTC over TCP (ICE/TCP media)
// The VM IP (e.g. 172.30.x.x, found with `podman machine ssh ip -4 addr`) is discovered at start and again after a failed
// connect (it changes when the VM restarts). UDP media (50000-50100) is NOT forwarded, so phones fall back to ICE/TCP on 7881.
// Leave it running: node scripts/lan-relay.mjs      Override the VM address with VM_IP=..., skip LiveKit with NO_LIVEKIT=1.
// Admin alternative for the API: netsh interface portproxy add v4tov4 listenaddress=0.0.0.0 listenport=8080 connectaddress=127.0.0.1 connectport=8080
import net from 'node:net';
import { execFileSync } from 'node:child_process';

const apiPort = Number(process.env.PORT ?? 8080);
let vmIp = process.env.VM_IP || null;
let lastLookup = 0;

/** The Podman machine's eth0 IPv4, or null when it cannot be found. */
function discoverVmIp() {
  if (process.env.VM_IP) return process.env.VM_IP;
  lastLookup = Date.now();
  try {
    const out = execFileSync('podman', ['machine', 'ssh', 'ip -4 -o addr show eth0'], { encoding: 'utf8', timeout: 20_000, windowsHide: true });
    return /inet (\d+\.\d+\.\d+\.\d+)\//.exec(out)?.[1] ?? null;
  } catch {
    return null;
  }
}

function relay(listenPort, target) {
  net.createServer((c) => {
    const host = target();
    if (!host) { c.destroy(); return; }
    const u = net.connect(listenPort, host);
    c.pipe(u).pipe(c);
    const end = () => { c.destroy(); u.destroy(); };
    c.on('error', end); u.on('error', (err) => {
      end();
      // The VM may have restarted with a new address: look again, at most every 10 s.
      if (target !== loopback && ['ECONNREFUSED', 'EHOSTUNREACH', 'ETIMEDOUT'].includes(err.code) && Date.now() - lastLookup > 10_000) vmIp = discoverVmIp() ?? vmIp;
    });
  }).on('error', (err) => console.error(`cannot listen on ${listenPort}: ${err.message}`))
    .listen(listenPort, '0.0.0.0', () => console.log(`relaying 0.0.0.0:${listenPort} -> ${target() ?? '(no target)'}:${listenPort}`));
}

const loopback = () => '127.0.0.1';
relay(apiPort, loopback);
if (!process.env.NO_LIVEKIT) {
  vmIp ??= discoverVmIp();
  if (!vmIp) console.error('LiveKit relay: could not find the Podman machine IP (podman machine ssh failed); set VM_IP=... and restart');
  for (const port of [7880, 7881]) relay(port, () => vmIp);
}
