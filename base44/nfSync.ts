import { createClientFromRequest } from 'npm:@base44/sdk@0.8.54';

/**
 * NoteFloat sync service.
 *
 * POST { action: "sync", cursor, notes[], categories[], tags[], reminders[], clipboard[], voice[] }
 *   -> { cursor, accepted: { notes: { <id>: <serverUpdatedAt> } }, conflicts: [{ id, server }], changes: {...} }
 * POST { action: "export" } -> { exportedAt, notes[], categories[], tags[], reminders[], clipboard[], voice[] }
 * POST { action: "wipe" }   -> deletes every synced record for the caller
 *
 * CONFLICT POLICY (matches the client, see SyncEngine.kt)
 *   1. Notes send the server timestamp they last saw as baseServerUpdatedAt. If the server row has not
 *      moved since then, the client edit is accepted. No conflict.
 *   2. If both sides changed the note, nothing is thrown away: the server keeps its row, reports the
 *      conflict, and the client keeps its own version as a "(conflict copy)" note.
 *   3. An edit made after a delete resurrects the note.
 *   4. Every other record type (categories, tags, reminders, clipboard, voice metadata) is
 *      last-writer-wins on updatedAt.
 *
 * Storage: one NfRecord row per object, payload is the JSON the device sent, so the server never
 * has to re-derive field lists as the app evolves. Rows are scoped to the account resolved from the
 * bearer token; a caller can never read or write another user's data.
 */

const MAX_ITEMS = 500;
const MAX_BODY = 3 * 1024 * 1024;
const KINDS = ['notes', 'categories', 'tags', 'reminders', 'clipboard', 'voice'] as const;
type Kind = typeof KINDS[number];

const KIND_OF: Record<Kind, string> = {
  notes: 'note',
  categories: 'category',
  tags: 'tag',
  reminders: 'reminder',
  clipboard: 'clipboard',
  voice: 'voice',
};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const fail = (message: string, status = 400) => json({ error: message }, status);

type Row = Record<string, any>;

async function rows(promise: Promise<any>): Promise<Row[]> {
  const r: any = await promise;
  if (Array.isArray(r)) return r;
  return Array.isArray(r?.items) ? r.items : [];
}

async function fetchAll(db: any, userId: string): Promise<Row[]> {
  const out: Row[] = [];
  let cursor: string | undefined = undefined;
  for (let page = 0; page < 60; page++) {
    const res: any = await db.NfRecord.filter({ userId }, { limit: 500, sort: 'serverUpdatedAt', ...(cursor ? { cursor } : {}) });
    const items: Row[] = Array.isArray(res) ? res : (res?.items ?? []);
    out.push(...items);
    if (Array.isArray(res) || !res?.has_more || !res?.next_cursor) break;
    cursor = res.next_cursor;
  }
  return out;
}

function bearer(req: Request): string | null {
  const h = req.headers.get('Authorization') ?? req.headers.get('authorization') ?? '';
  const m = /^Bearer\s+(.+)$/i.exec(h.trim());
  return m ? m[1].trim() : null;
}

async function readBody(req: Request): Promise<Row> {
  const text = await req.text();
  if (text.length > MAX_BODY) throw new Error('Request too large');
  if (!text) return {};
  const parsed = JSON.parse(text);
  return typeof parsed === 'object' && parsed !== null ? parsed : {};
}

