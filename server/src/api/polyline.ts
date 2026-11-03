/** Google encoded polyline codec. Used to validate uploaded traces and to read their first point. */

export function decodePolyline(encoded: string, precision: 5 | 6 = 5, maxPoints = 200_000): [number, number][] {
  const factor = 10 ** precision;
  const points: [number, number][] = [];
  let index = 0;
  let lat = 0;
  let lng = 0;
  const next = (): number => {
    let result = 0;
    let shift = 0;
    let byte: number;
    do {
      if (index >= encoded.length) throw new Error('truncated polyline');
      byte = encoded.charCodeAt(index++) - 63;
      if (byte < 0 || byte > 63) throw new Error('bad polyline character');
      result |= (byte & 0x1f) << shift;
      shift += 5;
      if (shift > 35) throw new Error('polyline number too long');
    } while (byte >= 0x20);
    return result & 1 ? ~(result >> 1) : result >> 1;
  };
  while (index < encoded.length) {
    lat += next();
    lng += next();
    const p: [number, number] = [lat / factor, lng / factor];
    if (Math.abs(p[0]) > 90 || Math.abs(p[1]) > 180) throw new Error('polyline point out of range');
    points.push(p);
    if (points.length > maxPoints) throw new Error('polyline has too many points');
  }
  return points;
}

export function encodePolyline(points: [number, number][], precision: 5 | 6 = 5): string {
  const factor = 10 ** precision;
  let out = '';
  let pLat = 0;
  let pLng = 0;
  const put = (v: number) => {
    let n = v < 0 ? ~(v << 1) : v << 1;
    while (n >= 0x20) { out += String.fromCharCode((0x20 | (n & 0x1f)) + 63); n >>= 5; }
    out += String.fromCharCode(n + 63);
  };
  for (const [lat, lng] of points) {
    const la = Math.round(lat * factor);
    const ln = Math.round(lng * factor);
    put(la - pLat);
    put(ln - pLng);
    pLat = la;
    pLng = ln;
  }
  return out;
}
