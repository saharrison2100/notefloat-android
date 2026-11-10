# Backend sources

These files are the source of truth for the two Base44 functions the app calls. They are deployed
from the Base44 agent (function names `nfAuth` and `nfSync`), not executed from this repo.

- `nfAuth.ts` - signup, signin, signout, me, delete_account. PBKDF2-SHA256 passwords, server-side sessions.
- `nfSync.ts` - push/pull sync with conflict copies, plus export and wipe.

Contract: `../documentation/API_SPECIFICATION.md`.
