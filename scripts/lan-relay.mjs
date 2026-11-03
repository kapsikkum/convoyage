// Windows + Podman machine (WSL) only publishes container ports on loopback (wslrelay). This forwards
// 0.0.0.0:8080 (LAN) -> 127.0.0.1:8080 so phones can connect. Leave it running: node scripts/lan-relay.mjs
// Admin alternative: netsh interface portproxy add v4tov4 listenaddress=0.0.0.0 listenport=8080 connectaddress=127.0.0.1 connectport=8080
import net from 'node:net';
const port = Number(process.env.PORT ?? 8080);
net.createServer((c) => {
  const u = net.connect(port, '127.0.0.1');
  c.pipe(u).pipe(c);
  const end = () => { c.destroy(); u.destroy(); };
  c.on('error', end); u.on('error', end);
}).listen(port, '0.0.0.0', () => console.log(`relaying 0.0.0.0:${port} -> 127.0.0.1:${port}`));
