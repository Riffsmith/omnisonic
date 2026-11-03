# Provider connections — `providers/provider-connections.md`

Purpose: Pin the model that surrounds a persisted provider row: the `kind`
vocabulary, the sealed endpoint type, runtime capability traits, the shared
availability gate, the opt-in mTLS contract, the download enqueue path, and how
image loading rides the provider's clients.

How derived: code reading of `provider-api` (`ProviderConnection.kt`,
`ProviderTraits.kt`, `ProviderStatus.kt`, `MediaProvider.kt`) and `core-data`
(`ProviderConfigMapper.kt`, `ProviderClientRegistry.kt`,
`ProviderTraitsSource.kt`, `network/ProviderStatusTracker.kt`,
`network/NetworkMonitor.kt`, `downloads/DownloadEnqueuer.kt`), the app-side
image wiring (`app/.../image/`), and their unit/integration tests
(`ProviderConfigMapperTest`, `LocalProviderPersistenceTest`,
`ProviderStatusTrackerTest`, `MtlsEndToEndHandshakeTest`,
`DownloadEnqueuerTest`, `ProviderClientRegistryTest`). Decisions dated
2026-11-01/02; see `../decisions.md`.

Confidence:
- [VERIFIED] Every behavior below is asserted by a named test in this repository unless marked otherwise.
- [UNKNOWN] Live-server and real-device behavior of the network gate and mTLS (no device or server available at decision time).

Date: 2026-11-03

Links: `../decisions.md`, `client-lifecycle.md`, `credential-handling.md`,
`../storage/schema-v2.md` (schema columns, triggers, migration backfill),
`../network/client-profiles.md` (API/MEDIA profile split).

---

## Provider kinds

[VERIFIED] `providers.kind` is stored by name: `REMOTE_SERVER`, `DEVICE`,
`VIRTUAL` (column default `REMOTE_SERVER`, so pre-existing rows load unchanged).
Unknown or null names fall back to `REMOTE_SERVER`.

[VERIFIED] The mapper validates by kind: a `REMOTE_SERVER` row requires a valid
primary block (blank host, port outside 1–65535, or a non-http/https scheme make
the row unloadable, exactly as before kinds existed). `DEVICE` and `VIRTUAL`
rows map to `ProviderEndpoint.None` regardless of any leftover primary columns —
kind wins.

[VERIFIED] A persisted `local` row loads end to end: entity row → mapper →
registry → a real `LocalMediaProvider` (`LocalProviderPersistenceTest` builds the
row through `ProviderDao` and never constructs a fake connection block).

## Sealed endpoints

[VERIFIED] `ProviderConnection` carries `endpoint: ProviderEndpoint`, a sealed
type: `Remote(primary: ConnectionBlock, secondary: ConnectionBlock?)` or `None`.
Server-only code resolves it through two helpers:

- `remoteEndpoint()` — nullable view for probing.
- `requireRemote()` — returns the primary block or throws
  `IllegalStateException`, the single fail-fast seam for server-only clients.

Why sealed rather than a nullable `primary` (decision rationale):

1. One greppable fail-fast seam instead of scattered `requireNotNull` guards in
   every server-only consumer (`SubsonicClient.preferredBlock`,
   `BlockFailover`).
2. Inside `Remote`, blocks stay non-null, so the failover math and URL building
   kept their exact logic when the type landed.
3. Serverless rows are representable without a placeholder
   `ConnectionBlock("local", "localhost", 0)` that other consumers could mistake
   for a real server.

[VERIFIED] `BlockFailover` treats a `None` endpoint as a fatal error before any
block is contacted. A `None` endpoint never reaches `SubsonicClient` under
mapper/registry rules; if it did, `requireRemote()` throws.

## Capability traits (runtime only, never persisted)

[VERIFIED] `ProviderTraits` declares what a provider factory supports. Defaults
match the Subsonic family; narrower providers override
`MediaProviderFactory.traits()`, and `ProviderRegistry.traitsFor(api)` resolves
them (unknown api → Subsonic defaults).

| Trait | Subsonic family | Local | Consulted by |
|---|---|---|---|
| `hasLibrarySync` | true | true | `SubsonicLibrarySyncer.runSync` (skips with a typed outcome, writes no sync-state row) |
| `supportsDownload` | true | false | `DownloadEnqueuer` (`SkippedDownloadUnsupported`, nothing written) |
| `supportsServerTranscode` | true | false | Reserved for the transcode planner (phase 8 seam) |
| `reportsUserData` | true | false | Outbox enqueue writes NOTHING and returns `SkippedNoUserDataReporting` |

[VERIFIED] Every gate degrades politely: a typed result, no exception, no partial
writes. The local provider's user-data methods remain local no-ops that report
success so an outbox row can never block them.

## Availability gating (one shared run gate)

[VERIFIED] `ProviderStatus` is `OK | NEEDS_WIFI | AUTH_FAILED | OFFLINE`.
`ProviderStatusTracker` exposes `StateFlow<Map<Long, ProviderStatus>>` and a
shared `runGate` (a `ProviderRunGate` fun interface; permissive default for call
sites without a tracker).

