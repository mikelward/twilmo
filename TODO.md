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

- [x] Gradle + AGP + Compose skeleton (`:app`, application ID `app.twilmo`),
      buildable in CI, `versionCode` from `git rev-list --count HEAD`.
- [x] CI workflow (`android-ci.yml`): build, unit tests (failure comments on
      PRs), lint, screenshot job — mirrored from simmo. The deploy job
      (Firebase App Distribution + Play internal track, secret-gated) lands
      with Phase 5's Play wiring, once the accounts exist to gate on.
- [x] Build identity: debug-build application ID suffixes (`.debug` CI
      tester / `.dev` local) and launcher variants, per the sibling
      convention; release keeps the production ID everywhere (see
      *Decisions needing review* on the icon treatment).
- [x] First domain unit with tests: `tel:` URI number extraction for the
      hand-off contract (scheme case, `%2B`, separators, RFC 3966 params,
      refusal cases).

## Phase 1 — Twilio side and backend

- [x] Document the setup in `docs/twilio-setup.md` (number, API key, TwiML
      App, Push Credential from the Firebase project's FCM service-account
      key, Functions service, env vars, wiring the number) with the token
      endpoint's request/response contract pinned. No secrets in the repo —
      the doc describes, the console holds.
- [ ] Create the Twilio assets by running the checklist (owner-only: needs
      the Twilio and Firebase accounts).
