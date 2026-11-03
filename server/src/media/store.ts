import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';

/** Where media bytes live. Rows in the `media` table point at it by `storage_key`. */
export interface MediaStore {
  put(key: string, data: Buffer, mime: string): Promise<void>;
  get(key: string): Promise<Buffer | null>;
  delete(key: string): Promise<void>;
}

/** Local disk, for development. Keys are server-generated, but the path is still checked. */
export class LocalDiskMediaStore implements MediaStore {
  constructor(private root: string) {}

  private resolve(key: string): string {
    const full = path.resolve(this.root, key);
    if (full !== this.root && !full.startsWith(this.root + path.sep)) throw new Error('media key escapes the store');
    return full;
  }

  async put(key: string, data: Buffer): Promise<void> {
    const file = this.resolve(key);
    await mkdir(path.dirname(file), { recursive: true });
    await writeFile(file, data);
  }

  async get(key: string): Promise<Buffer | null> {
    try { return await readFile(this.resolve(key)); } catch (err) {
      if ((err as NodeJS.ErrnoException).code === 'ENOENT') return null;
      throw err;
    }
  }

  async delete(key: string): Promise<void> {
    await rm(this.resolve(key), { force: true });
  }
}

/** In memory, for tests. */
export class MemoryMediaStore implements MediaStore {
  readonly files = new Map<string, Buffer>();
  async put(key: string, data: Buffer): Promise<void> { this.files.set(key, data); }
  async get(key: string): Promise<Buffer | null> { return this.files.get(key) ?? null; }
  async delete(key: string): Promise<void> { this.files.delete(key); }
}

/**
 * S3-compatible object storage (S3, R2, MinIO). Not built: wire an S3 client here and select it in
 * `server.ts` when an S3 bucket is configured.
 * TODO(s3): implement put/get/delete with @aws-sdk/client-s3 (or presigned URLs, so apps fetch from the bucket directly).
 */
export class S3MediaStore implements MediaStore {
  constructor(_opts: { bucket: string; endpoint?: string; region?: string }) {}
  async put(): Promise<void> { throw new Error('S3MediaStore is not implemented'); }
  async get(): Promise<Buffer | null> { throw new Error('S3MediaStore is not implemented'); }
  async delete(): Promise<void> { throw new Error('S3MediaStore is not implemented'); }
}
