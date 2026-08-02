# Twilmo Design Spec

Twilmo is an Android app that makes and receives phone calls on a Twilio number —
**reliably, with near-zero idle battery cost**. It is built on the **Twilio Voice
Android SDK**: one vendor calling stack for both directions, with Twilio owning
inbound push delivery and holding the call while the phone wakes up. It targets
Android 16+ (`minSdk 34`, `targetSdk 36`) on Pixel and Samsung, and is built to
feel like part of the platform — an incoming Twilmo call rings, answers, and routes
audio like a real phone call, because to the Telecom framework it is one.

> **Status.** This spec describes the intended v1 product and architecture. The
> repository currently contains documentation only; the project scaffolding and the
> calling stack land across the implementation milestones in `TODO.md`. Sections
> that describe not-yet-built behavior say so implicitly — almost all of them do.

> **Lineage.** Twilmo is the sibling of `mikelward/phomo` (a SIP client,
> outbound-only in v1 precisely because inbound over SIP forces an always-on
> registration) and builds the route phomo's `PUSH.md` analyzes as *Option 6 /
> family A*: drop SIP, adopt one vendor's SDK for both legs, and let the provider
> own the push. That document is the design study behind most of the decisions
> below; this spec restates its conclusions at product altitude rather than
> repeating the analysis.

## Product shape

- **A second phone line.** Twilmo gives the phone a Twilio number that can both
  place and receive calls, alongside the SIM. The SIM keeps being the primary
  line; Twilmo never interferes with SIM calls, and an incoming cellular call
  always wins any contention for the audio route.
- **Reliability and battery are the twin constraints, and they rank in that
  order.** The phone must ring when the Twilio number is called — promptly, and
  every time the platform allows — and the app must cost effectively nothing
  while idle. The architecture below exists because this combination is only
  achievable when the *provider* stays reachable on the device's behalf and wakes
  it by push.
- **Stay out of the way.** The app's own UI is a thin surface: setup, a dialer,
  call status, and a registration-health readout. Incoming calls surface through
  the platform's own incoming-call UI (a CallStyle notification / full-screen
  ring), not a custom in-app experience the user must learn.
- **Vendor lock-in is accepted deliberately.** With a SIP client, changing
  provider is a credentials edit; with a vendor SDK it is a rewrite of the
  calling layer. Twilmo accepts that: the portability a SIP stack buys is worth
  little to a single user with one provider, and what the SDK hands back —
  provider-owned push, a held inbound call (no wake race), proven WebRTC media,
  per-call quality telemetry — is exactly the hard part of this product
  (`PUSH.md` §6 Option 6, §8 A). The name says it plainly: Twilmo is a Twilio
  app.

## Devices and compatibility

- First-class targets: recent **Pixel** and **Samsung** devices on **Android
  16+**. These two OEMs differ in background-execution and battery-optimizer
  behavior — the exact things that decide whether a push wakes the phone — so
  both are part of "done" for any inbound-path change.
- `minSdk 34` covers Android 14–15 devices as a courtesy; the product is designed
  and tested against 16+.
- **Google Play services is required for inbound.** FCM is the wake mechanism, so
  a de-Googled device can place calls but will never receive one. This is stated
  in-app rather than silently failing.

## Calling stack — Twilio Voice Android SDK

- **One stack, both directions.** `com.twilio:voice-android` carries outbound and
  inbound calls over Twilio's WebRTC-based signaling and media. There is no SIP
  layer, no liblinphone, no registration lease, and no second media engine.
- **Why a vendor SDK rather than SIP + push:** every SIP route to reliable
  inbound needs someone to stay reachable and hold the `INVITE` while the phone
  wakes — a push-capable proxy we run, a rented gateway, or a provider registrar
  speaking RFC 8599, none of which Twilio offers on its trunks (`PUSH.md` §5,
  §8). The Voice SDK is Twilio's supported answer: Twilio holds the call, sends
  the high-priority FCM push itself, and delivers the call over its own
  signaling when the app comes up. There is no wake race for us to lose and
  nothing of ours listening between calls.
