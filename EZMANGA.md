# EZManga

EZManga is a local-only companion for Mihon 0.21.2. It sends CBZ files from an
Android phone to an Android TV and reads them on the TV. The phone's original
document is never moved, modified, or deleted.

This repository is the original Mihon 0.21.2 source tree with three additional
Gradle modules:

| Module | Purpose |
| --- | --- |
| `ezmanga-core` | Pairing payloads, six-digit codes, QR encoding, CBZ inspection, the TV HTTP server, the phone HTTP client, and the TV-local library store |
| `ezmanga-tv` | Android TV app: pairing screen, library, Mihon-archive-backed reader, TV-only deletion |
| `ezmanga-mobile` | Phone app: QR scanner, six-digit pairing, mDNS discovery, manual address fallback, CBZ picker, streaming upload, TV library status |

The rest of the repository is Mihon. EZManga does not replace or modify Mihon's
reader UI, sources, database, or license notices. The TV reader reuses Mihon's
`:core:archive` module (`mihon.core.archive.ArchiveReader`) to open CBZs.

---

## Pairing

The TV starts a small HTTP server on a random port and displays two ways to
pair:

1. **QR code** — contains an `ezmanga://pair?...` URI with the TV address,
   port, device name, protocol version, and a per-process bearer token.
2. **Six-digit code** — changes every five minutes. The countdown and a
   progress bar are shown on the TV.

The phone can scan the QR code or type the current six-digit code. Typing a code
calls `POST /api/pair`; a valid code is exchanged for the same bearer token that
the QR code contains. The token is kept in phone memory only. It is not written
to disk, and it becomes unusable when the TV process restarts.

The TV advertises itself over Android NSD/mDNS as `_ezmanga._tcp.`. The phone
uses that for automatic discovery, but always offers a manual `IP:port` field.
Manual entry is the reliable fallback when multicast is blocked by a router,
when the devices are on isolated guest networks, or when using emulators.

### Code rotation and throttling

- Codes are six decimal digits generated with `SecureRandom`.
- A code is valid for exactly five minutes from its creation.
- The TV UI polls the server every second, so a new code appears automatically
  when the old one expires.
- The server verifies the currently active code only. An old code is rejected
  with HTTP 401.
- Eight failed guesses in a one-minute window cause HTTP 429 with a
  `retryAfterSeconds` value; the window then resets. A successful pairing
  clears the failure count.
- Codes are never logged. The debug build logs the QR pairing URI (including
  its token) only to help local development.

The six-digit code is a convenience alternative to the QR code, not a
replacement for it. The QR code is not time-limited: it stays valid until the
TV process exits because it embeds the long-lived process token.

---

## HTTP API

All responses are JSON with `Cache-Control: no-store`. Requests other than
`/api/ping` and `/api/pair` require the bearer token in the
`X-EzManga-Token` header.

| Method | Path | Auth | Description |
| --- | --- | --- | --- |
| `GET` | `/api/ping` | no | Liveness check |
| `POST` | `/api/pair` | no | Exchange `{"code":"123456"}` for `{"token":...,"deviceName":...,"version":1}` |
| `GET` | `/api/info` | yes | TV name and protocol version |
| `GET` | `/api/library` | yes | Books currently stored on the TV |
| `POST` | `/api/upload?filename=...` | yes | Stream a CBZ body into the TV library |
| `DELETE` | `/api/books/{id}` | yes | Delete one TV-local copy |
| `GET` | `/api/books/{id}/pages` | yes | Page entry names inside the CBZ |

The upload request must include `Content-Length`; bodies are capped at 1.5 GB.
Pair request bodies are capped at 4 KB. Uploads are written to
`files/ezmanga-library/incoming/`, inspected as a CBZ, and then moved into a
per-book directory under `files/ezmanga-library/`.

### Error statuses

| Status | Meaning |
| --- | --- |
| `401` | Missing/invalid token, or expired/incorrect pairing code |
| `404` | Book or endpoint not found |
| `411` | Upload/pair request is missing `Content-Length` |
| `413` | Upload or pair request is too large |
| `422` | Uploaded file is not a valid CBZ |
| `429` | Too many incorrect pairing codes; retry later |
| `500` | Local server/storage error |

---

## Storage and deletion guarantee

The TV owns only its own copy. `LocalBookStore` creates a new directory for
every upload, writes the copied CBZ as `book.cbz`, and stores metadata next to
it. Deletion removes that directory. The phone sends a byte stream and keeps
the source document untouched.

Both the TV delete dialog and the phone success message state this explicitly.
The runtime verification recorded in this repository used a five-page sample
CBZ: with three books on the TV, deleting one left two books on the TV, and the
phone's SHA-256 stayed
`1b1b208487620c57c4380e4477c1e1697e74360194f4b6a0504c2a21a3e81c45`.

---

## TV interface

The TV app follows Mihon's Material 3 look and dark palette
(`MaterialExpressiveTheme`). The library uses a compact top app bar with a
book-count pill and connection status, a focusable cover grid with titles below
covers, and page-count/format badges. The pairing screen presents the QR code
and six-digit code in a bordered card with the rotating countdown and the TV
address. Cover thumbnails are decoded through `MangaPageLoader` and kept in an
18 MB LRU cache.

