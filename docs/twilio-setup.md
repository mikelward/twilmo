# Twilio setup

The owner-run checklist that stands up Twilmo's Twilio side: a number, the
credentials the app dials with, the push credential that makes it ring, and
the two small Functions in [`functions/`](../functions/) that are the entire
backend (SPEC → *Backend*). **No secrets live in this repo** — this document
describes them; the Twilio console holds them. Every identifier below is a
placeholder of the right shape, not a real value.

## What you end up with

| Piece | Where it lives | What the app gets |
|---|---|---|
| Phone number | Twilio console | The second line's number |
| API key (`SKxxxx…` + secret) | Console → Account → API keys | Never leaves Twilio; the token Function uses it to mint |
| TwiML App (`APxxxx…`) | Console → Voice → TwiML apps | Routes the app's outbound `connect()` to the outbound Function |
| Push Credential (`CRxxxx…`) | Console → Voice → Push credentials | Lets Twilio send the FCM push that rings the phone |
| Token Function URL | Functions service | The **endpoint URL** the app's setup screen asks for |
| Shared secret | Functions env var + app setup screen | Authenticates the app to the token Function |

## Steps

1. **Number.** Buy (or port) a voice-capable number. Cost: ~$1.15/month for a
   US local number, plus per-minute voice rates (~$0.014/min outbound,
   ~$0.0085/min inbound for US calls at current list prices).
2. **API key.** Console → Account → API keys & tokens → create a *standard*
   key. Record the SID (`SKxxxx…`) and secret **in the console flow only** —
   the secret is shown once and goes straight into the Functions service's
   environment variables in step 5, nowhere else.
3. **Firebase project.** Inbound wake-up rides FCM, so create the Firebase
   project, then **register the Android apps in it** — a project alone
   produces no client config. Add three Android apps, one per application
   ID the build produces: `app.twilmo` (Play), `app.twilmo.debug` (CI
   tester), and `app.twilmo.dev` (local build). Then download
   `google-services.json` (one file; it lists every registered app) — the
   inbound milestone ships it in the Android build. Separately, in Project
   settings → Service accounts, generate a service-account key JSON for
   step 4.
4. **Push Credential.** Console → Voice → Push credentials → create one of
   type FCM and upload the service-account JSON from step 3. Record the
   credential SID (`CRxxxx…`).
5. **Functions service.** Create a Functions service (console → Functions &
   Assets) and add the three handler files from [`functions/`](../functions/)
   (the `*.test.js` files stay in the repo — CI runs them; they are not
   deployed):
   - `token.js` — **public** visibility, *not* protected: it is called by
     the app, not by Twilio, so Twilio's signature check would reject every
     legitimate request. It authenticates callers itself via the
     `x-twilmo-secret` header.
   - `inbound.protected.js` — **protected** visibility: it is called only by
     Twilio (the number's voice webhook), and protection makes the runtime
     verify Twilio's request signature.
   - `outbound.protected.js` — **protected** visibility: it is called only
     by Twilio (the TwiML App's voice URL) when the app places a call.

   Set the service's environment variables (Settings → Environment
   variables): `API_KEY_SID`, `API_KEY_SECRET`, `TWIML_APP_SID` (after step
   6), `PUSH_CREDENTIAL_SID`, `CLIENT_IDENTITY` (any stable string naming
   this app install's line, e.g. `twilmo`), `CALLER_ID` (the number from
   step 1, E.164 — outbound calls present it), and `SHARED_SECRET` (a long
   random string; it is what the app's setup screen calls the client
   secret). `ACCOUNT_SID` and `AUTH_TOKEN` are provided by the runtime.
   Deploy, and record two deployed HTTPS URLs: the token Function's — the
   app's **endpoint URL** — and the outbound Function's, for step 6.
6. **TwiML App.** Console → Voice → TwiML apps → create one; set its
   **voice request URL to the outbound Function's deployed HTTPS URL** from
   step 5 (the URL, not the filename) — when the app calls the SDK's
   `connect()`, Twilio asks this URL how to route the call. The contract:
   the app passes the dialed number, E.164, as the `To` connect parameter,
   and the Function dials it with `CALLER_ID` as the caller ID. Then put
   the TwiML App's SID (`APxxxx…`) into the service's `TWIML_APP_SID` env
   var **and redeploy the service** — a deployed build bakes its
   environment in, so an env var set after step 5's deploy takes effect
   only on the next deployment; without the redeploy, minted tokens lack
   the outgoing application SID and outbound calls cannot route.
7. **Wire the number.** Console → the number → Voice configuration → "A call
   comes in" → Function → `inbound.protected.js`. From then on, calls to the
   number hit the Function, which dials the app's client identity — Twilio
   holds the call and sends the high-priority push itself (SPEC →
   *Inbound*). Then set the **call fallback URL** ("Primary handler fails")
   to a **TwiML Bin** (console → TwiML Bins) with a short honest message,
   e.g. `<Response><Say>This number can't take calls right now. Please try
   again later.</Say></Response>` — TwiML Bins are served by Twilio's core,
   independent of the Functions service, so a Functions outage degrades to
   an honest announcement instead of Twilio's default error tone (SPEC →
   *Backend*: webhook failure handling is configured Twilio-side). The miss
   itself remains invisible to the app — this failure class is documented
   under SPEC's undetectable failures.
8. **App setup screen** (Phase 2): enter the endpoint URL (token Function
   URL), the identity from `CLIENT_IDENTITY`, and the shared secret.

## The token endpoint's contract

The app depends on exactly this shape (revisitable while unshipped; recorded
in `TODO.md` → *Decisions needing review*):

- **Request**: `GET`/`POST` to the token Function URL with header
  `x-twilmo-secret: <shared secret>`.
- **Success** (`200`, JSON): `{ "token": "<jwt>", "expiresInSeconds": 3600,
  "identity": "<identity>" }`. The app computes the cache expiry from
  `expiresInSeconds` against its own clock — it never parses the JWT.
- **Failure**: `401` for a missing/wrong secret; anything else is surfaced
  by the app as "endpoint down" with the mint failure's reason at dial time
  (SPEC → *Outbound*).

## Cost and reliability

- **Functions**: free tier covers 10,000 invocations/month; beyond that
  $0.0001/invocation. At personal-use call volumes this is effectively $0.
- **The inbound Function sits ahead of the ring** (SPEC → *Backend*): its
  cold-start latency is part of the push-to-ring budget and is one of the
  three load-bearing latencies Phase 4 measures on a real device.
- **If the Functions service is down**, inbound calls fail at Twilio (the
  caller hears an error; nothing reaches the phone) and outbound token mints
  fail visibly at dial time — a cached token still dials, per the token
  readiness policy. Both failure modes are surfaced, never silent.