- **Media quality is a strength, not a compromise.** The WebRTC lineage brings
  Opus, echo cancellation / gain control / noise suppression, jitter buffering,
  packet-loss concealment, and ICE/STUN/TURN NAT traversal. The SDK exposes
  per-call telemetry (`Call.getStats()`: MOS, jitter, packet loss, RTT) and live
  quality-warning callbacks, which serve the call-quality bar in `AGENTS.md`
  directly.
- Prefer the SDK's and the platform's proven defaults over hand-rolled media or
  audio-routing behavior, always.

## Inbound — the push-woken ring

The inbound model is the reason this app exists, so its invariants are spelled
out.

- **The address is a binding, not a lease — but it is not permanent either.**
  The app registers `identity + FCM token` with Twilio (`Voice.register`). That
  binding is long-lived — no NAT path to hold open, no periodic re-`REGISTER`
  treadmill, no keep-alive — but **Twilio expires registration bindings after
  roughly a year**, so "registered once, reachable forever" is not true and must
  not be designed for. Twilio reaches the device out of band via FCM; nothing
  needs a network path held open between calls.
- **What must stay fresh is the push token — and, rarely, the binding itself.**
  Registration is re-run at three event triggers: **app start**, **`onNewToken`**
  (FCM rotated the token), and **credentials changed** — plus one **rare
  scheduled renewal** (on the order of monthly) whose only job is to keep the
  binding from aging out of Twilio's ~1-year TTL on a phone whose owner never
  opens the app. A stale token or an expired binding is a phone that silently
  stops ringing — the app's worst failure mode — so both are tested correctness
  surfaces, not code paths. **Network change is deliberately not a trigger**: it
  would need a standing `ConnectivityManager` callback (banned by the battery
  model), it fires constantly on the move, and Play services already re-homes
  the FCM socket for every app at once (`PUSH.md` §4).
- **Call flow:** a call to the Twilio number → Twilio's routing webhook returns
  `<Dial><Client>` for the app's identity → Twilio sends a **high-priority FCM
  data message** and holds the call → the app wakes, hands the call to Telecom,
  and the phone rings → Answer accepts the call through the SDK and media flows.
  Twilio owns the hold-and-retry between push and answer; the app's only job is
  to get from push to visible ring fast.
- **Ring first, resolve later.** The incoming-call surface is raised the moment
  the push lands — before media, before anything else resolves. Perceived
  latency lives between push delivery and first ring, so nothing blocking sits
  in that path.
- **Push discipline protects future pushes.** Android demotes an app's
  high-priority messages if they don't visibly surface something to the user, and
  a demoted push cannot wake a call. Therefore: every push ends in something
  visible (ring, missed-call notice, or a stated failure); no speculative or
  silent pushes ever; the handler checks the delivered priority before doing
  call work. Duplicate deliveries are deduped on the call's identifier.
- **Ghost rings are canceled honestly.** A ring whose underlying call has ended
  (caller hung up during wake, setup failed) is torn down promptly with an
  honest disconnect cause. A missed-call entry from someone who never got
  through is worse than a ring that arrives half a second later.
- **The undetectable failure modes are documented, not papered over.** A
  force-stopped app receives no FCM at all until next open, and neither that nor
  a dead binding is observable from inside the app while it is happening
  (`PUSH.md` §4). **App hibernation is the same failure with a schedule**: on
  Android 12+, months of non-use lets the system pause the app — permissions
  revoked, scheduled work canceled, pushes undeliverable — which defeats the
  monthly renewal in exactly the never-opened scenario it exists for. Unlike
  force-stop, this one *is* detectable ahead of time
  (`PackageManagerCompat.getUnusedAppRestrictionsStatus()`), so Twilmo checks
  it: onboarding guides the user to turn off "Pause app activity if unused"
  (`ACTION_MANAGE_UNUSED_APP_RESTRICTIONS`), and the registration-health
  readout warns whenever the protection is back on. Mitigation for the rest is
  honest rather than magical: registration repairs itself at every app start,
  and the home screen carries a **registration-health readout** — last
  successful registration, last push received — so "is this thing working?" is
  a question the user can answer. The readout cannot warn *during* an outage,
  and the reachability guarantee is stated with its real edge: a phone whose
  owner neither opens the app nor grants the hibernation exemption will
  eventually stop being reachable, and no client-side design changes that.
- **Unanswered and unreachable calls fall to the number's voicemail /
  no-answer behavior configured Twilio-side**, so a caller never listens to dead
  air (exact behavior is an open question below).

