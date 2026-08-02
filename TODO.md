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

- [ ] Gradle + AGP + Compose skeleton (`:app`, application ID `app.twilmo`),
      buildable in CI, `versionCode` from `git rev-list --count HEAD`.
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

- [ ] Config store split by sensitivity (SPEC → *Persistence*): endpoint URL +
      identity ride backup/transfer; only the client secret is encrypted and
      backup-excluded. Setup screen, plus the restore path: detect the missing
      secret after a restore and ask for exactly that one field with the
      reason stated.
- [ ] Token client: keep a valid token ready ahead of dialing (cache +
      opportunistic refresh; exact policy chosen at implementation time within
      the battery model), mint on demand as the fallback; explicit failure
      surfacing (no network, bad secret, endpoint down with no cached token →
      reason shown at dial time). Cache/refresh logic unit-tested.
- [ ] Call state machine (pure Kotlin, table-tested) driving the Twilio Voice
      SDK: connect, ringing, connected, disconnected, every failure edge.
- [ ] Telecom integration, line model first (SPEC → *Telecom integration*):
      register a `CAPABILITY_CALL_PROVIDER` phone account + `ConnectionService`
      so Twilmo is a calling line the stock dialer (and Simmo's phone-account
      redirect) can place calls on; in-call foreground service scoped to the
      call; audio routing owned by the platform. Device-verify the four line
      unknowns (SPEC → *Open questions*) before hardening; fall back to
      self-managed via `androidx.core-telecom` if the model fails.
- [ ] Simmo-side prerequisite for the line model (SPEC → *Hand-off intent*,
      "No redirect loop"): extend Simmo's already-on-target pass-through to
      the account dimension — a call whose initial `PhoneAccountHandle`
      already belongs to a calling app (Twilmo included) proceeds unmodified.
      Land in simmo before (or with) enabling Twilmo's call-provider account;
      scope confirmed by the device verification of whether managed calls are
      offered to redirection at all.
- [ ] Hand-off intent contract (SPEC → *Hand-off intent*): `ACTION_DIAL` /
      `ACTION_VIEW tel:` pre-fill, `ACTION_CALL tel:` dials immediately with
      the activity exported behind
      `android:permission="android.permission.CALL_PHONE"`; number-keyed only;
      unconfigured launch degrades visibly with the number preserved;
      emergency numbers forwarded to the platform dialer. Unit-test the number
      parsing, the permission boundary, and the refusal paths.
- [ ] Dialer + in-call screen (mute, speaker/Bluetooth via Telecom, DTMF
      keypad, hang up), with screenshot tests.

## Phase 3 — Inbound calling

- [ ] `FirebaseMessagingService`: high-priority check, payload parse, dedupe on
      call identifier + event type (a cancel shares its invite's identifier
      and must never be swallowed) — all pure-logic-tested.
- [ ] `Voice.register` on app start / `onNewToken` / credentials change — and
      deliberately *not* on network change. Unit tests for every trigger,
      including rotation while backgrounded.
- [ ] A credentials change that replaces the identity or the registration
      authority (account/endpoint, even with the same identity string)
      unregisters the old binding first (SPEC → *Inbound*): complete
      `Voice.unregister` — which needs an access token for the old identity
      minted by the old authority — before discarding the old auth config;
      surface a leftover binding when the old backend is gone; switch path
      unit-tested.
- [ ] Scheduled registration renewal so the binding never crosses Twilio's
      ~1-year TTL unopened (SPEC → *Battery model*: scheduled work is fine,
      the always-running posture is what's banned; cadence and scheduler
      chosen at implementation time); scheduling logic unit-tested.
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

## v2 explorations (deferred, decided direction only)

- [ ] **All outbound calls on Twilmo by default** (maintainer, 2026-08-02) —
      likely just the stock dialer's default-calling-account setting under the
      line model; explore desirability: cost vs. SIM plan, no-network behavior,
      emergency/short-code calls always staying on the SIM.
- [ ] Simmo-side follow-up once the hand-off contract ships: add Twilmo's row
      to simmo's `docs/handoff-intents.md` as a confirmed auto-dial (and
      phone-account-redirect) target. (The account pass-through itself is v1
      work — see Phase 2.)

## Decisions needing review

Guesses made while drafting the skeleton, each cheap to change:

- **Twilio Functions hosts the backend** (same vendor, least moving parts);
  alternative was a Worker/Cloud Run function. Reversible until Phase 1 lands.
- **Codex is named as the automated reviewer** in `AGENTS.md`, following simmo
  (phomo uses Copilot). One-line change if wrong.

Resolved (maintainer, 2026-08-02): Twilmo is a second line only — Simmo owns
redirection, and Twilmo integrates as a calling line: a call-provider phone
account plus a number-keyed hand-off intent that dials immediately
(Google-Voice-style deep link, minus the pre-fill tap), never requiring
contacts registration entries. Also resolved (maintainer, 2026-08-02): the
application ID is `app.twilmo`, with simmo's `.debug`/`.dev` suffix scheme.