/** The stored payload is exactly what the device sent; server timestamps are added, never trusted. */
function payloadOf(row: Row): Row {
  try {
    const p = JSON.parse(String(row.payload ?? '{}'));
    if (typeof p === 'object' && p !== null) {
      p.serverUpdatedAt = Number(row.serverUpdatedAt ?? p.updatedAt ?? 0);
      return p;
    }
  } catch { /* fall through to a minimal shell */ }
  return { id: row.recordId, updatedAt: Number(row.updatedAt ?? 0), serverUpdatedAt: Number(row.serverUpdatedAt ?? 0) };
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response(null, { status: 204 });
  if (req.method !== 'POST') return fail('Use POST', 405);

  try {
    const base44 = createClientFromRequest(req);
    const db = base44.asServiceRole.entities;

    const token = bearer(req);
    if (!token) return fail('Not signed in.', 401);
    const session = (await rows(db.NfSession.filter({ token })))[0];
    if (!session) return fail('Session expired. Sign in again.', 401);
    if (Number(session.expiresAt) < Date.now()) return fail('Session expired. Sign in again.', 401);
    const userId = String(session.accountId);

    const body = await readBody(req);
    const action = String(body.action ?? 'sync').trim();

    if (action === 'export') {
      const all = await fetchAll(db, userId);
      const out: Row = { exportedAt: Date.now(), email: '' };
      for (const kind of KINDS) out[kind] = all.filter((r) => r.kind === KIND_OF[kind]).map(payloadOf);
      return json(out);
    }

    if (action === 'wipe') {
      const all = await fetchAll(db, userId);
      for (const r of all) await db.NfRecord.delete(r.id);
      return json({ ok: true, deleted: all.length });
    }

    if (action !== 'sync') return fail('Unknown action.', 400);

    const now = Date.now();
    let cursor = Number(body.cursor ?? 0);
    if (!Number.isFinite(cursor) || cursor < 0) cursor = 0;

    const existing = await fetchAll(db, userId);
    const index = new Map<string, Row>();
    for (const r of existing) index.set(`${r.kind}:${r.recordId}`, r);

    const accepted: Record<string, Record<string, number>> = {};
    for (const k of KINDS) accepted[KIND_OF[k]] = {};
    const conflicts: Row[] = [];
    const written = new Set<string>();

    for (const kind of KINDS) {
      const list = Array.isArray(body[kind]) ? (body[kind] as Row[]).slice(0, MAX_ITEMS) : [];
      for (const item of list) {
        const id = String(item?.id ?? '').trim();
        if (!id || id.length > 80) continue;
        const clientUpdatedAt = Number(item.updatedAt ?? now);
        const payload = JSON.stringify(item);
        const key = `${KIND_OF[kind]}:${id}`;
        const row = index.get(key);

        if (!row) {
          const created = await db.NfRecord.create({
            userId, kind: KIND_OF[kind], recordId: id,
            updatedAt: clientUpdatedAt, serverUpdatedAt: now,
            deleted: Boolean(item.deleted ?? item.trashed), payload,
          });
          index.set(key, created);
          written.add(key);
          accepted[KIND_OF[kind]][id] = now;
          continue;
        }

        const serverUpdatedAt = Number(row.serverUpdatedAt ?? 0);
        const serverUpdatedAtLocal = Number(row.updatedAt ?? 0);

        if (kind === 'notes') {
          const base = Number(item.baseServerUpdatedAt ?? 0);
          const serverMoved = base > 0 && serverUpdatedAt > base + 2; // 2 ms tolerance for rounding
          const resurrecting = Boolean(row.deleted);
          if (!serverMoved || resurrecting || clientUpdatedAt >= serverUpdatedAtLocal) {
            await db.NfRecord.update(row.id, {
              updatedAt: clientUpdatedAt, serverUpdatedAt: now,
              deleted: Boolean(item.trashed), payload,
            });
            accepted.note[id] = now;
            written.add(key);
          } else {
            conflicts.push({ id, server: payloadOf(row) });
          }
        } else {
          // last writer wins
          if (clientUpdatedAt >= serverUpdatedAtLocal) {
            await db.NfRecord.update(row.id, {
              updatedAt: clientUpdatedAt, serverUpdatedAt: now,
              deleted: Boolean(item.deleted), payload,
            });
            accepted[KIND_OF[kind]][id] = now;
            written.add(key);
          }
        }
      }
    }

    const changes: Record<string, Row[]> = {};
    for (const k of KINDS) changes[k] = [];
    let maxSeen = cursor;
    for (const row of index.values()) {
      const ts = Number(row.serverUpdatedAt ?? 0);
      if (ts > maxSeen) maxSeen = ts;
      const key = `${row.kind}:${row.recordId}`;
      if (ts <= cursor || written.has(key)) continue;
      const target = KINDS.find((k) => KIND_OF[k] === row.kind);
      if (target) changes[target].push(payloadOf(row));
    }

    return json({ cursor: Math.max(maxSeen, now), accepted, conflicts, changes });
  } catch (e) {
    return fail(e instanceof Error ? e.message : 'Unexpected error', 500);
  }
});
