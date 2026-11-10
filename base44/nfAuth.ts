import { createClientFromRequest } from 'npm:@base44/sdk@0.8.54';

/**
 * NoteFloat account service.
 *
 * Actions (POST body):
 *   { action: "signup",  email, password, name }  -> { token, expiresAt, email, name }
 *   { action: "signin",  email, password }        -> { token, expiresAt, email, name }
 *   { action: "google",  accessToken }            -> { token, expiresAt, email, name }
 *     The Google token is verified with Google itself (userinfo + tokeninfo) before any session is
 *     issued, so a client cannot simply claim an email address.
 *   { action: "signout" }  (Authorization: Bearer <token>)
 *   { action: "delete_account" } (Authorization: Bearer <token>)
 *   { action: "me" }       (Authorization: Bearer <token>)
 *
 * Passwords are stored as PBKDF2-SHA256 (100k iterations) with a per-account salt.
 * Session tokens are random 32-byte values, stored server side, and expire after 180 days.
 * Nothing here trusts a client-supplied user id: the owner is always resolved from the token.
 */

const ITERATIONS = 100_000;
const SESSION_DAYS = 180;
const MAX_BODY = 256 * 1024;

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const fail = (message: string, status = 400) => json({ error: message }, status);

const enc = new TextEncoder();

function hex(buf: ArrayBuffer): string {
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

function randomHex(bytes: number): string {
  const a = new Uint8Array(bytes);
  crypto.getRandomValues(a);
  return [...a].map((b) => b.toString(16).padStart(2, '0')).join('');
}

async function hashPassword(password: string, salt: string, iterations: number): Promise<string> {
  const key = await crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', salt: enc.encode(salt), iterations, hash: 'SHA-256' },
    key,
    256,
  );
  return hex(bits);
}

/** Constant-time comparison so a wrong password cannot be timed character by character. */
function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function normaliseEmail(value: unknown): string {
  return String(value ?? '').trim().toLowerCase();
}

function validEmail(email: string): boolean {
  return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email) && email.length <= 254;
}

type Row = Record<string, any>;

async function rows(promise: Promise<any>): Promise<Row[]> {
  const r: any = await promise;
  if (Array.isArray(r)) return r;
  return Array.isArray(r?.items) ? r.items : [];
}

async function readBody(req: Request): Promise<Row> {
  const text = await req.text();
  if (text.length > MAX_BODY) throw new Error('Request too large');
  if (!text) return {};
  const parsed = JSON.parse(text);
  return typeof parsed === 'object' && parsed !== null ? parsed : {};
}

