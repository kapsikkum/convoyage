// Generates the placeholder app icon: chamfered cyan square on near-black. No dependencies.
import { deflateSync } from 'node:zlib';
import { writeFileSync } from 'node:fs';

const N = 1024;
const bg = [0x05, 0x08, 0x0d];
const fg = [0x2d, 0xe2, 0xe6];
const dot = [0x02, 0x14, 0x16];
const m = 200; // margin
const cut = 190; // chamfer at top-left and bottom-right
const x0 = m, y0 = m, x1 = N - m, y1 = N - m;
const dots = [[400, 624], [512, 512], [624, 400]];
const raw = Buffer.alloc((N * 3 + 1) * N);
for (let y = 0; y < N; y++) {
  raw[y * (N * 3 + 1)] = 0;
  for (let x = 0; x < N; x++) {
    let c = bg;
    if (x >= x0 && x < x1 && y >= y0 && y < y1) {
      const tl = (x - x0) + (y - y0) >= cut;
      const br = (x1 - x) + (y1 - y) > cut;
      if (tl && br) c = fg;
      for (const [cx, cy] of dots) if ((x - cx) ** 2 + (y - cy) ** 2 < 52 * 52 && c === fg) c = dot;
    }
    const o = y * (N * 3 + 1) + 1 + x * 3;
    raw[o] = c[0]; raw[o + 1] = c[1]; raw[o + 2] = c[2];
  }
}
const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
const crc = (b) => { let c = 0xffffffff; for (const v of b) c = crcTable[(c ^ v) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
const chunk = (type, data) => {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const c = Buffer.alloc(4); c.writeUInt32BE(crc(td));
  return Buffer.concat([len, td, c]);
};
const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(N, 0); ihdr.writeUInt32BE(N, 4); ihdr[8] = 8; ihdr[9] = 2;
const png = Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
writeFileSync(new URL('../Convoyage/Assets.xcassets/AppIcon.appiconset/AppIcon.png', import.meta.url), png);
console.log('wrote icon', png.length);
