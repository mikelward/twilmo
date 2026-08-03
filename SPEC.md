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

- **A second phone line — and only that** (maintainer, 2026-08-02). Twilmo gives
  the phone a Twilio number that can both place and receive calls, alongside the
  SIM. The SIM keeps being the primary line; Twilmo never interferes with SIM
  calls, and an incoming cellular call always wins any contention for the audio
  route. Twilmo does **not** take the call-redirection role or classify numbers
  dialed in the stock dialer — that is Simmo's job (`mikelward/simmo`), and the
  division of labor is deliberate: Simmo decides *where* a dialed call should
  go; Twilmo is one of the places it can send it, via the hand-off intent
  contract below.
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
  (FCM rotated the token), and **credentials changed** — plus an **occasional
  scheduled renewal** whose only job is to keep the binding from aging out of
  Twilio's ~1-year TTL on a phone whose owner never
  opens the app — its cadence and scheduler are the implementation's choice,
  bounded by the battery model. A stale token or an expired binding is a phone
  that silently stops ringing — the app's worst failure mode — so both are
  tested correctness surfaces, not code paths. **A credentials change that
  replaces the identity *or* the registration authority unregisters the old
  binding before registering the new one.** `Voice.register` against new
  credentials does not remove the previous binding — and that holds even when
  the identity string is unchanged, if the Twilio account or endpoint behind
  it changed — so the old number could keep ringing this phone until its TTL
  lapses. Unregistering needs more than the old identity + FCM token —
  `Voice.unregister` authenticates with an access token *for that identity,
  minted by the old authority* — so the switch completes the old binding's
  unregister (minting against the old configuration where needed) **before**
  the old auth configuration is discarded. Where that's impossible — the old
  backend is already gone — the leftover binding is surfaced to the user, not
  silently accepted. The switch path is unit-tested like the other triggers. **Network change is deliberately not a trigger**: it
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
  call work. **This holds even with `POST_NOTIFICATIONS` denied — and a
  passive call-log row is not the visible outcome**; the log is the durable
  record, not a surface anyone sees at push time. The exempt surfaces are the
  call ones: the ring itself (CallStyle is exempt for a registered calling
  app), and a push that can't become an answered call is still raised and
  ended through Telecom with an honest missed/disconnect cause, so that under
  the line model the **platform dialer's own missed-call notification** —
  which Twilmo's permission does not gate — surfaces the miss immediately.
  Under the self-managed fallback the exemption covers the ring, which is the
  push's visible outcome; the suppressed missed-call notice is the residual
  gap onboarding asks to fix and the health readout flags. **A state with no
  visible outlet does not receive pushes at all**: Twilmo holds an inbound
  registration only while its calling account is registered and enabled —
  able to raise the ring or the honest Telecom-completed miss — and when that
  stops being true it unregisters the binding rather than letting invisible
  deliveries arrive and demote the pushes that matter, with the health
  readout saying inbound is off and why. Unregistering is itself a network
  call that can fail — the device may be offline, or the old token authority
  already unreachable — so the invariant doesn't rest on a single attempt:
  an unregister that can't complete is retried as deferred background work
  until confirmed (fine under the *Battery model* — rare, event-driven, and
  it ends), and until confirmation the binding is treated as possibly live —
  a push arriving in that window is still handled to a visible end (the
  stated-failure notification where notifications are granted; otherwise the
  debug log carries the reason and the health readout shows the leftover
  binding). And the retry is not the last line, because it can be permanently
  defeated — the old token authority gone for good — while notifications are
  denied: the **FCM registration token itself is the authority-independent
  kill switch**. When unregistration cannot be confirmed and no visible
  outlet remains, the app deletes its own FCM token, which invalidates every
  binding addressed to this installation at the FCM layer — the stale
  binding's pushes then fail upstream instead of arriving invisibly, which
  also protects the priority standing of future pushes. Nothing live is
  lost: this state exists only when no enabled calling account remains, and
  the next setup mints and registers a fresh token. The health readout still
  surfaces the leftover binding so the user can clear it from the Twilio
  console when convenient. Duplicate deliveries are deduped on **call identifier plus event
  type** — a cancel push carries the same identifier as its invite, so
  identifier-only dedupe would swallow the cancellation and leave a ghost
  ring; the invite → cancel transition is modeled explicitly in the call state
  machine rather than filtered away.
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
- **A valid token is ready when the user dials, wherever possible** (principle
  3 in `AGENTS.md`). The app caches an unexpired access token and keeps it
  fresh opportunistically, so call setup normally spends nothing on the token
  endpoint and a transient endpoint failure doesn't block a call while a valid
  cached token exists. On-demand minting at dial time is the fallback, not the
  design. The exact refresh policy is the implementation's, bounded by the
  battery model — the spec requires only that dialing doesn't normally wait on
  the endpoint.
