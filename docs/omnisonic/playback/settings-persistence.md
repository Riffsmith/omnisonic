# Playback settings persistence — `playback/settings-persistence.md`

Purpose: pins down how engine preference enums (offload mode, replay-gain mode,
replay-gain normalisation, transient-loss action) are stored by
`PlaybackSettingsStore` and how legacy data is treated.

How derived: audit defect T18 in the pre-release review prompt (ordinal storage
remaps saved settings when enum constants are reordered or inserted), plus the
DataStore file format observation (typed preference keys; an int value stored
under a key later read as a string fails the whole file read).

Confidence: name-based storage and the legacy mapping are [VERIFIED] by
`PlaybackSettingsStoreTest`. The "one release" legacy window is a policy choice
[INFERRED]; nothing is shipped, so no released device is expected to carry
ordinal data.

Date: 2026-11-02

Links: `playback-api` `PlaybackSettings`/`OffloadMode`/`ReplayGainMode`/
`RgNormalisation`/`TransientLossAction`; review prompt T18.

---

## Storage contract

- Enum-valued settings persist under `*_name` string keys
  (`engine.offload_mode_name`, `engine.replay_gain_mode_name`,
  `engine.replay_gain_normalisation_name`, `engine.transient_loss_action_name`)
  holding `enum.name`. [VERIFIED]
- Resolution is by exact name match. An unknown or missing name falls back to
  the per-setting default (offload `DISABLED`, replay-gain `OFF`,
  normalisation `RG2`, transient loss `DUCK`). [VERIFIED]
- Because the persisted token is the stable name, inserting or reordering enum
  constants cannot remap an existing stored value. [VERIFIED]

## Legacy ordinal fallback (one release)

- The old ordinal int keys (`engine.offload_mode`,
  `engine.replay_gain_mode`, `engine.replay_gain_normalisation`,
  `engine.transient_loss_action`) remain readable. When no name key is
  present, an in-range legacy ordinal maps back to its entry. [VERIFIED]
- An out-of-range legacy ordinal (enum shrank, corrupt value) falls back to
  the default. [VERIFIED]
- Every write stores the name key and REMOVES the legacy ordinal key, so the
  fallback read only ever applies to data written by a pre-name build.
  [VERIFIED]
- No data migration is required: the preference file keeps both key shapes and
  DataStore reads are per-key. [VERIFIED]
