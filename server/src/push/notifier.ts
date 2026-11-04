import type { Kysely } from 'kysely';
import type { DB } from '../db/index.js';
import type { PushPayload, PushSender } from './sender.js';

/** Hands a notification to a user's open sockets; true if at least one took it. */
export type LiveDelivery = (userId: string, payload: PushPayload) => boolean;

const LIKE_GAP_MS = 60_000;

const HAZARD_TEXT: Record<string, string> = {
  police: 'Police reported ahead', hazard: 'Hazard reported ahead', works: 'Roadworks reported ahead',
  fuel: 'Fuel stop reported', rest: 'Rest stop reported', crash: 'Crash reported ahead',
};

/** Decides who gets which push, skipping blocked pairs, and forgets tokens the provider rejects. Never throws. */
export class Notifier {
  private lastLike = new Map<string, number>();
  private pending = new Set<Promise<void>>();
  private live: LiveDelivery = () => false;
  constructor(private db: Kysely<DB>, private sender: PushSender, private now: () => number = Date.now) {}

  /** Notifications go down open sockets first (the app's keep-alive connection); push only reaches the rest. */
  setLive(live: LiveDelivery): void { this.live = live; }

  /** Resolves when every notification started so far has been handed to the sender (for tests and shutdown). */
  async idle(): Promise<void> {
    while (this.pending.size) await Promise.all([...this.pending]);
  }

  private async name(userId: string): Promise<string> {
    const p = await this.db.selectFrom('profiles').select(['display_name', 'handle']).where('user_id', '=', userId).executeTakeFirst();
    return p ? (p.display_name || p.handle) : 'Someone';
  }

  private async blocked(a: string, b: string): Promise<boolean> {
    const r = await this.db.selectFrom('blocks').select('blocker_id')
      .where((eb) => eb.or([eb.and([eb('blocker_id', '=', a), eb('blocked_id', '=', b)]), eb.and([eb('blocker_id', '=', b), eb('blocked_id', '=', a)])])).executeTakeFirst();
    return !!r;
  }

  /** Live sockets first; push for everyone they did not reach, or for everyone when [everyChannel] (SOS must not be missed). */
  private async deliver(userIds: string[], payload: PushPayload, everyChannel = false): Promise<void> {
    const offline = userIds.filter((id) => !this.live(id, payload) || everyChannel);
    if (!offline.length) return;
    const rows = await this.db.selectFrom('device_tokens').select(['platform', 'token', 'p256dh', 'auth']).where('user_id', 'in', offline).execute();
    if (!rows.length) return;
    const { invalid } = await this.sender.send(rows, payload);
    if (invalid.length) await this.db.deleteFrom('device_tokens').where('token', 'in', invalid).execute();
  }

  private run(job: () => Promise<void>): void {
    const p: Promise<void> = job().catch((err) => console.error('[push] notify failed:', (err as Error).message)).finally(() => { this.pending.delete(p); });
    this.pending.add(p);
  }

  follow(follower: string, followee: string, status: 'pending' | 'accepted'): void {
    this.run(async () => {
      if (follower === followee || await this.blocked(follower, followee)) return;
      const who = await this.name(follower);
      await this.deliver([followee], {
        title: status === 'pending' ? 'Follow request' : 'New follower',
        body: status === 'pending' ? `${who} wants to follow you` : `${who} started following you`,
        data: { type: status === 'pending' ? 'follow_request' : 'follow', userId: follower },
      });
    });
  }

  comment(postId: string, commenter: string, text: string): void {
    this.run(async () => {
      const post = await this.db.selectFrom('posts').select('author_id').where('id', '=', postId).executeTakeFirst();
      if (!post || post.author_id === commenter || await this.blocked(commenter, post.author_id)) return;
      await this.deliver([post.author_id], { title: `${await this.name(commenter)} commented`, body: text.slice(0, 120), data: { type: 'comment', postId } });
    });
  }

  /** At most one like notification a minute per post owner; the rest are dropped. */
  like(postId: string, liker: string): void {
    this.run(async () => {
      const post = await this.db.selectFrom('posts').select('author_id').where('id', '=', postId).executeTakeFirst();
      if (!post || post.author_id === liker || await this.blocked(liker, post.author_id)) return;
      const now = this.now();
      const last = this.lastLike.get(post.author_id);
      if (last !== undefined && now - last < LIKE_GAP_MS) return;
      this.lastLike.set(post.author_id, now);
      if (this.lastLike.size > 5000) for (const [k, v] of this.lastLike) if (now - v >= LIKE_GAP_MS) this.lastLike.delete(k);
      await this.deliver([post.author_id], { title: 'New like', body: `${await this.name(liker)} liked your post`, data: { type: 'like', postId } });
    });
  }

  /** SOS in a convoy: every other member, connected or not. */
  sos(code: string, memberIds: string[], from: string, note: string | undefined): void {
    this.run(async () => {
      await this.deliver(memberIds.filter((id) => id !== from), { title: `SOS from ${await this.name(from)}`, body: note || 'Needs help', data: { type: 'sos', room: code, userId: from } }, true);
    });
  }

  /** A hazard reported by a convoy member: only those with no live socket. */
  hazard(code: string, offlineIds: string[], reporter: string, kind: string, hazardId: string): void {
    this.run(async () => {
      await this.deliver(offlineIds.filter((id) => id !== reporter), { title: 'Convoy', body: HAZARD_TEXT[kind] ?? 'Hazard reported', data: { type: 'hazard', room: code, hazardId } });
    });
  }
}