- [x] Access-token endpoint code (`functions/token.js`): mints Voice access
      tokens for the app's identity with the incoming grant Phase 3's
      registration needs; public visibility (the app calls it, not Twilio)
      with shared-secret header auth, constant-time compared; recorded as
      revisitable in *Decisions needing review*. Handler tests
      (`functions/*.test.js`, Node's built-in runner) run in CI alongside
      the Gradle suite.
- [x] Inbound routing webhook code (`functions/inbound.protected.js`):
      `<Dial><Client>` to the identity with `answerOnBridge`; protected
      visibility so the runtime verifies Twilio's signature. No-answer
      behavior stays the simple default until the `SPEC.md` open question is
      decided (see *Decisions needing review*). The number's fallback URL is
      a TwiML Bin (doc step 7) so a Functions outage degrades to an honest
      announcement, per SPEC → *Backend*.
- [x] Outbound routing webhook code (`functions/outbound.protected.js`):
      the TwiML App's voice URL — `<Dial><Number>` on the `To` connect
      parameter with the account's `CALLER_ID`; a missing `To` ends the leg
      so the SDK surfaces the connect failure. Handler-tested; the
      connect-parameter contract is pinned in the doc and consumed by the
      Phase 2 SDK driver.

## Phase 2 — Outbound calling

- [x] Config store split by sensitivity (SPEC → *Persistence*): endpoint
      URL + identity in DataStore riding backup/transfer; the client secret
      Keystore-encrypted in a SharedPreferences file the extraction rules
      exclude from backup and device transfer. Setup screen (full form with
      per-field validation, https-only endpoint) plus the restore path:
      config-without-secret is detected as the restore signature and setup
      asks for exactly the one missing field with the reason stated; an
      undecryptable secret degrades to the same ask. Authority fingerprint
      (SHA-256 over endpoint + identity, length-prefixed) feeds the token
      cache's authority binding. Domain logic, view model, and both screen
      states unit/screenshot-tested; the Keystore crypto itself is owed a
      device check.
- [x] Token readiness policy (pure Kotlin, unit-tested): at dial time a
      fresh cache connects immediately, an aging cache connects and starts a
      background refresh, and no comfortably valid cache means mint-now with
      the dial waiting — keeping a thin-but-unexpired token as the last
      resort if the mint fails (a call on a thin token beats no call);
      cached tokens are bound to the credential set that minted them, so a
      credentials change orphans the old cache everywhere; opportunistic
      warm-up refresh only when the cache is thin (battery model: refreshes
      ride moments the app is already awake); the credential is never
      printable via toString.
- [x] Token minting core (pure, fake-transport-tested): maps the pinned
      endpoint contract onto results — minted tokens stamped with the
      authority key and clock-derived expiry; 401 → bad secret, other
      statuses → endpoint error with the code, unreachable → network
      failure, off-contract 200 → malformed — each with a one-line
      sanitized debug reason that never carries a body, URL, or secret.
      The dial-time flow composes the readiness policy with one mint:
      fresh-mint replace-cache, background-refresh signaling, the
      last-resort fallback re-checked after the mint with its failure kept
      visible, and no-token failing the dial with the reason.
- [ ] Wire the token client into the app: the real HTTPS transport
      (sanitized failure details), cache storage, and the call state
      machine's token request driving DialTokenFlow — reasons surfaced at
      dial time per SPEC → *Outbound*.
- [x] Call state machine (pure Kotlin, table-tested): dial → token →
      connect → ringing → connected → disconnected, with every failure edge
      explicit, exactly one reported outcome per call, unexpected events
      logged rather than swallowed, and no phone number in the machine so
      its state is freely loggable.
- [ ] Drive the Twilio Voice SDK from the call state machine (arrives with
      the SDK dependency): map SDK callbacks to events and effects to SDK
      calls, with the driver supplying the number and token the machine
      deliberately never holds.
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
- [ ] On-device debug log foundation per the `AGENTS.md` privacy policy
      (coarse call-flow state, one reason per outcome), landing with the
      first call paths so no milestone ships failure paths without their
      diagnostic reasons; inbound instruments it as Phase 3 lands.

## Phase 3 — Inbound calling

- [ ] `FirebaseMessagingService`: high-priority check, payload parse, dedupe on
      call identifier + event type (a cancel shares its invite's identifier
      and must never be swallowed) — all pure-logic-tested.
- [ ] `Voice.register` on app start / `onNewToken` / credentials change — and
      deliberately *not* on network change — with **every trigger gated on the
      visible-outlet invariant** (SPEC → *Inbound*, push discipline): a
      trigger registers only while the calling account is registered and
      enabled, and the account becoming disabled or removed unregisters the
      binding. Unit tests for every trigger and the gate, including rotation
      while backgrounded and a trigger firing while the account is disabled.
- [ ] A credentials change that replaces the identity or the registration
      authority (account/endpoint, even with the same identity string)
      unregisters the old binding first (SPEC → *Inbound*): complete
      `Voice.unregister` — which needs an access token for the old identity
      minted by the old authority — before discarding the old auth config.
      Same for the account being disabled or removed: an unregister that
      can't complete (offline, authority unreachable) is retried as deferred
      background work until confirmed, the binding is treated as possibly
      live until then, and a leftover binding is surfaced when the old
      backend is gone for good. When confirmation is permanently impossible
      and no visible outlet remains, delete the app's FCM registration token
      (SPEC → *Inbound*) — the authority-independent kill switch that
      invalidates every binding to this install at the FCM layer; a later
      setup mints a fresh token. Switch, retry, and kill-switch paths
      unit-tested.
- [ ] Scheduled registration renewal so the binding never crosses Twilio's
      ~1-year TTL unopened (SPEC → *Battery model*: scheduled work is fine,
      the always-running posture is what's banned; cadence and scheduler
      chosen at implementation time); scheduling logic unit-tested.
- [ ] Push → Telecom `addIncomingCall` → CallStyle notification within 5 s;
      full-screen ring gated on `canUseFullScreenIntent()`; audio capture only
      after Telecom brings the call up.
- [ ] Ghost-ring teardown with honest disconnect causes (caller hung up during
      wake, setup failure); missed-call notice — with the
      notifications-denied path surfaced by the **platform dialer's own
      missed-call notification** via Telecom's disconnect handling (the call
      log is the record, not the surface), device-verified on Pixel and
      Samsung (SPEC → *Inbound*, push discipline).
- [ ] Inbound races unit-tested: push with no credentials, push during a
      cellular call, push after caller hangup, duplicate push.

## Phase 4 — Reliability surface

- [ ] Registration-health readout on the home screen: last successful
      registration, last push received, current permission/config gaps —
      including a denied `POST_NOTIFICATIONS` flagged as a gap.
- [ ] Hibernation guard (SPEC → *Inbound*): check
      `PackageManagerCompat.getUnusedAppRestrictionsStatus()`, guide the user
      through `ACTION_MANAGE_UNUSED_APP_RESTRICTIONS` in onboarding, and warn
      in the health readout when "Pause app activity if unused" is on.
