import sharp from 'sharp';
import { badRequest, HttpError } from '../errors.js';

export const MAX_UPLOAD_BYTES = 15 * 1024 * 1024;
/** Longest edge kept; bigger images are scaled down. */
const MAX_EDGE = 2560;
const THUMB_EDGE = 512;
const MAX_PIXELS = 60_000_000;

export type ImageKind = 'jpeg' | 'png' | 'webp';

const MIME: Record<ImageKind, string> = { jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp' };
const EXT: Record<ImageKind, string> = { jpeg: 'jpg', png: 'png', webp: 'webp' };

/** Identify an image by its first bytes, never by the filename or the client's Content-Type. */
export function sniffImage(b: Buffer): ImageKind | null {
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return 'jpeg';
  if (b.length >= 8 && b.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return 'png';
  if (b.length >= 12 && b.toString('ascii', 0, 4) === 'RIFF' && b.toString('ascii', 8, 12) === 'WEBP') return 'webp';
  return null;
}

export interface ProcessedImage {
  mime: string;
  ext: string;
  data: Buffer;
  thumb: Buffer;
  width: number;
  height: number;
  /** EXIF DateTimeOriginal, read as UTC (EXIF has no zone), or null. */
  takenAt: Date | null;
}

/**
 * Re-encode an upload: auto-rotate, downscale, and drop every metadata block (GPS, camera, owner name, ...).
 * The one thing kept is DateTimeOriginal, returned as `takenAt` and stored in the database (not in the file).
 * HEIC/HEIF is refused: the prebuilt sharp has no HEVC decoder, so clients convert to JPEG before upload.
 */
export async function processImage(input: Buffer): Promise<ProcessedImage> {
  const kind = sniffImage(input);
  if (!kind) throw new HttpError(415, 'Only JPEG, PNG and WebP images are accepted');
  try {
    const meta = await sharp(input, { limitInputPixels: MAX_PIXELS }).metadata();
    const takenAt = meta.exif ? exifDateTimeOriginal(meta.exif) : null;
    const base = () => sharp(input, { limitInputPixels: MAX_PIXELS, failOn: 'error' }).rotate();
    let pipeline = base().resize({ width: MAX_EDGE, height: MAX_EDGE, fit: 'inside', withoutEnlargement: true });
    pipeline = kind === 'jpeg' ? pipeline.jpeg({ quality: 85, mozjpeg: true }) : kind === 'png' ? pipeline.png() : pipeline.webp({ quality: 85 });
    const { data, info } = await pipeline.toBuffer({ resolveWithObject: true });
    const thumb = await sharp(data).resize({ width: THUMB_EDGE, height: THUMB_EDGE, fit: 'inside', withoutEnlargement: true }).jpeg({ quality: 75 }).toBuffer();
    return { mime: MIME[kind], ext: EXT[kind], data, thumb, width: info.width, height: info.height, takenAt };
  } catch (err) {
    if (err instanceof HttpError) throw err;
    throw badRequest('That file is not a valid image');
  }
}

/** Minimal EXIF reader: IFD0 -> ExifIFD pointer (0x8769) -> DateTimeOriginal (0x9003, "YYYY:MM:DD HH:MM:SS"). */
export function exifDateTimeOriginal(exif: Buffer): Date | null {
  try {
    const start = exif.toString('ascii', 0, 4) === 'Exif' ? 6 : 0;
    const tiff = exif.subarray(start);
    const little = tiff.toString('ascii', 0, 2) === 'II';
    if (!little && tiff.toString('ascii', 0, 2) !== 'MM') return null;
    const u16 = (o: number) => (little ? tiff.readUInt16LE(o) : tiff.readUInt16BE(o));
    const u32 = (o: number) => (little ? tiff.readUInt32LE(o) : tiff.readUInt32BE(o));
    const find = (ifd: number, tag: number): { type: number; count: number; valueAt: number } | null => {
      if (ifd + 2 > tiff.length) return null;
      const n = u16(ifd);
      for (let i = 0; i < n; i++) {
        const e = ifd + 2 + i * 12;
        if (e + 12 > tiff.length) return null;
        if (u16(e) === tag) return { type: u16(e + 2), count: u32(e + 4), valueAt: e + 8 };
      }
      return null;
    };
    const pointer = find(u32(4), 0x8769);
    if (!pointer) return null;
    const dto = find(u32(pointer.valueAt), 0x9003);
    if (!dto || dto.type !== 2 || dto.count < 19) return null;
    const text = tiff.toString('ascii', u32(dto.valueAt), u32(dto.valueAt) + 19);
    const m = /^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})$/.exec(text);
    if (!m) return null;
    const d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]));
    return Number.isNaN(d.getTime()) || d.getUTCFullYear() < 1990 ? null : d;
  } catch {
    return null;
  }
}
