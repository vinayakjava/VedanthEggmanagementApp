# Contract: Backup File Format and Google Drive Storage

**Format version**: 1 | **Extension**: `.ebmbackup` | **MIME**: `application/octet-stream`

This format is a long-lived contract: every future app version MUST be able to read every earlier
format version (Constitution III). Changes require a new `formatVersion` and an entry in the release
notes.

## File name

`EggBusiness_<shop-slug>_<yyyyMMdd-HHmm>.ebmbackup` — `shop-slug` is the ASCII-transliterated shop
name, at most 24 characters `[A-Za-z0-9-]`, or `shop` when transliteration yields nothing.

## Binary layout

| Offset | Size | Field | Notes |
|--------|------|-------|-------|
| 0 | 8 | magic | ASCII `EBMBAK\0\1` |
| 8 | 4 | headerLength | uint32 big-endian, ≤ 64 KiB |
| 12 | headerLength | header | UTF-8 JSON (below); used as GCM AAD |
| … | 12 | wrapNonce | random |
| … | 48 | wrappedKey | AES-256-GCM(KEK, K) = 32-byte ciphertext + 16-byte tag |
| … | payloadLength | payload | SQLCipher database file encrypted with K (R9) |

## Header JSON (plaintext; contains no business data)

```json
{
  "formatVersion": 1,
  "createdAt": "2026-11-07T21:30:05Z",
  "appVersionName": "1.0.0",
  "appVersionCode": 10000,
  "schemaVersion": 1,
  "deviceModel": "Samsung SM-A155F",
  "trigger": "MANUAL",
  "kdf": { "alg": "PBKDF2WithHmacSHA256", "iterations": 600000, "salt": "<base64 16 bytes>" },
  "payloadLength": 1835008,
  "payloadSha256": "<hex>"
}
```

Shop name, customer counts and every other business detail are read **after** decryption from the
payload (the summary screen, US9-6). They are never written in plaintext.

## Cryptography

| Item | Rule |
|------|------|
| K (payload key) | 32 random bytes from `SecureRandom`, new for every backup |
| KEK | PBKDF2-HMAC-SHA256(passphrase UTF-8 NFKC, salt, iterations) → 32 bytes |
| Wrap | AES-256-GCM, 96-bit nonce, 128-bit tag, AAD = exact header bytes |
| Payload | `sqlcipher_export` of the live DB into an attached DB keyed with raw key K; SQLCipher 4 defaults (AES-256, HMAC-SHA512 per page) |
| Integrity | `payloadSha256` over the payload bytes; checked before opening |

Passphrase change: a new salt and a new KEK, and the stored wrapped KEK is replaced. Old backups
keep their own salt, so they still need the old passphrase (US12-9).

## Restore validation order and outcomes

| Step | Failure → `BackupFailure` | User message (spec) |
|------|---------------------------|---------------------|
| 1. Magic and header parse, `formatVersion` known | `NOT_A_BACKUP` (unknown magic) / `NEWER_VERSION` (format too new) | "This file is not an Egg Business Manager backup." / "…newer version of the app…" |
| 2. Unwrap K with KEK from the entered passphrase | `WRONG_PASSPHRASE` | "Incorrect passphrase." (counts toward lockout) |
| 3. Length and SHA-256 match | `CORRUPT` | "This backup is damaged and cannot be restored." |
| 4. Open payload with K; `PRAGMA integrity_check` = ok; required tables present | `CORRUPT` | as above |
| 5. `PRAGMA user_version` ≤ app schema version | `NEWER_VERSION` | "This backup was made by a newer version of the app. Please update the app first." |
| 6. Re-key into staging with the device DB key; read `BackupSummary` | `LOW_STORAGE` / `UNKNOWN` | "Not enough storage…" / generic |

Nothing in the current data changes before the user confirms the summary (FR-050). Swap and
rollback follow the R10 state machine.

## Google Drive storage

- **Scope**: `https://www.googleapis.com/auth/drive.appdata` only.
- **Folder**: `appDataFolder` (hidden from the Drive UI; deleted by Google if the user removes the
  app's access *and* deletes its hidden data).
- **Upload**: `POST https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable`,
  metadata `{ name, parents: ["appDataFolder"], appProperties: {...} }`, then a PUT of the bytes in
  8 MiB chunks; resume on retry.
- **appProperties** (each value ≤ 124 bytes): `ebmFormat=1`, `createdAt`, `trigger`
  (`MANUAL`|`AUTOMATIC`), `appVersion`, `schemaVersion`, `device`, `sizeBytes`.
- **List**: `GET /drive/v3/files?spaces=appDataFolder&q=appProperties has { key='ebmFormat' and value='1' }&fields=files(id,name,size,createdTime,appProperties)&orderBy=createdTime desc`.
- **Download**: `GET /drive/v3/files/{id}?alt=media` → `cacheDir/restore/download.ebmbackup`.
- **Delete**: `DELETE /drive/v3/files/{id}`.
- **Retention**: after a successful AUTOMATIC upload, delete AUTOMATIC files beyond the newest 10.
  MANUAL files are never deleted automatically (FR-049).
- **Error mapping**: no connectivity/IO → `NO_NETWORK`; HTTP 401 or authorization needing resolution
  → `AUTH_REQUIRED`; 403 `storageQuotaExceeded` → `DRIVE_FULL`; 403/429 rate limit → retry with
  backoff; 5xx → retry; anything else → `UNKNOWN`.
