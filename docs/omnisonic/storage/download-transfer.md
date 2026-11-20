# Resumable download transfer — `download-transfer.md`

Purpose: pin the HTTP transfer contract behind Phase 7 tasks 1–2: fresh per-attempt URL resolution through the provider MEDIA client, identity encoding, `If-Range`-validated 206 resume, the complete response/restart matrix, progress and settlement rules, pause/cancel behaviour, provider-media failover, and the finalize guard.

How derived: the task plan `.agents/plans/phase-7-tasks.md` §TODO 3, own design decision D-41, the task 1 queue contract (`storage/download-queue-lifecycle.md`), the task 2 storage contract (`storage/offline-storage-layout.md`), and the shipped `:core-data` `downloads` transfer package plus its MockWebServer tests. All behaviour is reasoned from HTTP/1.1 range semantics and plain `java.io`/`java.nio` file operations; no studied-app code or naming is involved.

Confidence: the request/response matrix, validator persistence, restart budget, progress bounds, settlement outcomes, failover ordering and finalize refusal are [VERIFIED] by JVM tests. Live-server byte-range behaviour, throughput, power-loss durability of the finalize rename and device filesystem behaviour remain [UNKNOWN] without a device or live-server trace.

Date: 2026-11-20

Links: `storage/download-queue-lifecycle.md`, `storage/offline-storage-layout.md`, `providers/provider-connections.md`, `.agents/plans/phase-7-tasks.md`, D-41 in `../decisions.md`.

---

## Attempt shape

One attempt handles one claimed row ([VERIFIED]):

1. Read the row and parse its `mediaKey` into `DownloadStreamKey` (`providerId`, `externalId`, bitrate, format). An unparseable key parks the row `FAILED` with `download parked: invalid queue key`; the provider id in the key must match the row.
2. Run `prepare(0)` before any provider/media resolution, so a blocked root cannot trigger even the unsynced `songDetails` fallback.
3. Resolve the media source: provider row, registry-backed download capability, provider + MEDIA client, the song-snapshot path (fresh `songDetails` fallback), and at least one download endpoint. Nothing here falls back to a plain client; a provider that cannot be rebuilt fails closed. Fallback auth and transport failures stay distinguishable from a permanently missing path.
4. Plan the variant path with `OfflineStorage.planPath` (task 2), resolve the `.part` and final paths with `resolveStoredPath`, then run `prepare(remainingBytes)` for the 100 MiB floor.
5. Resume state is the `.part` length plus the `<hash>.part.state` sidecar. The sidecar is discarded when its identity hash differs, when the part is empty, when there is no part file, or when it carries no usable validator; unverifiable bytes are never appended.
6. Request, validate, stream, and settle.

Each attempt re-resolves the URL: signed/expiring query parameters are never persisted. [VERIFIED]

## Request

- `Accept-Encoding: identity` is always sent explicitly, so OkHttp neither advertises gzip nor transparently decompresses. The production MEDIA client preserves that explicit header while still suppressing its default transparent encoding for other media calls. [VERIFIED through engine and `HttpClientFactoryTest`]
- Resuming sends `Range: bytes=<part length>-` and `If-Range: <validator>`. It is only attempted when a validator exists: a syntactically quoted strong `ETag` (weak or unquoted tags are refused) or `Last-Modified`. No validator means a fresh full request; an unsolicited 206 cannot authorize an append. [VERIFIED]
- The URL and its query string never enter `lastError`, a log line or a persisted file. Failure text is a fixed code such as `transfer failed: server status`. [VERIFIED]

## Response matrix