function bearer(req: Request): string | null {
  const h = req.headers.get('Authorization') ?? req.headers.get('authorization') ?? '';
  const m = /^Bearer\s+(.+)$/i.exec(h.trim());
  return m ? m[1].trim() : null;
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response(null, { status: 204 });
  if (req.method !== 'POST') return fail('Use POST', 405);

  try {
    const base44 = createClientFromRequest(req);
    const db = base44.asServiceRole.entities;
    const body = await readBody(req);
    const action = String(body.action ?? '').trim();

    if (action === 'signup' || action === 'signin') {
      const email = normaliseEmail(body.email);
      const password = String(body.password ?? '');
      if (!validEmail(email)) return fail('Enter a valid email address.');
      if (password.length < 8) return fail('Use at least 8 characters for the password.');
      if (password.length > 200) return fail('That password is too long.');

      const existing = await rows(db.NfAccount.filter({ email }));

      if (action === 'signup') {
        if (existing.length > 0) return fail('An account already uses that email. Sign in instead.', 409);
        const salt = randomHex(16);
        const passHash = await hashPassword(password, salt, ITERATIONS);
        const created = await db.NfAccount.create({
          email,
          name: String(body.name ?? '').trim().slice(0, 80),
          passHash,
          salt,
          iterations: ITERATIONS,
        });
        return json(await issueSession(db, created.id, email, String(body.name ?? '').trim()));
      }

      // signin: same message whether the account is missing or the password is wrong
      if (existing.length === 0) return fail('Email or password is incorrect.', 401);
      const account = existing[0];
      if (!account.passHash || Number(account.iterations ?? 0) < 1000) {
        return fail('This account signs in with Google. Use Continue with Google.', 409);
      }
      const attempts = Number(account.failedAttempts ?? 0);
      if (attempts >= 10) return fail('Too many failed attempts. Try again later or reset the password.', 429);
      const computed = await hashPassword(password, String(account.salt), Number(account.iterations ?? ITERATIONS));
      if (!safeEqual(computed, String(account.passHash))) {
        await db.NfAccount.update(account.id, { failedAttempts: attempts + 1 });
        return fail('Email or password is incorrect.', 401);
      }
      if (attempts > 0) await db.NfAccount.update(account.id, { failedAttempts: 0 });
      return json(await issueSession(db, account.id, email, String(account.name ?? '')));
    }

    if (action === 'google') {
      const accessToken = String(body.accessToken ?? '').trim();
      if (accessToken.length < 20 || accessToken.length > 4096) return fail('Google sign-in failed. Try again.', 401);

      const verified = await verifyGoogleToken(accessToken);
      if (!verified) return fail('Google could not confirm this account. Try again.', 401);

      const email = normaliseEmail(verified.email);
      if (!validEmail(email)) return fail('Google did not return a usable email address.', 401);

      const existing = await rows(db.NfAccount.filter({ email }));
      let account = existing[0];

      if (!account) {
        account = await db.NfAccount.create({
          email,
          name: String(verified.name ?? '').slice(0, 80),
          googleId: String(verified.sub ?? '').slice(0, 120),
          provider: 'google',
          passHash: '',
          salt: '',
          iterations: 0,
          lastSignInAt: Date.now(),
        });
      } else {
        const patch: Row = { lastSignInAt: Date.now() };
        if (!account.googleId && verified.sub) patch.googleId = String(verified.sub).slice(0, 120);
        await db.NfAccount.update(account.id, patch);
      }

      return json(await issueSession(db, account.id, email, String(account.name ?? verified.name ?? '')));
    }

    // ---- every action below needs a valid session ----
    const token = bearer(req);
    if (!token) return fail('Not signed in.', 401);
    const sessions = await rows(db.NfSession.filter({ token }));
    const session = sessions[0];
    if (!session) return fail('Not signed in.', 401);
    if (Number(session.expiresAt) < Date.now()) {
      await db.NfSession.delete(session.id);
      return fail('Session expired. Sign in again.', 401);
    }

    if (action === 'signout') {
      await db.NfSession.delete(session.id);
      return json({ ok: true });
    }

    if (action === 'me') {
      const account = (await rows(db.NfAccount.filter({ id: session.accountId })))[0];
      return json({ email: account?.email ?? '', name: account?.name ?? '', expiresAt: Number(session.expiresAt) });
    }

    if (action === 'delete_account') {
      const accountId = String(session.accountId);
      const allSessions = await rows(db.NfSession.filter({ accountId }));
      for (const s of allSessions) await db.NfSession.delete(s.id);
      const records = await rows(db.NfRecord.filter({ userId: accountId }));
      for (const r of records) await db.NfRecord.delete(r.id);
      await db.NfAccount.delete(accountId);
      return json({ ok: true, deleted: records.length });
    }

    return fail('Unknown action.', 400);
  } catch (e) {
    return fail(e instanceof Error ? e.message : 'Unexpected error', 500);
  }
});

/**
 * Verifies a Google OAuth access token with Google, never with the client's claims.
 *
 *   userinfo  proves the token is live and tells us which Google account owns it.
 *   tokeninfo proves the token was issued to an OAuth client (i.e. an app), not hand-crafted, and
 *   that it has not expired.
 *
 * Set GOOGLE_OAUTH_AUDIENCE (comma separated client ids) in the function's environment to also pin
 * the audience to clients in your own Google Cloud project; without it the check is limited to
 * "Google issued this token to this account".
 */
async function verifyGoogleToken(accessToken: string): Promise<{ email: string; name: string; sub: string } | null> {
  try {
    const userRes = await fetch('https://www.googleapis.com/oauth2/v3/userinfo', {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    if (!userRes.ok) return null;
    const user: any = await userRes.json();
    if (!user?.email || user.email_verified === false) return null;

    const infoRes = await fetch('https://oauth2.googleapis.com/tokeninfo?access_token=' + encodeURIComponent(accessToken));
    if (!infoRes.ok) return null;
    const info: any = await infoRes.json();
    if (!info?.aud) return null;
    if (Number(info.expires_in ?? 0) <= 0) return null;

    const pinned = String((globalThis as any).Deno?.env?.get?.('GOOGLE_OAUTH_AUDIENCE') ?? '').trim();
    if (pinned) {
      const allowed = pinned.split(',').map((v) => v.trim()).filter(Boolean);
      if (!allowed.includes(String(info.aud)) && !allowed.includes(String(info.azp ?? ''))) return null;
    }

    return { email: String(user.email), name: String(user.name ?? ''), sub: String(user.sub ?? '') };
  } catch {
    return null;
  }
}

async function issueSession(db: any, accountId: string, email: string, name: string) {
  const token = randomHex(32);
  const expiresAt = Date.now() + SESSION_DAYS * 86_400_000;
  await db.NfSession.create({ token, accountId, expiresAt });
  return { token, expiresAt, email, name };
}