[VERIFIED] Status precedence: `AUTH_FAILED` (credentials marker, see
`credential-handling.md`) > `OFFLINE` (not connected) > `NEEDS_WIFI` (row is
`wifiOnly` and the current network is not Wi-Fi) > `OK`. Unknown provider ids
resolve to `OK` — the gate owns network facts, not row lookup.

[VERIFIED] `NetworkMonitor` is the only consumer of `ConnectivityManager`
(`NET_CAPABILITY_INTERNET`, `TRANSPORT_WIFI`); it is an injected seam with a
Robolectric-shadowable implementation.

[VERIFIED] Consumers: sync skips before any read or write (no sync-state row);
`OutboxDrainer.drain(providerId)` returns `DrainReport(blockedBy = status)` and
leaves tasks due with no backoff; `DownloadEnqueuer` returns
`BlockedByStatus(status)` and writes nothing. Blocked work therefore stays
pending and runs on the next successful gate check.

[VERIFIED] A VPN-only network counts as NOT Wi-Fi: the platform exposes no
public API for the underlying transport, so a Wi-Fi-only provider stays
`NEEDS_WIFI` behind a VPN. Documented conservatively — metered data is never
assumed to be Wi-Fi.

## mTLS: opt-in and fail closed

[VERIFIED] Columns live on `providers` (`mtlsEnabled`, `mtlsKeystoreBlob`,
`mtlsAlias`, `trustedCertsBlob`; schema details in `../storage/schema-v2.md`).
All blobs are sealed with the same AES-GCM/Keystore framing as passwords
(`CredentialCodec.sealBytes/unsealBytes`). The keystore password rides the
sealed `secretBlob` under the `mtlsKeystorePassword` key, so secrets stay in one
envelope; `ClientCertSpec` has no generated `toString`, so its bytes cannot leak
through logs.

[VERIFIED] Mapper rules (all tested):

- `mtlsEnabled = false` → stored blobs and alias are ignored entirely;
  `clientCertificate` is null. Data alone can never enable mTLS.
- `mtlsEnabled = true` → the keystore blob must unseal to non-empty bytes,
  otherwise the row is `Unloadable`. There is no insecure fallback.
- `trustedCertsBlob` decodes INDEPENDENTLY of `mtlsEnabled` — a custom trust
  anchor is server-verification configuration, not client identity. A
  present-but-unparseable blob makes the row unloadable rather than silently
  downgrading server verification; absent blob → ordinary platform verification.
- A blank alias → null alias; missing password entry → null password.

[VERIFIED] The resolved identity is applied to BOTH client profiles:
`ProviderClientRegistry` builds a MEDIA client (`httpClientFor`) and an API
client (`apiClientFor`) per provider from the same connection, and a corrupt
(unparseable) PKCS#12 makes the registry drop the provider with a warning that
names no secrets. `SubsonicProviderFactory.defaultClient` mirrors the same
mTLS/trust mapping for the production API path — a deliberate ~8-line
duplication (provider-api cannot depend on core-network); flagged as a
unification candidate, not a hidden divergence.

[VERIFIED] `MtlsEndToEndHandshakeTest` completes real handshakes for both
profiles through persistence → mapper → registry against a client-certificate-
demanding MockWebServer, and proves the disabled path fails the handshake
(custom trust anchor retained, client identity absent).

## Download enqueue

[VERIFIED] `DownloadEnqueuer.enqueue(providerId, externalId, ...)` runs three
steps in order:

1. Traits first: `supportsDownload = false` → `SkippedDownloadUnsupported`
   (deterministic permanent rejection, nothing written).
2. Run gate second: non-OK status → `BlockedByStatus(status)` (transient;
   nothing written).
3. Insert-or-ignore of a `PENDING` row keyed on
   `DownloadKeys.mediaKey(providerId, externalId, bitrate, format)`.

[VERIFIED] The media key format is
`stream:<providerId>:<externalId>:<maxBitRateKbps|auto>:<format|auto>` — the
same vocabulary as the playback cache key (`../playback/stream-cache-identity.md`),
so downloads dedupe on the stream identity. Re-enqueueing an existing key is
idempotent and never resets progress.

[VERIFIED] `DownloadStatus` (`PENDING/RUNNING/COMPLETED/FAILED`) is persisted by
name. Deleting a provider removes its download rows through the cascade trigger
(`../storage/schema-v2.md`).

## Image loading rides the provider's clients

[VERIFIED] The app-wide Coil `ImageLoader` (Hilt singleton set as Coil's
default) routes every request through `ProviderRoutedCallFactory` →
`ProviderClientRegistry.apiClientForUrl(url)`, which matches scheme/host/port on
BOTH connection blocks — so cover images carry the provider's custom headers,
mTLS identity, and trust anchors. A URL that matches no provider block falls
back to the plain default client.