| Case | Handling |
|---|---|
| HTTP 200, fresh | Truncate the part, write the body, record validator/total |
| HTTP 200 while resuming (ignored `Range` or changed entity) | Truncate and write the full body; never append. Not a restart-budget event |
| HTTP 206 with a valid `Content-Range` starting at the part length | Append |
| HTTP 206 with missing/malformed `Content-Range`, another start offset, a changed response validator, a mismatched `Content-Length`/total, or a total that disagrees with the sidecar | Discard the part and restart from zero (one restart) |
| HTTP 206 with `Content-Range` total `*` and no prior known total | Counted short-body failure; never finalize a possible prefix |
| Body longer than its declared 206 range | Counted length-mismatch failure; discard the part |
| HTTP 416 | Discard the part and restart from zero (one restart) |
| HTTP 401/403 | Report the provider status tracker, uncounted block |
| Other status | Counted failure (`server status`) |
| Transport failure after all ordered endpoints fail | Uncounted `NETWORK_UNAVAILABLE` block; preserve the truthful part and leave `updatedAt` unchanged |
| `Content-Encoding` other than identity/absent | Counted failure, part and sidecar discarded |
| `Content-Type` text/JSON/XML/HTML, or a body whose first non-whitespace byte is `<`, `{` or `[` | Counted failure (`not media`), part discarded — a login/proxy/protocol body is never published as audio |
| Unknown/chunked length | Stream to EOF, indeterminate progress, finalize on clean EOF |
| Body shorter than `Content-Length`/`Content-Range`/known total | Counted failure (`short body`), part preserved for the next resume |
| Zero-byte body | Counted failure (`empty body`) |
| Part equals its validated total | Finalize without a request (crash recovery between body and rename) |

- The restart budget is exactly one per attempt. A second restart-triggering response becomes a counted failure, so a misbehaving server cannot loop. [VERIFIED]
- A full 200 body is never appended to existing bytes; stale bytes are truncated first. [VERIFIED]
- A counted failure keeps the `.part` and its sidecar when the bytes are a truthful prefix, and counts through the task-1 rules (1–5 `PENDING` with `errorCount × 10 s`, the sixth parks `FAILED`). [VERIFIED]
- Once response headers reveal a total, the remaining byte count is checked against the free-space floor before the first body write. A write failure rechecks storage; root loss or disk exhaustion is an uncounted block rather than a generic retry. [VERIFIED JVM 2026-11-20 audit]
- Payload sniffing accumulates across fragmented reads. Auth success is reported only after a complete validated media body, so an HTTP 200 JSON/HTML login or protocol error cannot clear an auth-failure marker. [VERIFIED JVM 2026-11-20 audit]

## Sidecar

`<hash>.part.state` is a small deterministic text file beside the extensionless part:

```
v=1
identity=<sha-256 hex of the variant identity>
etag=<strong ETag or empty>
last=<Last-Modified or empty>
total=<expected bytes or empty>
```

- It is written atomically (sibling `.tmp`, flush, `sync`, replace) before the first body byte, so a process death mid-body still records the validator and expected length. A 200 response truncates the old part before writing its new sidecar, so a crash cannot retag old bytes as the new representation. [VERIFIED implementation order; fault-injection power-loss coverage UNKNOWN]
- A corrupt, oversized, duplicate-key, malformed-total, weak-ETag or foreign-identity sidecar decodes to null and the part is discarded. No queue column and no migration is involved. [VERIFIED]
- `If-Range` uses the strong ETag first, then `Last-Modified`. [VERIFIED]
- The part-equals-total shortcut trusts the sidecar that was written when the bytes were verified; a server-side change inside that window is not re-validated. [INFERRED — a later revalidation pass can add a conditional one-byte probe]

## Progress

- `progressPercent = bytesOnDisk × 100 / total`, bounded 0–100; `-1` when the total is unknown. The persisted marker is `-1`. [VERIFIED]
- Writes go through `coordinator.reportProgress`, so the conditional `ACTIVE` row write and the lease fence still apply; a stale worker writes nothing. [VERIFIED]
- Writes are throttled: at most one per 250 ms while the percentage moves, plus forced writes for the first value, `100`, and transitions into/out of the indeterminate state. [VERIFIED]

## Pause, cancel and settlement

- The coordinator exposes one one-shot interruption signal per lease. `pauseAll`, `pauseOne` and `cancel` complete it after their conditional row writes; `release`/`complete`/`park` complete a still-pending signal with `null`. [VERIFIED]
- The transfer watches the signal, cancels the OkHttp call immediately (including while waiting for headers), and checks it before every read and before finalize. Coroutine cancellation additionally cancels the call and is never counted. [VERIFIED]
- Pause keeps the `.part` and sidecar, releases the lease without an error increment, and returns to `PAUSED`/`PENDING` by the global gate. [VERIFIED]
- Cancel during an active engine attempt deletes the `.part` and sidecar, releases the lease and reports `Cancelled`. A finalized file is never deleted by a later cancel of the same row. A row deleted before its engine has resolved a path returns `LeaseLost`; any pre-existing pathless orphan from that race, or from cancelling a non-active row, is deliberately left for task 5's identity-checked orphan sweep rather than guessed from another row. [VERIFIED active path; sweep deferred]
- Counted failure releases the lease after writing the failure so the worker slot is immediately reusable. Coroutine cancellation also cancels the call and releases the lease in a non-cancellable cleanup without counting. A permanent park that loses its conditional write to pause/cancel returns `LeaseLost` and releases the slot rather than claiming a successful park. [VERIFIED JVM 2026-11-20 audit]
- Terminal fail-closed conditions park `ACTIVE → FAILED` with a sanitized `lastError` and no error increment: missing/unsupported provider, missing media path, unparseable key, unplannable path, existing final file. Parking leaves `updatedAt` untouched; only explicit retry revives the row, so a permanent condition cannot spin. [VERIFIED]
- Storage/auth blocks release the row uncounted: `ROOT_UNAVAILABLE`, `NOT_WRITABLE`, `LOW_SPACE`, `AUTH_REJECTED`. Auth also feeds the provider status tracker, whose run gate then blocks later claims. [VERIFIED]