The reader keeps compact overlays for page position and D-pad hints. Delete
confirmation is a full-window dialog with isolated focus; use a direction to
focus Cancel or Delete TV copy, then press OK. Deleting removes only the
TV-local directory.

---

## Reader and performance

The TV reader uses `MangaPageLoader`, which:

- reuses one mmap-backed `ArchiveReader` for the lifetime of the reader instead
  of reopening the CBZ on every page;
- uses an `LruCache` bounded by bytes (roughly 1/8 of the available heap,
  clamped to 24–64 MB);
- decodes pages with `BitmapFactory` and only downsamples when necessary;
- prefetches the next and previous pages after the current page is visible;
- closes the archive and evicts the cache when the reader leaves composition.

Page order comes from `CbzInspector`, which sorts archive entries naturally so
`2.png` comes before `10.png`. D-pad left/right and on-screen Previous/Next
controls turn pages.

Phone uploads avoid a second disk copy when the document provider reports a
size: the selected `InputStream` is streamed directly to the TV with a fixed
`Content-Length`. If the provider does not report a size, the app falls back to
a temporary cache copy and removes it afterward. Progress is reported from the
actual bytes written.

Successful TV library refreshes show a short message for four seconds. Opening
a book clears any pending banner so it cannot cover the page.

---

## Build and test

Requirements: JDK 21 and an Android SDK. From the repository root:

```powershell
$env:JAVA_HOME="C:\path\to\jdk-21"
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"

.\gradlew.bat :ezmanga-core:testDebugUnitTest `
  :ezmanga-tv:assembleDebug `
  :ezmanga-mobile:assembleDebug `
  :ezmanga-core:spotlessCheck `
  :ezmanga-tv:spotlessCheck `
  :ezmanga-mobile:spotlessCheck --console=plain
```

The debug APKs are written to:

- `ezmanga-tv/build/outputs/apk/debug/ezmanga-tv-debug.apk`
- `ezmanga-mobile/build/outputs/apk/debug/ezmanga-mobile-debug.apk`

Tests cover QR payload round-trips, QR encoding, address parsing, six-digit
rotation and expiry, wrong-code throttling and recovery, token rejection,
upload/list/delete behavior, malformed CBZ rejection, and preservation of the
phone source file.

The unmodified Mihon baseline was also built with `:app:assembleDebug` before
EZManga changes. The only pre-existing baseline warning was that the checkout
is not a Git repository, so Mihon's commit count and SHA defaulted to `0` and
`unknown`.

### Emulator note

Two Android emulators cannot reach each other's internal `10.0.2.15` address.
For local testing, forward the TV server port on the host and use
`10.0.2.2:<forwarded-port>` in the phone's manual address field:

```powershell
adb -s emulator-5554 forward tcp:40537 tcp:40537
# On the phone, enter 10.0.2.2:40537 (or the current TV port).
```

mDNS discovery may not cross the emulator NAT; manual entry is intentional and
always available.

---

## Known limitations

- Traffic is plain HTTP on the local network. The bearer token protects the API,
  but EZManga does not provide TLS or encryption against a hostile LAN.
- The TV port is random and changes when the TV process restarts. The phone
  must pair again after that (the old token is invalid).
- Pairing is memory-only on the phone; closing the phone app or restarting the
  phone requires pairing again. This is deliberate, but it is not a persistent
  "remember this TV" experience yet.
- mDNS can be blocked by access points, VPNs, or client isolation. Use the
  manual address field in those cases.
- QR scanning needs a real camera. Emulator images without a virtual camera
  show JourneyApps' "camera encountered a problem" dialog; use the six-digit
  code or the manual pairing link in that case.
- The server requires `Content-Length` and does not implement chunked transfer
  encoding. The bundled phone app always supplies a length.
- A TV code is global to the server, not per-phone. Anyone who can see the code
  and reach the TV can pair during its lifetime. This matches the local-only
  threat model; the code rotates every five minutes.
- The TV displays the QR/code while it is open, including after a phone has
  connected, so a second phone can pair. Restart the TV app to invalidate
  existing pairings.
- In the TV emulator, the first focus ring appears after the first D-pad press
  (on the first library item, or on Cancel/Delete in the delete dialog). All
  actions are reachable from there; physical TV hardware was not available to
  verify this behavior.

---

## Attribution and license

This work is based on Mihon 0.21.2. Mihon's original source, Gradle setup,
notices, and license remain in place. Mihon is licensed under the Apache
License, Version 2.0; see the repository `LICENSE` file. The EZManga modules
are distributed under the same license as part of this tree. See `NOTICE` for
the upstream attribution.

The reader integrates Mihon's `:core:archive` module directly. QR generation
uses ZXing Core (`com.google.zxing:core`), and the phone's QR scanner uses
JourneyApps' ZXing Android Embedded. Their licenses and notices remain with
their respective projects.
