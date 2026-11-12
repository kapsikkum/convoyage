import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { S3Client, PutObjectCommand, GetObjectCommand, DeleteObjectCommand } from '@aws-sdk/client-s3';

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

/** The slice of the S3 client the store uses, so tests can pass a fake. */
export interface S3Like {
  send(command: unknown): Promise<any>;
}

export interface S3Options {
  bucket: string;
  endpoint?: string;
  region?: string;
  /** Path-style addressing, needed by MinIO and some self-hosted stores. */
  forcePathStyle?: boolean;
  accessKeyId?: string;
  secretAccessKey?: string;
  /** Key prefix inside the bucket, e.g. "media/". */
  prefix?: string;
}

/** S3-compatible object storage (S3, R2, MinIO). Bytes are proxied through the API, so media stays behind the auth checks. */
export class S3MediaStore implements MediaStore {
  private client: S3Like;
  private prefix: string;
  constructor(private opts: S3Options, client?: S3Like) {
    this.prefix = opts.prefix ?? '';
    this.client = client ?? new S3Client({
      region: opts.region ?? 'auto',
      endpoint: opts.endpoint,
      forcePathStyle: opts.forcePathStyle,
      credentials: opts.accessKeyId && opts.secretAccessKey ? { accessKeyId: opts.accessKeyId, secretAccessKey: opts.secretAccessKey } : undefined,
    });
  }

  private key(key: string): string {
    if (key.includes('..')) throw new Error('media key escapes the store');
    return this.prefix + key;
  }

  async put(key: string, data: Buffer, mime: string): Promise<void> {
    await this.client.send(new PutObjectCommand({ Bucket: this.opts.bucket, Key: this.key(key), Body: data, ContentType: mime }));
  }

  async get(key: string): Promise<Buffer | null> {
    try {
      const res = await this.client.send(new GetObjectCommand({ Bucket: this.opts.bucket, Key: this.key(key) }));
      return Buffer.from(await res.Body.transformToByteArray());
    } catch (err) {
      const e = err as { name?: string; $metadata?: { httpStatusCode?: number } };
      if (e.name === 'NoSuchKey' || e.$metadata?.httpStatusCode === 404) return null;
      throw err;
    }
  }

  async delete(key: string): Promise<void> {
    await this.client.send(new DeleteObjectCommand({ Bucket: this.opts.bucket, Key: this.key(key) }));
  }
}