## Provider-media failover

- `DownloadMediaSource` resolves an ordered endpoint list that always carries the registry MEDIA client; there is no plain-client fallback. [VERIFIED]
- `MediaProvider.downloadEndpoints` defaults to empty (single `downloadUrl`); the subsonic implementation returns the preferred block first and the alternate block second, rebuilding auth parameters per block. [VERIFIED]
- A transport failure before the body completes moves to the next endpoint. The next request resumes from the part length with `If-Range`; if the representation changed, the response is a 200 and the part is rewritten from zero, so bytes from two representations are never concatenated. [VERIFIED]
- A complete validated 2xx media body clears a stale auth marker; a 401/403 reports the rejection. Protocol payloads and incomplete bodies do not clear it. [VERIFIED]

## Finalize

- The part must be non-empty and, when the total is known, exactly equal to it; oversized parts are discarded and counted. [VERIFIED]
- The file is flushed, `sync`ed and closed successfully before the move. A sync/close failure does not publish it. The move is a same-directory rename that never replaces an existing target. An existing final path parks the row (`file conflict`) instead of overwriting. [VERIFIED JVM policy; filesystem fault injection and power loss UNKNOWN]
- A per-path mutex serializes finalizes in this process; variant-qualified hashing already gives each quality/format variant its own final path, so two variants cannot corrupt one file. Replacement-generation order is deliberately not defined here — that belongs to the confirmed task-5/8 policy. [VERIFIED policy; replacement policy UNKNOWN]
- After the rename the sidecar is deleted and the queue row is deleted through the lease-fenced `complete`. Task 5 replaces that settlement with the `offline_media` publication transaction and must retain verification state until that transaction commits; it cannot reuse the current early sidecar deletion unchanged. [INFERRED — publication seam, crash recovery owned by task 5]
- A pause or cancel that lands in the sub-millisecond window between rename and settlement leaves a complete file without a matching queue row; the file is never overwritten and task 5's orphan/adoption flow owns it. [VERIFIED policy]
- Power-loss durability of the rename itself (directory metadata) is [UNKNOWN] without a device trace.

## Deliberately out of scope

Foreground service, notifications, network/connectivity triggers, wake locks, MediaStore export, automatic rules, downloads UI, offline playback, and any schema migration. Task 4 supplies the service/worker loop and gates; task 5 owns `offline_media` publication and the orphan sweep.

## Evidence

Original task evidence: `:core-data` **558/0/0/0**, download package 153; `:provider-navidrome` 43, `:provider-api` 67, `:app:assembleDebug` green. The 2026-11-20 audit corrected production MEDIA identity preservation, transport/auth/storage settlement, fragmented payload validation, 206 continuity/completeness, lease cleanup, and test fixtures that previously allowed delayed or restart-from-zero false successes. Final audit evidence: `:core-data` **582/0/0/0**, download package **177/0/0/0** (`DownloadTransferEngineTest` **42**, `DownloadMediaSourceTest` **5**, `DownloadWorkerCoordinatorTest` **36**); `:core-network` **25/0/0/0**, `:core-model` **26/0/0/0**, `:provider-navidrome` **43/0/0/4** (live-server skips), `:provider-api` **67/0/0/0**, `:app:assembleDebug` green. No exported Room baseline changed; `Migrations.ALL` stays empty. Live-server range/throughput and power-loss filesystem behavior remain `[UNKNOWN]`. [VERIFIED JVM 2026-11-20]