- Call setup states are visible immediately (dialing → ringing → connected), and
  every failure — no network, token fetch failed with no cached token, Twilio
  rejected the call — is surfaced with a reason, never a dead button.
- The number the far end sees is the user's Twilio number.

### Hand-off intent (Simmo integration)

Twilmo is a first-class hand-off *target* for Simmo's rule engine (see simmo's
`docs/handoff-intents.md` for the mechanism: Simmo cancels the carrier call and
launches the target app at the dialed number). That mechanism shapes the
contract — by the time Twilmo is launched, the user's original call is already
gone, so a dead end or a silent no-op here strands a call. Three tiers, all part
of v1's outbound milestone:

- **Phone-account redirect — the preferred route.** Because Twilmo is a
  call-provider calling account (see *Telecom integration*), Simmo's
  redirection service can move the dialed call onto Twilmo's
  `PhoneAccountHandle` without canceling anything: the platform places the call
  on the line, auto-dial by construction, with nothing to strand. The intent
  tiers below are the fallback for when the account isn't registered/enabled or
  the line model is unavailable on a device.
- **Conventional dial intents — pre-fill.** Twilmo's dialer activity handles
  `ACTION_DIAL` and `ACTION_VIEW` with a `tel:` URI, opening the dialer
  pre-filled with the number and waiting for a tap. This is deliberately
  convention-compliant (a `DIAL`/`VIEW` handler must not place the call
  itself), and it is what makes Twilmo discoverable to Simmo's existing generic
  fallback — Simmo's reachability discovery offers only apps whose
  number-carrying intent resolves, so these filters alone put Twilmo in Simmo's
  editor with zero Simmo-side changes.
- **Explicit hand-off — places the call.** `ACTION_CALL` with a `tel:` URI
  places the call through Twilmo immediately, no tap — the behavior Simmo
  records per-app as `requiresTap = false`, and the right one for
  cancel-and-forward, where a pre-filled keypad after the carrier call
  vanished reads as a bug. **The boundary is enforced, not assumed**: the
  auto-dial activity is exported behind
  `android:permission="android.permission.CALL_PHONE"`, so only senders the
  user has granted phone-call permission (Simmo qualifies) can trigger a paid
  call — an intent filter alone checks nothing, and an unprotected exported
  dial-on-launch activity would let any installed app place calls on the
  user's Twilio account. The pre-fill `DIAL`/`VIEW` filters stay open, as the
  platform convention expects: they cost a tap, not money.
- **"Resolves ≠ ready" is handled honestly.** If Twilmo is launched at a number
  while unconfigured (no backend config, no network, token fetch fails), it
  never dead-ends: the number is preserved on screen, the reason is stated, and
  one tap retries or opens setup. Simmo cannot detect Twilmo's readiness from
  the intent, so Twilmo degrades visibly instead.
- **Number-keyed, never contact-keyed** (maintainer, 2026-08-02). The contract
  requires no contacts integration on either side: Twilmo dials arbitrary PSTN
  numbers, so the intents above carry the number itself, and hand-off must not
  depend on contacts-provider registration entries (the sync-adapter "Connected
  apps" rows apps like WhatsApp write). Simmo's per-contact route exists for
  app-to-app callers that can *only* be reached that way, and its rows have
  proven stale and unverifiable ("resolves ≠ ready"); Twilmo takes the
  dial-intent route instead, which works for any number whether or not it is a
  saved contact. Twilmo declares no contacts sync adapter in v1.
- **Number format**: the `tel:` scheme-specific part as dialed. E.164 preferred
  (Simmo normalizes before launching); national-format digits are parsed
  against the configured home region rather than rejected.
- **Emergency numbers are refused**, always: Twilmo never places an emergency
  call over Twilio — it forwards the number to the platform dialer and says so.
- **No redirect loop — by contract, not by accident.** Under the line model
  Twilmo's connections are *managed*, and a managed outgoing call may be
  offered to the `CallRedirectionService` — so "self-managed calls are never
  redirected" protects only the fallback model and cannot be relied on. The
  loop protection is therefore part of the integration contract, split by how
  the call reached Twilmo: a call **Simmo redirected** onto Twilmo's account is
  not re-offered to the redirection service by the platform; a
  **cancel-and-forward** hand-off carries Simmo's existing pass token; and a
  call the user **places directly on Twilmo** (in-app dialer, stock-dialer
  account selection) is excluded Simmo-side — Simmo's decision function treats
  a call whose initial `PhoneAccountHandle` already belongs to a calling app,
  Twilmo's included, as "proceed unmodified" (its already-on-target
  pass-through, extended to the account dimension). That Simmo-side rule is a
  small follow-up tracked in `TODO.md`, and whether managed call-provider
  calls are in fact offered to redirection on Pixel and Samsung is part of the
  line model's device verification.
