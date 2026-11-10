# NoteFloat backend API

Cloud sync is optional. Everything in the app works offline; the backend only mirrors data between
devices. Two functions, both hosted on Base44, both called with `POST` and JSON bodies.

Base URL: `https://neo-58a47037.base44.app/functions/`

## nfAuth - accounts

| Action | Body | Response |
| --- | --- | --- |
| `signup` | `{email, password, name}` | `{token, expiresAt, email, name}` |
| `signin` | `{email, password}` | `{token, expiresAt, email, name}` |
| `signout` | empty, `Authorization: Bearer <token>` | `{ok: true}` |
| `delete_account` | empty, bearer | `{ok: true, deleted: n}` |
| `me` | empty, bearer | `{email, name, expiresAt}` |

Rules:

1. Passwords: PBKDF2-HMAC-SHA256, 100,000 iterations (the platform maximum), 16-byte random salt,
   constant-time comparison. Plain passwords are never stored or logged.
2. Sessions: 32 random bytes in `NfSession`, valid 180 days, revoked on signout and account deletion.
3. Sign-in answers `Email or password is incorrect.` for both a missing account and a wrong password.
4. Ten consecutive failed attempts on one account answer `429` until a successful sign-in resets the counter.
5. Error responses are `{"error": "..."}` with 400/401/409/429/500.

## nfSync - records

`POST {action: "sync", cursor, notes[], categories[], tags[], reminders[], clipboard[], voice[]}`

Response:

```json
{
  "cursor": 1794322603596,
  "accepted": {"note": {"<id>": 1794322603596}, "category": {}, "...": {}},
  "conflicts": [{"id": "<note id>", "server": {...}}],
  "changes": {"notes": [], "categories": [], "tags": [], "reminders": [], "clipboard": [], "voice": []}
}
```

- `cursor` is a server millisecond timestamp. Send back the last value received to pull only newer rows.
- `accepted` holds the server timestamp per pushed record so the device can mark it clean.
- `changes` holds everything newer than the sent cursor that this call did not just write.
- Records are stored one per `NfRecord` row: `{userId, kind, recordId, updatedAt, serverUpdatedAt, deleted, payload}`,
  where `payload` is the exact JSON the device sent. Adding a field to a note needs no server change.

### Conflict policy

1. A note push carries `baseServerUpdatedAt`, the server timestamp the device last saw. If the server
   row has not moved since, the edit is accepted.
2. If both sides moved, nothing is discarded: the server keeps its row and reports the note in
   `conflicts`; the client keeps its version as "<title> (conflict copy)". Both remain visible.
3. An edit after a delete resurrects the note.
4. Categories, tags, reminders, clipboard and voice metadata are last-writer-wins on `updatedAt`.

### Other actions

| Action | Effect |
| --- | --- |
| `export` | `{exportedAt, notes[], categories[], tags[], reminders[], clipboard[], voice[]}` for the account |
| `wipe` | deletes every synced record for the account |

Both require a bearer token. A caller can never read or write another account's rows: the owner is
resolved from the session token, never from the request body.

## Storage entities (Base44)

| Entity | Fields |
| --- | --- |
| `NfAccount` | email, name, passHash, salt, iterations, failedAttempts |
| `NfSession` | token, accountId, expiresAt |
| `NfRecord` | userId, kind, recordId, updatedAt, serverUpdatedAt, deleted, payload |

Server-side access uses the service role inside the functions, so no entity row is exposed directly
to a client.