## Outbound

- **The in-app dialer places calls through the SDK** (`Voice.connect`),
  surfaced to the system as a proper Telecom call.
- **Tokens are minted ahead of the call, not during it** (principle 3 in
  `AGENTS.md`). The app caches an unexpired access token and refreshes it
  opportunistically while in use — at app open, and before expiry — so call
  setup normally spends nothing on the token endpoint and a transient endpoint
  failure doesn't block a call while a valid cached token exists. On-demand
  minting at dial time is the fallback, not the design. Refresh is in-use only —
  no scheduled background refresh; an idle app just lets the token lapse and
  re-mints at next open.
- Call setup states are visible immediately (dialing → ringing → connected), and
  every failure — no network, token fetch failed with no cached token, Twilio
  rejected the call — is surfaced with a reason, never a dead button.
- The number the far end sees is the user's Twilio number.
- Whether Twilmo should *also* transparently divert calls dialed in the stock
  dialer (a `CallRedirectionService`, as phomo does for international numbers)
  is an open product question below; v1 as specced is in-app dialing only.

## Telecom integration

- Twilmo registers a **self-managed calling account** and drives calls through
  **`androidx.core-telecom` (`CallsManager`)** — the modern path, which owns the
  foreground-service lifecycle, audio endpoint routing (earpiece / speaker /
  Bluetooth), and coexistence with cellular calls, and which the platform
  documentation steers calling apps toward (`PUSH.md` §3). Twilmo does not touch
  `AudioManager` routing itself.
- Incoming calls post a **CallStyle notification within the platform's 5-second
  window**, with a full-screen ring where `canUseFullScreenIntent()` allows and a
  heads-up notification otherwise.
- **Audio capture waits for Telecom.** On Android 14+ a `microphone` foreground
  service cannot start from the background, so the app never starts capture
  straight off the push — Telecom binding the call, or the user's Answer tap, is
  what brings the microphone up. This ordering is an invariant, not an
  implementation detail: the version that violates it fails only on real
  devices.
- An incoming cellular call during a Twilmo call, and a Twilmo push during a
  cellular call, are first-class tested states — the cellular call is never
  degraded.

## Battery model

- **Nothing stays running while idle.** The rule is "choose the
  battery-efficient architecture," not "background work is forbidden"
  (maintainer, 2026-08-02): what the design avoids is the always-running
  posture — a persistent service, a wakelock, a polling loop, a keep-alive, a
  standing `ConnectivityManager` callback — while necessary background work
  remains allowed with its justification stated. As designed, the app's only
  always-on cost is Google Play services' FCM socket — already open on every
  target device for every other app, costing Twilmo nothing incremental.
- **Rare, deferrable scheduled work is fine when it earns its keep.** The first
  instance is the **monthly registration renewal** (see *Inbound*): Twilio
  expires registration bindings after roughly a year, and a phone whose owner
  never opens the app must still ring, so a coarse `WorkManager` job (roughly
  monthly, deferrable — it renews a ~1-year TTL, so timing precision is
  irrelevant) re-runs registration. Cost: one short network round trip a
  month — effectively zero battery — against principle 1's worst failure. Any
  new scheduled work states the same trade in its PR.
- **During an active call only**, a foreground service (type `phoneCall` /
  `microphone`, managed via core-telecom) keeps the call alive and audible —
  scoped strictly to the call's lifetime.
- The inbound push handler is the one sanctioned wake outside a call, and it
  always terminates in something visible.
- Any change that adds work outside a call's lifetime must justify why it can't
  be on-demand and must be called out explicitly in the PR (`AGENTS.md`).
- Twilmo never asks the user to exempt it from battery optimization as a setup
  step. If a specific OEM's optimizer is ever shown to defer call pushes, that
  prompt becomes a deliberate, documented decision — it sits awkwardly against a
  product whose pitch is battery efficiency. The **hibernation exemption is
  different and is asked for** (see *Inbound*): "Pause app activity if unused"
  cancels the renewal job and blocks pushes outright after months of non-use,
  so declining it doesn't cost battery headroom — it eventually costs
  reachability itself.

## Backend

Twilio's SDK route needs two small server pieces of ours; neither holds state
about calls and nothing of ours is on the media path. The routing webhook *is*
on the inbound signaling path, and the analysis below treats it that way.

