# TODO

Phased plan toward the product in `SPEC.md`. Each phase should land as its own PR
(or small stack), fully unit-tested, with `./gradlew test` and `./gradlew lint`
green.

## Phase 0 — Docs skeleton (this PR)

- [x] `SPEC.md`, `TODO.md`, `AGENTS.md` (+ `CLAUDE.md` / `GEMINI.md` symlinks),
      `README.md`.
- [x] `.claude` SessionStart hook + settings and `.gitignore`, mirrored from
      simmo, so remote web sessions can provision the Android SDK.

## Phase 0b — Project scaffold

- [ ] Gradle + AGP + Compose skeleton (`:app`, application ID per the open
      question in `SPEC.md`), buildable in CI, `versionCode` from
      `git rev-list --count HEAD`.
- [ ] CI workflow (`android-ci.yml`): build, unit tests (failure comments on
      PRs), lint, screenshot job — mirrored from simmo, release steps
      secret-gated.
- [ ] Build identity: `.debug` (CI tester) / `.dev` (local) application ID
      suffixes and badged launcher icons, per the sibling convention.

## Phase 1 — Twilio side and backend

- [ ] Create the Twilio assets and document the setup in `docs/twilio-setup.md`:
      a number, an API key, a TwiML App, a Push Credential (FCM service-account
      key from the new Firebase project). No secrets in the repo — the doc
      describes, the console holds.
- [ ] Access-token endpoint (Twilio Functions): mints Voice access tokens for
      the app's identity, authenticated so only the app can mint (shared-secret
      header to start; recorded as revisitable).
- [ ] Inbound routing webhook (TwiML App): `<Dial><Client>` to the identity,
      with the no-answer behavior from the `SPEC.md` open question once decided.

## Phase 2 — Outbound calling

- [ ] Encrypted, backup-excluded config store (token-endpoint URL + secret,
      identity) and the setup screen.
- [ ] Token client: cache an unexpired token, refresh opportunistically while
      in use (app open, pre-expiry — no scheduled background refresh), mint
      on demand as the fallback; explicit failure surfacing (no network, bad
      secret, endpoint down with no cached token → reason shown at dial time).
      Cache/refresh logic unit-tested.
- [ ] Call state machine (pure Kotlin, table-tested) driving the Twilio Voice
      SDK: connect, ringing, connected, disconnected, every failure edge.
- [ ] Telecom integration via `androidx.core-telecom` (`CallsManager`):
      self-managed calling account, in-call foreground service scoped to the
      call, audio routing owned by the platform.
- [ ] Dialer + in-call screen (mute, speaker/Bluetooth via Telecom, DTMF
      keypad, hang up), with screenshot tests.

## Phase 3 — Inbound calling

- [ ] `FirebaseMessagingService`: high-priority check, payload parse, dedupe on
      call identifier — all pure-logic-tested.
- [ ] `Voice.register` on app start / `onNewToken` / credentials change — and
      deliberately *not* on network change. Unit tests for every trigger,
      including rotation while backgrounded.
- [ ] Monthly registration-renewal job (`WorkManager`, deferrable) so the
      binding never crosses Twilio's ~1-year TTL unopened (SPEC → *Battery
      model*: scheduled work is fine, the always-running posture is what's
      banned); scheduling logic unit-tested.
- [ ] Push → Telecom `addIncomingCall` → CallStyle notification within 5 s;
      full-screen ring gated on `canUseFullScreenIntent()`; audio capture only
      after Telecom brings the call up.
- [ ] Ghost-ring teardown with honest disconnect causes (caller hung up during
      wake, setup failure); missed-call notice.
- [ ] Inbound races unit-tested: push with no credentials, push during a
      cellular call, push after caller hangup, duplicate push.

## Phase 4 — Reliability surface

- [ ] Registration-health readout on the home screen: last successful
      registration, last push received, current permission/config gaps.
- [ ] Hibernation guard (SPEC → *Inbound*): check
      `PackageManagerCompat.getUnusedAppRestrictionsStatus()`, guide the user
      through `ACTION_MANAGE_UNUSED_APP_RESTRICTIONS` in onboarding, and warn
      in the health readout when "Pause app activity if unused" is on.
- [ ] On-device debug log per the `AGENTS.md` privacy policy (coarse call-flow
      state, one reason per outcome).
- [ ] Measure the load-bearing latencies on a real Pixel and Samsung: routing
      webhook response (cold and warm Function), FCM delivery to a Dozing
      device, wake-to-ring time. Record findings in `SPEC.md`.

## Phase 5 — Polish and release readiness

- [ ] Onboarding flow (contextual permission requests, setup validation call).
- [ ] `docs/PRIVACY.md` (push-per-call disclosure to Google, Twilio's carrier
      position, debug-log contents) before any release.
- [ ] Theme/settings, translations (per the `AGENTS.md` two-PR flow), Play
      internal-track wiring.

## Decisions needing review

Guesses made while drafting the skeleton, each cheap to change:

- **Twilio Functions hosts the backend** (same vendor, least moving parts);
  alternative was a Worker/Cloud Run function. Reversible until Phase 1 lands.
- **Codex is named as the automated reviewer** in `AGENTS.md`, following simmo
  (phomo uses Copilot). One-line change if wrong.
- **Application ID `app.twilmo`** with simmo's `.debug`/`.dev` suffix scheme —
  flagged as an open question in `SPEC.md`; nothing depends on it until
  Phase 0b.
- **In-app dialing only for outbound v1** (no `CallRedirectionService`) — the
  top open question in `SPEC.md`; scope grows if the answer is "router too."