- [ ] Measure the load-bearing latencies on a real Pixel and Samsung: routing
      webhook response (cold and warm Function), FCM delivery to a Dozing
      device, wake-to-ring time. Record findings in `SPEC.md`.

## Phase 5 — Polish and release readiness

- [ ] Onboarding flow (contextual permission requests, setup validation call).
- [ ] `docs/PRIVACY.md` (push-per-call disclosure to Google, Twilio's carrier
      position, debug-log contents) before any release.
- [ ] Theme/settings, Play internal-track wiring.

## Post-MVP

- [ ] Translations (maintainer, 2026-08-02: deferred until after MVP). Every
      user-facing string still lands in `values/strings.xml` with the
      per-string `MissingTranslation` ignore as it's written, so this is pure
      fan-out when it starts: propose/approve English copy in chat, then one
      PR per the `AGENTS.md` two-PR flow adds every locale and removes the
      ignores.

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
- **Launcher identity is color-coded, not letter-badged** (scaffold): one
  handset glyph on teal (Play) / amber (dev) / dark slate (CI tester)
  backgrounds, instead of simmo's lettered DEBUG/DEV badge bars. Simpler to
  land, same tell-them-apart job; swapping in badge-bar vectors later touches
  only the drawable layer. The glyph itself is a stock Material handset — a
  real brand mark can replace it any time.
- **Scaffold dependency set is minimal**: no DataStore, serialization,
  libphonenumber, or Firebase yet — each arrives with the phase that first
  uses it, so the tree never carries an unexercised dependency.
- **CI deploy job deferred to Phase 5** (see Phase 0b): mirroring simmo's
  Firebase/Play pipeline before those accounts exist would ship 500 untested
  workflow lines; the build/test/lint/screenshot jobs land now.
- **Token endpoint contract** (`docs/twilio-setup.md`): shared-secret header
  `x-twilmo-secret`, JSON `{token, expiresInSeconds, identity}`, TTL 3600 s.
  Alternatives were per-install keys or Twilio-signed requests (impossible —
  the app isn't Twilio). Revisitable until the app's token client ships
  against it; a longer TTL (up to 24 h) is a one-line change weighed against
  how long a stolen token stays usable.
- **Inbound no-answer behavior defaults to a 30 s dial timeout** and
  Twilio's default hangup (`functions/inbound.protected.js`) while the
  `SPEC.md` open question (voicemail / message / forward) is undecided.
  Chosen as the simplest honest behavior; changing it later touches only
  the Function.
- **The secret is Keystore-AES-GCM in an excluded SharedPreferences file**,
  not androidx security-crypto (deprecated) or a passphrase KDF. Two layers,
  one reason each: exclusion keeps it out of backups, Keystore keeps the
  on-device file ciphertext. Reversible — the `SecretStore` interface hides
  the mechanism.
- **The authority fingerprint hashes endpoint + identity, not the secret**:
  rotating the secret doesn't change who mints, so a still-valid cached
  token survives a secret fix. Including the secret would only cost an
  extra mint; one-line change if preferred.
- **Outbound connect-parameter contract** (`docs/twilio-setup.md` step 6):
  the app passes the dialed number as the `To` parameter, E.164, and the
  Function presents the account's `CALLER_ID`. The obvious shape, but
  recorded because the Phase 2 SDK driver must match it; revisitable until
  that driver ships.

Resolved (maintainer, 2026-08-02): privacy rules are floors that preserve the
user's privacy, weighed against functionality, data loss, performance, cost,
and simplicity — never the narrowest reading prematurely encoded as final
(`AGENTS.md` → *Privacy*, `SPEC.md` → *Privacy*).

Resolved (maintainer, 2026-08-02): Twilmo is a second line only — Simmo owns
redirection, and Twilmo integrates as a calling line: a call-provider phone
account plus a number-keyed hand-off intent that dials immediately
(Google-Voice-style deep link, minus the pre-fill tap), never requiring
contacts registration entries. Also resolved (maintainer, 2026-08-02): the
application ID is `app.twilmo`, with simmo's `.debug`/`.dev` suffix scheme.