- **This surface is a public contract.** Once shipped, the intent filters and
  their behavior are kept stable and recorded here; Simmo-side, Twilmo then
  gets its row in `docs/handoff-intents.md` as a confirmed auto-dial target.

## Telecom integration

- **Twilmo presents itself to the platform as a calling line, not just an app
  that makes calls** (maintainer, 2026-08-02). It registers a Telecom
  `PhoneAccount` with **`CAPABILITY_CALL_PROVIDER`** — the classic call-provider
  integration SIP accounts used — rather than only a self-managed account. What
  the line model buys:
  - the **stock dialer can place calls on Twilmo** — per-call ("place call
    using…") or as the account's default — with the system in-call UI and call
    log attributing calls to the line;
  - **Simmo can redirect a dialed call onto Twilmo's account directly**
    (phone-account redirect — the mechanism simmo's `docs/handoff-intents.md`
    records as unavailable for every current target), which is strictly better
    than cancel-and-forward: the platform re-places the call itself, nothing is
    stranded, and there is no pre-fill tap;
  - incoming calls arrive through `addNewIncomingCall` on the account and ring
    through the **platform's own incoming-call UI**.
- **Verification owed before this hardens**: a call-provider account must be
  enabled by the user under Calling accounts (an onboarding step), and
  third-party call-provider behavior — including redirection onto the account
  and the OEM dialer's in-call UI driving a Twilio SDK call — needs real-device
  proof on both Pixel and Samsung. If the line model proves unreliable on an
  OEM, the fallback is the **self-managed model** via `androidx.core-telecom`
  (`CallsManager`), with a CallStyle notification within the platform's
  5-second window and a full-screen ring where `canUseFullScreenIntent()`
  allows — and Simmo integration then rides the intent contract instead. Either
  way Telecom owns audio endpoint routing (earpiece / speaker / Bluetooth);
  Twilmo never touches `AudioManager` routing itself.
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
  instance is the **registration renewal** (see *Inbound*): Twilio expires
  registration bindings after roughly a year, and a phone whose owner never
  opens the app must still ring, so scheduled work re-runs registration
  occasionally. Its cost — a short network round trip at long intervals — is
  effectively zero battery against principle 1's worst failure. Scheduling
  specifics (mechanism, cadence) live in the code, not here; any new scheduled
  work states the same trade in its PR.
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
- `MANAGE_OWN_CALLS` — declared under **both** Telecom models, not just the
  self-managed fallback: the `phoneCall` foreground-service type requires the
  app to hold this permission (or the default-dialer role, which Twilmo never
  takes), so dropping it under the line model would make the in-call service
  throw. Whether the line model even needs an app-owned foreground service —
  Telecom binding the managed `ConnectionService` may carry the process on its
  own — is part of the device verification in *Telecom integration*; the
  permission stays declared either way so the service is always legal.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_PHONE_CALL` /
  `FOREGROUND_SERVICE_MICROPHONE` — the in-call foreground service.
- `POST_NOTIFICATIONS` — missed-call notices and the registration-health
  surface. **Not a gate on the incoming-call UI**: an app with
  `MANAGE_OWN_CALLS`, a `ConnectionService`-backed calling account, and a
  registered phone account is exempt for CallStyle notifications, so inbound
  ringing never appears unavailable because this permission was declined
  (`PUSH.md` §13). A denial does suppress the app's own non-exempt notices,
  so the push discipline routes those outcomes through exempt surfaces
  (the exempt ring, and Telecom disconnect handling that puts the miss in
  front of the user via the platform dialer under the line model — see
  *Inbound*) and the health readout flags the denial as a gap.
- `USE_FULL_SCREEN_INTENT` — the full-screen ring in the self-managed fallback
  model; granted by default to calling apps, checked via
  `canUseFullScreenIntent()` and degraded to heads-up when revoked. Under the
  line model the platform's own incoming-call UI rings instead.

The manifest starts minimal and grows with the milestone that exercises each
entry, so the app never ships holding a permission it doesn't use.

## Persistence

- **Backend configuration is split by sensitivity, so a restored device is not
  a blank slate** (principle 2 in `AGENTS.md`: keep what is safe to keep). The
  **token-endpoint URL and the app's Twilio identity** are configuration, not
  secrets — they ride ordinary backup and device-to-device transfer, so after a
  device replacement the app comes back knowing its own setup. Only the
  **client secret** lives in the encrypted, backup-excluded store: a credential
  should not transit cloud backup, and that is a narrow, stated exception, not
  a silent wipe. After a restore, the app detects the missing secret and asks
  for exactly that — one field, with the reason on screen ("restored from
  backup; re-enter the endpoint secret") — instead of presenting first-run
  setup as if nothing had ever been configured. Inbound registration resumes
  on the next app start after re-entry, per the *Inbound* triggers.
- **The stored secret is bound to the configuration it was saved for.** The
  secret's store also records, in the same atomic write, the authority
  fingerprint (a one-way hash of endpoint + identity). Because the two halves
  live in separate stores, a save can be torn — by process death or power loss
  between the two commits — and no write ordering alone can prevent it; the
  binding makes the tear *detectable*: a secret whose fingerprint doesn't
  match the current configuration is treated as missing, so the app reports
  the honest "secret needed" state instead of looking configured while every
  token mint fails (principle 1: never fail silently). The configuration is
  written first and the secret — bound to it — last, so an interrupted save
  can never produce a false "ready" and always keeps the configuration the
  user just entered: only the one secret field needs re-entering
  (principle 2: never lose the user's work).
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
- **Privacy commitments follow the product, not the other way around**
  (maintainer, 2026-08-02). The goal is preserving the user's privacy, weighed
  like any other design decision against functionality, data loss,
  performance, cost, and simplicity — not adopting the narrowest possible
  posture and prematurely encoding it as final. `docs/PRIVACY.md` is written
  from what actually ships, when it ships; a flow the user knowingly chooses
  (sharing a debug report, backing up settings) is a feature to design well,
  not a violation to design away.

## UI architecture

- Jetpack **Compose** with **Material 3** and dynamic color; light and dark
  themes both first-class. Spacing on the 4dp grid per `AGENTS.md`.
- The surface is intentionally small: a **home/status screen** (registration
  health, permission state), **setup/onboarding**, a **dialer**, and the
  **in-call screen**; incoming calls ring through the platform surface
  (CallStyle / full-screen), not a bespoke one.
- Every user-facing screen has a Roborazzi screenshot test wired into CI.

## Distribution and versioning

- Application ID **`app.twilmo`** (maintainer, 2026-08-02), with simmo's
  suffix scheme on the **debug build type**: `.debug` for the CI tester
  build, `.dev` for a local debug build, so the two co-install cleanly.
  Release builds keep the production ID everywhere — a local release build
  exists for R8/build inspection (unsigned without the Play keystore, so not
  installable) and wears the Dev launcher identity as a warning label; the
  artifacts that ship are CI-built.
- `versionCode` = `git rev-list --count HEAD`; `versionName` =
  `"<base>.<count>+<shortSha>"`, both derived at configure time in
  `app/build.gradle.kts`, matching the sibling repos. The base is `0.1` until
  the MVP ships — pre-1.0 is deliberate for an app that hasn't reached its
  first release.
- CI (`.github/workflows/android-ci.yml`) builds, unit-tests, and lints every
  PR and records screenshots. The sibling repos' release pipeline —
  Firebase/Play distribution on pushes to `main`, commit subjects becoming
  the release "What's new" (`AGENTS.md` *Commit messages*), every
  distribution step secret-gated and no-op on forks — lands with Phase 5's
  Play wiring, once the accounts exist to gate on (`TODO.md`).

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
- **Transparent redirection of stock-dialer calls** (the
  `CallRedirectionService` role). Decided, not open: that is Simmo's job, and
  Twilmo integrates with it as a hand-off target instead (see *Hand-off
  intent*).
- **A contacts sync adapter** ("Connected apps" entries under contacts) — the
  hand-off contract is deliberately number-keyed, so nothing needs it.
- **All outbound calls on Twilmo by default — a v2 exploration** (maintainer,
  2026-08-02). Under the line model this may need no new machinery: the stock
  dialer can already make a calling account the default. The exploration is
  whether it's *desirable* — per-minute cost vs. the SIM's plan, reliability on
  no-network moments, and the guarantee that emergency and short-code calls
  always stay on the SIM. Tracked in `TODO.md`; nothing in v1 depends on the
  answer.
- **Presenting the user's mobile number as outbound caller ID** — depends on
  Twilio verified-caller-ID configuration; post-v1 at best.
- **Multiple numbers / multiple providers**, call recording, an IVR, or being
  the device's default dialer. Twilmo is one Twilio number done well.

## Open questions

Ordered by how much each would change what gets built.

- **Does the line model survive contact with real devices?** Four specific
  unknowns, all device-verification rather than design: whether a third-party
  `CAPABILITY_CALL_PROVIDER` account is offered by the stock dialer's account
  chooser on Pixel *and* Samsung, whether a `CallRedirectionService` (Simmo)
  can redirect onto it, whether the OEM in-call UI drives a Twilio SDK call's
  audio correctly, and whether outgoing calls placed on the account are
  themselves offered to a redirection service (which decides how much of the
  "No redirect loop" contract Simmo's account pass-through has to carry). The
  self-managed fallback plus the auto-dial intent is the specced answer if the
  model fails.
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
