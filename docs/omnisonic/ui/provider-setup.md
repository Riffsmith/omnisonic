# Provider setup screen — `ui/provider-setup.md`

Purpose: pin down the first-run provider setup destination — what it accepts, when
it tests the connection, what it persists, and how it hands control back to the
shell (decision D-12).

How derived: own implementation for Phase 5 task 2b from the route table and
first-run flow described in `reference/docs/UI_SPEC.md` §3.2 and §5, the
cleartext policy in `network/cleartext-policy.md`, and the credential rules in
`providers/credential-handling.md`. No studied-app code is involved.

Confidence: form rules, probe-before-save, credential sealing, onboarding
completion and navigation are `[VERIFIED]` against `ServerUrlTest` (8),
`ProviderSetupServiceTest` (12), `ProviderSetupViewModelTest` (11) and
`ProviderSetupNavigationTest` (1). The launcher's no-op/skip behavior is
`[VERIFIED]` by `LibrarySyncLauncherTest` (2); its process-scoped `CoroutineScope`
is `[INFERRED]` from construction. Rendered behavior on a physical device
(keyboard, IME, small screens) is `[UNKNOWN]` until device testing.

Date: 2026-11-08

Links: `decisions.md` (D-12) · `network/cleartext-policy.md` ·
`providers/provider-connections.md` · `providers/credential-handling.md` ·
`ui/style-tokens.md` · `.agents/plans/phase-5-tasks.md` task 2b

---

## Entry and exit

- The shell's onboarding gate (`OnboardingGate.startRoute`) starts the app on
  `providerSetup` while `ShellPreferencesStore.DEFAULT_ONBOARDING_STEPS`
  contains that step. Setup is the only first-run destination; there is no
  skip. A provider can be added later from settings with the same form.
- A saved provider marks the onboarding step complete through
  `ShellPreferencesStore.completeOnboardingStep`, which removes the step and
  sets `onboardingComplete` when the list is exhausted. Re-running setup from
  settings is idempotent for that flag.
- On completion the shell branches on first run: as the start destination the
  form hands over to the `librarySync` step (`providerSetup` → `librarySync` →
  `home`); reached from settings, it goes straight to `home`. Either way the
  navigation uses `popUpTo(providerSetup) { inclusive = true }`, so back cannot
  return to the finished form. `[VERIFIED]` `ProviderSetupNavigationTest`.
- `ShellUiState.hasProvider` is a live Room count
  (`ProviderDao.countFlow()`), so the Home empty state ("Set up a provider"
  button) clears as soon as the provider row is written — no restart needed.
  `[VERIFIED]` `ProviderCountFlowTest`, `ShellViewModelTest`.
- The screen shows a back affordance only when the route was pushed on top of
  another destination (reached from settings). During first run the setup route
  is the NavHost start destination, so there is nothing to pop and no back
  control is rendered. `[VERIFIED]` `ProviderSetupScreen`/`ShellNavHost`.

## Form contract

`ProviderSetupForm` carries the raw text; `ProviderSetupRules.validate` is the
single validator used by both the screen and the persistence path.

| Field | Rule |
|---|---|
| Server URL | Required. Must start with `http://` or `https://`; a missing scheme is rejected rather than guessed, because self-hosted servers commonly run plain HTTP. Embedded credentials, queries and fragments are rejected so no secret can ride the URL into a log or provider row. A path prefix is kept in the connection block. |
| Backup server URL | Optional. Same URL rules when present; blank means "no failover block". |
| Username | Required; trimmed before the row is written. |
| Password | Required; not trimmed. Sealed through `CredentialCodec` before storage. |

`parseServerUrl` also reports cleartext transport and whether the host is in a
private range. Cleartext to a non-private host is allowed but the form shows a
warning before saving; cleartext to a private host is the expected LAN case and
shows no warning. `[VERIFIED]` `ServerUrlTest`, `ProviderSetupUiState`.

## Test and save flow

1. **Test connection** validates the form, then probes through the same
   transient provider build used elsewhere (`ProviderClientRegistry.buildTransientProvider`,
   never cached). Success is cached against the exact form contents.
2. Any edit invalidates the cached success.
3. **Save and continue** reuses a cached success for the identical form;
   otherwise it probes first. Only `ProviderProbeResult.Success` reaches
   persistence — an unreachable or rejecting server never produces a provider
   row.
4. Probe failures map to distinct messages: network/transport, rejected
   credentials, server HTTP error, unsupported provider type, invalid input,
   unexpected failure.

The form is disabled while a test or save is in flight; the save button label
switches between "Save and continue" and "Saving…". `[VERIFIED]`
`ProviderSetupViewModelTest`.

## What saving writes

`ProviderSetupService.save` writes exactly one provider row:

- `api = "subsonic"`, `kind = REMOTE_SERVER`;
- `displayOrder` = current provider count;
- primary block (scheme/host/port/path) and optional secondary block, with
  `useSecondary` set from the presence of the secondary URL;
- trimmed username and the password sealed via the existing credential codec;
- `createdAt`/`updatedAt` from one timestamp.

The client registry entry for the new id is invalidated so the first library
call rebuilds against the saved row. The password never appears in the row,
logs, or the URL. `[VERIFIED]` `ProviderSetupServiceTest`.

## Initial sync hand-off

After a successful save the view model marks onboarding complete and calls
`startInitialSync(providerId)`. That runs the first library walk through
`LibrarySyncLauncher` on a process-scoped `SupervisorJob()` / `Dispatchers.IO`
scope, so leaving the setup screen cannot cancel it. Failures are logged;
authentication outcomes feed the status tracker's run gate and auth-failed
marker. `[INFERRED]` from `ProviderWiringModule` / `LibrarySyncLauncher`;
unknown and unloadable provider ids are covered by `LibrarySyncLauncherTest`.

## Presentation notes

- The screen lives in `:feature-settings`
  (`app.omnisonic.feature.settings.providers`) because it reuses the settings
  shell and is reachable from settings later; its strings stay in that module
  (non-transitive resources).
- All spacing comes from `OmnisonicTheme.tokens`; the layout is a single
  scrollable column so the form stays usable on short screens.
- The password field uses `PasswordVisualTransformation`; the URL fields use
  the URI keyboard type.

## Known gaps

- Only the Subsonic-compatible provider kind is offered; provider-type
  selection, local MediaStore setup and multi-provider management are later
  work.
- No device-level verification of IME behavior, autofill, or hardware-keyboard
  navigation `[UNKNOWN]`.