- **An access-token endpoint** that mints the short-lived JWTs the SDK
  authenticates with (outbound `connect` and inbound `register` both need one).
  **Its authentication is security-critical**: an endpoint that mints tokens for
  anyone lets anyone place calls billed to the account *and* register to receive
  the user's incoming calls.
- **An inbound routing webhook** (a TwiML App) that answers Twilio's request for
  the number with `<Dial><Client>` aimed at the app's identity.
- Both are small and stateless and are hosted as **Twilio Functions** (decision
  recorded in `TODO.md`; revisitable). A **Firebase project is mandatory** — FCM
  is the wake path — and its service-account key is uploaded to Twilio as a Push
  Credential, so Twilio can send the push itself; the key lives in Twilio's
  credential store, not in this repo or the app.
- **Cost and reliability** (per `AGENTS.md`): a number is roughly $1–1.5/month
  (varies by country); calls bill per-minute at Twilio client/PSTN rates;
  Twilio Functions' free tier covers personal call volume, so expected marginal
  cost is the number plus minutes. Failure modes: if the token endpoint is
  down, outbound falls back to the cached token where one is valid and
  otherwise fails visibly with a reason at dial time. **The routing webhook
  sits ahead of the ring**: Twilio invokes it and waits for its
  `<Dial><Client>` before sending the push, so a cold, slow, or unavailable
  Function delays or prevents the ring — the caller hears Twilio's failure
  behavior (an error announcement / the number's no-answer handling), and
  nothing in-app can observe the miss. That makes the webhook part of the
  inbound reliability budget, not neutral plumbing: it stays tiny and
  dependency-free, its cold-start and response latency are included in the
  real-device ring measurements, and its failure handling is configured
  Twilio-side rather than left to a default error tone. Outbound latency:
  normally zero of ours (cached token), one HTTPS round trip in the fallback
  case.

## Permissions and roles

Requested contextually at first use, never as a wall at first launch:

- `INTERNET`, `ACCESS_NETWORK_STATE` — signaling and media; connectivity checks
  at call time. Install-time.
- `RECORD_AUDIO` — call audio; requested at first call.
- `MANAGE_OWN_CALLS` — the self-managed calling account.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_PHONE_CALL` /
  `FOREGROUND_SERVICE_MICROPHONE` — the in-call foreground service.
- `POST_NOTIFICATIONS` — missed-call notices and the registration-health
  surface. **Not a gate on the incoming-call UI**: an app with
  `MANAGE_OWN_CALLS`, a `ConnectionService`-backed calling account, and a
  registered phone account is exempt for CallStyle notifications, so inbound
  ringing never appears unavailable because this permission was declined
  (`PUSH.md` §13).
- `USE_FULL_SCREEN_INTENT` — the full-screen ring; granted by default to calling
  apps, checked via `canUseFullScreenIntent()` and degraded to heads-up when
  revoked.

The manifest starts minimal and grows with the milestone that exercises each
entry, so the app never ships holding a permission it doesn't use.

## Persistence

- **Backend configuration** (token-endpoint URL and its client secret, the app's
  Twilio identity) is the sensitive data Twilmo stores: encrypted at rest, in a
  backup-excluded store, so secrets are never carried off-device by cloud backup
  or device-to-device transfer.
- **Settings** (theme, notification preferences) are ordinary preferences and may
  be backed up.
- Twilmo keeps no call history of its own in v1 beyond what the platform call
  log records for a Telecom call.

## Privacy

- **Google sees a push per inbound call.** FCM payloads are encrypted in transit
  but not end-to-end, and the Voice SDK's payload format is Twilio's — it
  carries the call's routing metadata, including caller information. This
  disclosure is inherent to the chosen architecture and is documented in
  `docs/PRIVACY.md` before release, not discovered by users afterward.
- Twilio, as the carrier of record for the number, necessarily sees all call
  metadata and media — the same position any phone carrier occupies.
- The app itself collects nothing beyond the sibling repos' standard gated
  telemetry model (Crashlytics dormant without a `google-services.json` — though
  note inbound makes a Firebase *project* mandatory for FCM regardless; whether
  crash telemetry rides the same project is a Phase decision in `TODO.md`).
- The on-device debug log follows the narrow policy in `AGENTS.md` *Privacy*:
  coarse call-flow state only, never a full number, name, credential, or token.

## UI architecture

- Jetpack **Compose** with **Material 3** and dynamic color; light and dark
  themes both first-class. Spacing on the 4dp grid per `AGENTS.md`.
- The surface is intentionally small: a **home/status screen** (registration
  health, permission state), **setup/onboarding**, a **dialer**, and the
  **in-call screen**; incoming calls ring through the platform surface
  (CallStyle / full-screen), not a bespoke one.
- Every user-facing screen has a Roborazzi screenshot test wired into CI.

## Distribution and versioning

- `versionCode` = `git rev-list --count HEAD`; `versionName` =
  `"1.0.<count>+<shortSha>"`, both derived at configure time in
  `app/build.gradle.kts`, matching the sibling repos.
- CI (`.github/workflows/android-ci.yml`, landing with the scaffold milestone)
  builds and unit-tests every PR, records screenshots, and on `main` runs the
  sibling repos' release pipeline; commit subjects become the release "What's
  new" (`AGENTS.md` *Commit messages*). All distribution steps are secret-gated
  and no-op on forks.

## Testing strategy

- **Pure logic is unit-tested exhaustively.** The call state machine — including
  every inbound race: push with no credentials configured, push during a
  cellular call, push after the caller hung up, duplicate push, stale token —
  plus push-payload parsing/dedupe and token-refresh triggering are
  framework-independent Kotlin and carry the correctness weight of the app.
- **UI is screenshot-tested** with Roborazzi under Robolectric, recorded in CI.
- **Push delivery, media, audio routing, and Telecom behavior can only be
  verified on a real device** — the sandbox and emulator have no FCM-to-Doze
  delivery, no microphone, no cellular radio, and no real far end. Changes to
  those paths are flagged for on-device call testing every time and never
  reported as verified on inspection alone. The load-bearing latencies —
  webhook response time (cold and warm), FCM delivery to a Dozing device, and
  wake-to-ring time — are measured on a real Pixel and Samsung early (see
  `TODO.md`), since they are the numbers the whole inbound bet rests on.

## Non-goals (for now)

- **SMS.** Inbound SMS arrives by webhook, never by push to a client, so it
  requires real backend state (`PUSH.md` §9). Worth revisiting precisely
  *because* a token store would strengthen the backend story — but not v1.
- **Transparent redirection of stock-dialer calls** (phomo's
  `CallRedirectionService` role) — open question below; not in v1 as specced.
- **Presenting the user's mobile number as outbound caller ID** — depends on
  Twilio verified-caller-ID configuration; post-v1 at best.
- **Multiple numbers / multiple providers**, call recording, an IVR, or being
  the device's default dialer. Twilmo is one Twilio number done well.

## Open questions

Ordered by how much each would change what gets built.

- **Product shape: pure second line, or also a transparent outbound router?**
  As specced, outbound means dialing inside Twilmo. Should Twilmo also take the
  `CallRedirectionService` role and divert international calls dialed in the
  stock dialer onto the Twilio line, phomo-style? That adds phomo's number
  classification, fail-toward-the-SIM machinery, and the ~5 s redirection
  deadline discipline to scope — a substantial addition, best decided before the
  Telecom milestone.
- **Which country's number(s) first?** Number eligibility varies sharply by
  country (Germany is closed to individuals at Twilio; the UK and US are easy —
  `PUSH.md` §9) and it affects nothing in the architecture but everything in
  onboarding copy and testing.
- **Voicemail / no-answer behavior**: plain Twilio `<Dial>` timeout to a
  `<Record>` voicemail, or ring-only? Configured Twilio-side either way; the
  app needs to know only for the missed-call surface.
- **Where the backend lives**: Twilio Functions is assumed (least moving parts,
  same vendor). A Cloudflare Worker / Cloud Run function would decouple it from
  Twilio at the cost of a second platform. Cheap to revisit until Phase 1 lands.
- **Telemetry**: does Crashlytics ride the same (now-mandatory) Firebase project
  as FCM, keeping the siblings' opt-in gating model?
- **Application ID**: `app.twilmo`, following simmo's `app.simmo` (with `.debug`
  / `.dev` suffix scheme for CI and local builds)?
