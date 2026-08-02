# Twilmo

Android app for making and receiving phone calls on a Twilio number — reliably, and
without draining the battery (Kotlin, Compose, single `:app` module, Twilio Voice
Android SDK). Product and architecture decisions live in `SPEC.md`; the phased plan
lives in `TODO.md`. This repo mirrors the engineering conventions of its sibling
repos: `mikelward/simmo` is the reference for conventions, and when a convention is
underspecified here, that repo's `AGENTS.md` is the tiebreaker. `mikelward/phomo` is
the sibling that explored this product space (its `PUSH.md` is the design study
behind Twilmo's architecture).

## Project documentation

- Keep `SPEC.md` up to date when changing product behavior, architecture, the
  push/registration model, the Telecom integration, persistence, permissions,
  navigation, or testing strategy.
- **`SPEC.md` records product, functionality, and architecture decisions — not
  low-level implementation detail.** It captures *what* Twilmo does and *why* a
  design was chosen (the vendor-SDK route and its lock-in trade-off, the push-woken
  inbound model, the zero-idle battery posture), so a reader can understand and QA
  the product from the spec. Ask "would this still be true and worth stating if the
  implementation were rewritten?" — if not, leave it in the code and its comments.
- Keep `TODO.md` current: check items off as they land, add newly discovered work to
  the right phase.

## Engineering quality bar

These are the principles, in priority order. Where a specific rule below seems to
conflict with one of them, the principle wins and the rule is what needs fixing.

1. **Never miss a call, and never fail silently.** Both directions count: an inbound
   call that doesn't ring and an outbound call that doesn't connect are the worst
   outcomes this app has. The second worst is failing *quietly* — and this app's
   signature failure mode is exactly that: a stale push token, a force-stopped app,
   or a dead credential means the phone **silently stops ringing**, and nothing
   in-app can observe it while it's happening. Silence is what makes a user conclude
   "Twilmo stopped working" with nothing to go on. If Twilmo can't do the right
   thing, it does the safe thing **and says so** — a notification where the user is
   looking, and always a line in the debug log with the reason.
2. **Never lose the user's work.** Account configuration, settings, the trust that
   the number keeps working — the user set that up, and none of it is reproducible
   from anywhere else without redoing the setup. Data loss is never a side effect of
   a design decision; where a real constraint seems to force it, the trade-off is
   the user's to make, stated plainly, not one taken quietly on their behalf.
3. **Do the work ahead of time.** Anything the ring needs should already be in place
   before the call arrives: the FCM token registered with Twilio, credentials
   validated, the incoming-call path warm enough that push → visible ring is short.
   Preparation is what buys both speed and correctness at the moment it matters.
4. **Don't make the user wait for something that could have happened in the
   background.** Nothing that blocks a thread belongs on the push-to-ring path, the
   call-setup path, or in front of a screen's first frame.
5. **Show feedback as early as possible.** Appearing to do nothing is its own
   failure. When a push lands, the phone starts ringing *immediately* — before
   caller details resolve, before media is up. When the user taps Call, something
   visibly happens at once. A screen that appears immediately with a placeholder
   beats one that appears a second later fully-formed.
6. **Say why.** Every non-obvious action gets its reason recorded where the next
   reader will need it — a comment for a subtle mechanism, the debug log for a call
   outcome, the PR for a design trade-off.

Concretely, every change must hold the line on **correctness, battery efficiency,
call reliability & audio quality, and the inbound wake path**:

- **Correctness**: the change matches `SPEC.md` and the user's stated intent,
  handles the obvious edge cases (no network / cellular-only / captive Wi-Fi,
  permission denied, missing credentials, token fetch failure, the far end
  rejecting or not answering, an incoming cellular call arriving mid-Twilmo-call, a
  push arriving after the caller hung up, process death, configuration change), and
  preserves existing invariants. New behavior is covered by a unit test; when
  fixing a bug, add a test that fails before the fix and passes after.
- **Battery efficiency**: a first-class product constraint, not a nice-to-have.
  Twilmo's whole architecture exists so that **nothing runs while the phone is
  idle** — the only always-on cost is Google Play services' FCM socket, which every
  app on the phone already shares. **The rule is "choose the battery-efficient
  architecture," not "background work is forbidden"** (maintainer, 2026-08-02).
  What that means in practice: the always-running posture — a persistent
  service, a wakelock, a polling loop, a network keep-alive, a standing
  `ConnectivityManager` callback — is the thing to design away from, and rare
  or deferrable background work (`WorkManager` — e.g. the occasional
  registration renewal, `SPEC.md` → *Battery model*) is acceptable when it's
  necessary: say in the PR what it buys and why on-demand can't cover it. When
  in doubt, prefer the design that does nothing in the background.
- **Call reliability & audio quality**: when a call connects it must sound clean
  both ways and end cleanly — echo, one-way audio, or a broken Bluetooth handoff
  destroys trust the way a cosmetic bug never would. Prefer the Twilio SDK's and
  the platform Telecom framework's proven defaults over hand-rolled media or audio
  routing. The audio focus / routing / Telecom handshake must be correct for
  earpiece, speaker, Bluetooth, the proximity sensor, and any concurrent cellular
  call.
- **The inbound wake path.** The push-to-ring pipeline is this app's equivalent of
  a hot path, and it has its own discipline:
  - **Every push ends in something visible.** A high-priority FCM message that
    produces no user-visible result trains Android's priority-downgrade heuristic
    to demote future call pushes — a self-inflicted wound. The handler always ends
    in an incoming-call surface, a missed-call notice, or a stated failure. Never a
    silent return.
  - **Ring first, resolve later** (principle 5): post the incoming-call
    notification immediately on push receipt; fill in details as they arrive.
  - **Token rotation is a correctness surface, with tests.** `onNewToken` and app
    start both re-register with Twilio; a stale token is a phone that silently
    stops ringing. Network change is **not** a registration trigger — Play
    services already handles that for the FCM socket, and a standing connectivity
    callback is exactly the background work the battery rule bans.
  - **Ghost rings are canceled honestly.** A ring raised from a push whose call has
    since ended (caller hung up, setup failed) is torn down promptly with an honest
    disconnect cause — a phantom missed-call entry is worse than a slightly later
    ring.
  - **Audio capture waits for Telecom.** `RECORD_AUDIO` is while-in-use and a
    `microphone` foreground service can't start from the background on Android
    14+; the app captures audio only once Telecom or the user's Answer tap has
    brought it up — never straight off the push.
  - A degraded outcome on this path is reported, not swallowed: a call that went to
    voicemail because the app couldn't come up leaves a reason in the debug log and
    a notice the user can see.

When you cannot verify one of these locally (the sandbox has no radio, microphone,
push delivery, or Twilio peer), say so explicitly in the chat update — "verified by
unit test; the push-to-ring path needs a device check" — rather than implying all
were checked. Real-device behavior for anything touching push delivery, media, audio
routing, or Telecom is the pillar most often still owed.

## Spacing

Stick to a 4dp grid for every padding/margin/spacing value (`4`, `8`, `12`, `16`,
`24`, ...); reuse values already used by sibling composables; symmetry by default,
and any asymmetry gets a one-sentence justification in the PR. Flag off-grid or
inconsistent spacing you notice even outside the diff (file, line, proposed fix)
without silently fixing it in the same commit.

## Git workflow

- **These rules assume an `origin` remote.** If the environment supports remote Git,
  the absence of `origin` is a configuration error: say so and stop rather than
  improvising a local substitute. Sandboxes without remote Git support may continue
  on the provided local branch without fetching or rebasing from `origin/main`;
  commit the work locally, clearly report that it was not pushed and that no PR was
  opened, and leave remote Git operations for a capable environment.
- **Branch naming.** Feature branches are prefixed with the agent's own short name:
  `<agent>/<short-topic>` (`claude/...` for Claude Code, `codex/...` for Codex, and
  so on). One topic per branch; never commit to `main`. The placeholder `<agent>`
  stands in for whichever prefix you use — don't hard-code `claude/` unless you
  *are* Claude Code.
- **Merge cue (`merged` / `I merged` / `landed` / merge webhook) runs hygiene
  *before* engaging with the rest of the message:** `git fetch origin main`, cut a
  fresh `<agent>/<short-topic>` branch off `origin/main`, announce the switch.
  Where the sandbox has no remote, the cue can't be honored as written — say so and
  ask for a synced checkout rather than branching off a stale `main`.
- **After a merge, take a fresh `<agent>/<short-topic>`** — don't reset the merged
  name onto the new base. Its remote ref still points at the pre-merge tip, so
  `origin/<branch>..HEAD` keeps spanning the merged commits. When a sandbox pins
  the branch name so a fresh one isn't available, say so and ask before resetting
  it — no short check reliably separates "already merged" from "not yet merged",
  and guessing costs someone their work. Don't reach for `--force-with-lease` as
  the safety net either — fetching updates the remote-tracking ref the lease
  compares against, so a commit you have already fetched passes the lease
  unnoticed.
- **The agent authors; whoever merges takes over the committer line.** A squash or
  rebase merge rewrites the committer to the person who pressed the button. That's
  expected — never re-author or amend already-merged commits to "fix" authorship.
- In environments with remote Git support, always start work from the latest
  `origin/main`: `git fetch origin main` and rebase the working branch onto it
  before the first commit, even when the branch already exists. Resolve conflicts
  rather than abandoning the rebase, and never push commits on an out-of-date base
  when a fast-forward rebase onto `origin/main` was possible.
- **Use `git worktree` when it's available.** Give each branch its own worktree
  instead of switching branches in place.
- **Structure the branch as a sequence of logical commits, rebasing and squashing
  as needed.** Each commit is one coherent change that stands on its own —
  buildable and green by itself. The repo rebase-merges, so every commit lands on
  `main` individually with its own subject, blame lines, and bisect step.
- Clean up the unmerged commit history before requesting review and again before
  merge (`git rebase -i origin/main`): iteration leaves `fix CI` / `address
  review` / `wip` churn, and nothing squashes it for you. After rewriting,
  force-push with `git push --force-with-lease` (never bare `--force`). Ask before
  rewriting commits that have been individually reviewed.
- **Unshallow before answering anything that depends on git history depth.** The
  sandbox clones shallow, so `git rev-list --count`, `git log` past the shallow
  boundary, blame, and any "what versionCode is this" question return wrong answers
  without warning. If `git rev-parse --is-shallow-repository` says `true`, run
  `git fetch --unshallow origin main` first, then re-check; if it is still `true`,
  say the history is truncated instead of quoting a versionCode.

## Commit messages

- Write every subject for end users, sentence case, plain English, no internal
  symbol names, ≤ ~70 characters; engineering detail goes in the body. This repo
  uses the sibling repos' release pipeline: every release-worthy commit subject in
  a push to `main` ships as a bullet in the Play "What's new" list.
- Because the repo rebase-merges, the PR title never lands on `main` — each
  commit's own subject does. Title **every** commit on the branch by these rules,
  not just the PR.
- Keep non-user-facing commits out of release notes with a subject prefix, used
  precisely (the prefix is a promise the commit has **no user-visible effect**):
  - `ci:` — CI / workflow plumbing.
  - `docs:` — documentation only (`docs/PRIVACY.md` is the exception — it backs the
    hosted privacy policy, so it is user-facing).
  - `internal:` — build config, dependency upgrades, other plumbing.
  - `refactor:` — behavior-preserving code changes.
  - `test:` / `tests:` — test-only changes.
- **Housekeeping paths are dropped whatever the subject says.** A commit whose
  every changed path is a `.md` file (at any depth) or a root dotfile / dotdir
  (`.github/`, `.claude/`, `.gitignore`, …) never reaches the notes, prefixed or
  not — with `docs/PRIVACY.md` the exception. Prefix those commits anyway, so the
  intent is explicit.
- **Surviving subjects ship as a `• `-bulleted list, oldest-first** — always
  bulleted, even when only one commit qualifies. Bodies are always dropped.
- **Play caps "What's new" at 500 characters per language.** CI measures the full
  formatted output and drops whole trailing subjects until it fits. Don't line up a
  long stack of small commits when one of them tells the user-facing story on its
  own — squash the supporting work into it.

## Autonomy

- **Open the PR without being asked.** Pushing a finished branch and opening its
  pull request are one step, not two. The exception is an explicit instruction not
  to ("just commit", "no PR yet"), which holds until the user lifts it. This file
  is the repo owner's standing request for that PR.
- **Opening the PR includes wiring up the watch.** In the same step, subscribe to
  the PR's activity (`subscribe_pr_activity`) *and* arm the first scheduled check.
  Both, not either: the subscription gives you review comments and CI results as
  they land, and the scheduled check catches the ones the webhook drops.
- **Poll your own open PRs every 5 minutes** — the ones you opened or were
  explicitly asked to watch — for new review comments, CI status, approvals, and
  the Codex thumbs up. Never end a turn by going idle with one of yours still open:
  arm the next check with whatever the client offers (`send_later`, a scheduled
  task / cron, `/loop`), and arm it *without asking*. Merging doesn't end the watch
  either: drop to a slower cadence (every half hour or so) and keep handling late
  comments per the reply-or-resolve rule under *Working with PRs*.
- **Three polling states, so the 5-minute cadence has an end.** Five minutes is for
  a PR with something outstanding: CI running, a review requested, a comment
  unanswered, a merge conflict. Once a PR is green, reviewed, and has nothing left
  but the merge — or is merged and only waiting out late comments — drop to
  half-hourly. Stop entirely when it merges or closes and the late-comment window
  has passed.
- **One pending check per PR, not one per wake-up.** Before arming, reuse or cancel
  the pending one (`update_trigger`, or `delete_trigger` then re-arm) so exactly
  one check is outstanding.
- **"Drive" means run the loop automatically**: pick the next task, implement it,
  open the PR, wait for the automatic Codex review, address every comment, merge
  once CI is green and Codex has left its thumbs up — then pick the next actionable
  `TODO.md` item and go around again. Actionable means ready to build: skip
  anything explicitly deferred or waiting on a product decision rather than
  guessing the decision.
- **A red baseline is the next task.** Before pulling anything from `TODO.md`, run
  `./gradlew test` and `./gradlew lint` and get them green. A preexisting failure
  is work to do, not a thing to classify as "unrelated" and step around. Fix it
  first (as its own first commit, per *Testing expectations*), then pick the task.
- **"Autopilot" is drive without blocking on the user.** Wherever drive would stop
  and ask, autopilot takes its best guess and keeps going, preferring the option
  that is cheapest to undo or change later. Record each guess in `TODO.md` under a
  `Decisions needing review` heading — what was decided, what the alternative was,
  and why it's reversible. The carve-out is for destructive or irreversible actions
  *outside* the loop — rewriting shared history, deleting work, anything reaching a
  system beyond this repo — which still wait for a real answer. Privacy uncertainty
  is never inside the loop either: if you can't tell whether something is user
  data — a number, a contact, a token, an identifier — it waits for a real answer,
  since a push can't be un-published.

## Working with PRs

- Prefer the `mcp__github__*` MCP tools for GitHub operations; the `gh` CLI is not
  installed in the sandbox. If your client exposes neither, say so rather than
  guessing at the outcome of an operation you couldn't perform.
- **"Drive to merge"**: open the PR, wait for the automatic Codex review, address
  every review comment — fix it if you agree, reply on the thread saying why if you
  don't — and merge once CI is green and Codex has left its thumbs up.
- **Merge when green + Codex thumbs up, then continue.** Once a PR's CI is green
  and Codex has finished its pass with no unaddressed suggestions (its "no
  suggestions" outcome is a 👍 reaction; suggestion threads count as addressed once
  fixed or answered), rebase-merge the PR without waiting for a further go-ahead.
- **Codex is the automated reviewer on this repo** — not Copilot. Its reviews are
  triggered automatically; you don't request them.
- **Address Codex comments automatically — don't wait to be asked.** Fold the fix
  into the commit it belongs to (rebase / `--fixup`) rather than tacking on an
  "address review" commit.
- **`resolve_review_thread` works — pass the thread ID, not a comment ID.**
  `mcp__github__pull_request_read` / `get_review_comments` returns each thread's
  node ID (`PRRT_*`) on `review_threads[].id`; pass that to
  `mcp__github__resolve_review_thread` as `threadId`. A comment's node ID
  (`PRRC_*`) fails — they're different objects.
- **Report when Codex finishes reviewing a fresh push** — a one-liner naming the
  SHA and comment count, tied to the *latest* pushed SHA.
- **Judge every review comment on merit, whoever wrote it.** Verify the claim
  before acting; if it doesn't hold up, reply saying why and decline.
- Never leave a review comment thread silently dismissed: reply on the thread or
  resolve it.
- **Report the Android `versionCode` after every merge to `main`.** Fetch `main`
  and run `git rev-list --count origin/main` (`app/build.gradle.kts` derives the
  versionCode from this count). Report it as e.g. `Need versionCode 12 (b81c23d)
  or higher to test PR #5's fix`.
- Link every open PR in the stack (one URL per line) whenever you push, summarize
  CI, or invite review.
- Refresh the PR title and body on every push so they describe the full, latest
  state of the branch — re-read `git diff origin/main...HEAD` and patch whatever
  drifted.
- Keep watching merged PRs for late review comments; stop once every post-merge
  comment is handled *and* the PR has gone ~24h without a new one. A PR closed
  without merging gets the same treatment, timed from the close.
- Skip echo events silently: if a webhook event's body matches a comment you just
  posted, it's your own echo — continue without comment.
- On CI failure: check for the failing-tests PR comment first; no comment means the
  failure is earlier than tests (compile, lint, resource merge). The PR `build` job
  builds `refs/pull/<N>/merge` — your branch *merged with main* — so reproduce with
  `git merge origin/main --no-commit` before bisecting your own commits. Check
  whether the failure is pre-existing on the base commit before debugging.

## Talking to the user

- **One question at a time.** Never stack multiple questions in a single turn — ask
  the most important one, wait for the answer, then ask the next if you still need
  it.
- **Don't interrupt.** Never fire off a question while the user is still typing.
- **Keep replies short — don't dump a full page.** Lead with the single most
  important point and stop.
- **End the turn by restating any pending decision.** If you're waiting on an
  answer, the last line of the reply is that question, written out in about a
  sentence, restated every turn until it's answered. Nothing pending, no line.

## Asking questions

- Ask questions as plain chat messages. Claude specifically: never use
  `AskUserQuestion`, Claude Code's multiple-choice question prompt — it's broken in
  the Claude mobile app, so a question asked through it may be unanswerable.
- After asking, stop and wait for the answer. Don't proceed on an assumed answer,
  pick a "recommended" option yourself, or keep working on the part the question
  affects.
- Acknowledge every answer explicitly before acting on it.
- Whenever you change direction — because of an answer, something discovered in
  the code, a failing check, or any other reason — say so immediately in chat.

## Error handling

- **Don't silently swallow exceptions.** Every catch block needs to do three
  things: **log** the exception with enough context to identify the failed call —
  but **sanitized context only** (never a phone number, contact name, credential,
  or push/access token; the *Privacy* rule applies to logs too); **clean up** what
  the `try` block acquired (`use { … }` / `finally`) so a failure doesn't leak
  resources or leave state half-mutated; and **handle the edge case explicitly** —
  pick how the caller sees this failure rather than letting control fall through.
  Catching `Throwable` (or a blanket `Exception`) also swallows
  `CancellationException`, which breaks structured concurrency — narrow the type,
  or rethrow `CancellationException` first. **On the push-to-ring path this rule
  has teeth:** a swallowed exception between the FCM message arriving and the
  incoming-call notification posting is a phone that never rang, with no trace.
  Every catch there must still end in something visible — an incoming-call
  surface, a missed-call notice, or a logged, user-visible failure — never a
  silent fall-through. If you genuinely do want to ignore a specific failure,
  name the reason in a one-line comment and still log at debug.

## Privacy

- **Never put user data in any artifact that leaves this machine.** That includes
  commit subjects and bodies, PR titles / descriptions / comments, review replies,
  issue text, branch names, code comments, test fixtures, screenshot snapshots,
  and anything else that ends up on GitHub, the Play Console, or in logs. This app
  handles PII by definition — **phone numbers, call logs, contact names, the
  user's Twilio number, Twilio account identifiers (Account SID, API keys, auth
  tokens, Call SIDs tied to real calls), FCM registration tokens, access tokens,
  and the backend endpoint's URL and secrets**. None of it goes into a commit, a
  PR, a bug reproduction, or a test fixture. If a user-supplied bug report
  contains real numbers or SIDs, paraphrase — don't quote verbatim. When in
  doubt, ask before pushing.
- **The test is whether a value is somebody's, not whether the name is real.**
  Stock stand-ins are fine (`Telstra` as *a* carrier, `+15550100` as a number,
  `ACxxxx…` as an obviously-fake SID). What is banned is a *particular person's*
  data lifted from a device or a bug report.
- **The on-device debug log is the one sanctioned exception, and a narrow one.**
  Diagnosing "why didn't it ring" is a hard product requirement, so the log may
  carry **coarse call-flow state**: which step failed (push received, registration,
  token fetch, Telecom handoff, media), a dialed number's country calling code,
  and per-call outcome with one reason. The floor is absolute: never a full
  number, a contact's name, a credential, or a raw token. Above the floor the
  test is need, not category — log what a reader plausibly needs to explain a
  call outcome and no more. `docs/PRIVACY.md` must describe what the log carries
  before any sharing feature ships.

## Language and spelling

Use US English everywhere people read English: user-facing strings, commit subjects
and bodies, PR titles/descriptions, comments, KDoc, identifiers, docs (`SPEC.md`,
`TODO.md`, `docs/`), and this file.

The forms sibling repos keep getting wrong, so check them by name: **`gray`** (not
"grey") and **`canceled` / `canceling`** (one `l`). Others: `color`, `behavior`,
`dialog`, `-ize` over `-ise`, `license`, `center`, `labeled`, `traveling`.

Platform/third-party API spellings stay as the framework spells them —
`CancellationException` keeps its double `l`, and a framework symbol is never
rewritten to match this rule. This is about US-vs-UK spelling, not about adding
locales.

## Concise copy

Keep user-facing text short. A label, action, or title should carry only the words
the user needs — drop framing verbs and prefixes the surrounding UI already
implies. Prefer the shortest phrasing that stays unambiguous; when a longer form is
genuinely needed for clarity, say why in the PR.

## Translations

English first, translations in a second PR — never the same PR. Propose new English
copy in chat and get explicit approval before translating. New base strings land
with a per-string `tools:ignore="MissingTranslation"` and a `<!-- TODO: translate
-->` comment; the follow-up translation PR fans the approved copy out to every
locale and removes both. Escape apostrophes (`\'`) in any locale's string
resources.

## Remote build environments (Cursor Cloud and Claude Code on the web)

- **JDK 21** is pre-installed. **Android SDK** lives at `/opt/android-sdk`
  (`ANDROID_HOME`). On Claude Code on the web the SDK is *not* pre-installed; the
  `SessionStart` hook at `.claude/hooks/session-start.sh` provisions it
  (cmdline-tools, `platforms;android-36`, platform-tools, licenses) at session
  start. If `/opt/android-sdk` is empty mid-session, run
  `CLAUDE_CODE_REMOTE=true .claude/hooks/session-start.sh` rather than
  hand-installing.
- The Gradle wrapper auto-downloads Gradle on first run; AGP auto-installs the
  compileSdk minor platform on the first build.
- Key commands: `./gradlew assembleDebug` (build), `./gradlew test` (unit tests),
  `./gradlew lint` (lint), `./gradlew clean`.
- **No emulator practicality**: KVM is unavailable in the remote environments, and
  no emulator receives real FCM pushes to a Dozing device or has a real audio
  peer — call flows need a real device. Say so when reporting verification status.

## Testing expectations

- Code changes must include or update unit tests; product logic belongs in the pure
  domain layer where it is testable without Android. The call state machine —
  including the inbound races (push with no credentials, push during a cellular
  call, push after the caller hung up, stale token) — is pure logic and must be
  unit-tested exhaustively; these are exactly the parts that fail silently and
  lose a call.
- UI changes must include or update Robolectric + Roborazzi screenshot tests,
  wired into `.github/workflows/android-ci.yml`. The screenshot job records
  against an explicit `--tests` allow-list (one step per screenshot class) — add a
  `Run … screenshot tests` step alongside any new test class.
- The screenshot job auto-commits recording drift back to same-repo PR branches
  (`ci: refresh recorded screenshots`) and auto-posts a before/after diff comment
  on the PR. After CI runs on a UI-touching push, `git pull` before pushing again.
- Run `./gradlew test` and `./gradlew lint` before pushing when the environment
  can; otherwise say clearly what was verified by inspection only.
- **Fix any preexisting test failures as the *first* commit of the series.** If
  the failure is genuinely unrelated and out of scope, say so in the first
  response and confirm before skipping past it.
- **Don't paper over racy / flaky tests** with `Thread.sleep`, a retry loop, or a
  bumped timeout. Make the ordering explicit — a test dispatcher you advance,
  `runTest`, a gate you release from the test.
- **Don't disable a failing check** (a test, lint, a Roborazzi comparison) to make
  it pass — fix the underlying issue.
- **Verify the sandbox state before assuming it either way.** Spend ten seconds
  confirming before concluding the build can't run here: `command -v sdkmanager`,
  `ls /opt/android-sdk`, and a `curl -s -o /dev/null -w "%{http_code}"` at
  `https://maven.google.com/`. If you find it blocked when this file says it
  shouldn't be, flag it.

## Cost and reliability

- **Call out cost and reliability up front** when recommending new infrastructure
  or a new external call. This app already depends on paid, external services —
  Twilio (numbers, per-minute rates, the Voice SDK), Firebase (FCM), and a small
  backend for token minting and inbound routing — so every addition to that
  surface gets a rough dollar figure (free-tier vs. paid thresholds, $/month at
  expected traffic) and a reliability note: new failure modes, rate limits, added
  latency, extra points of failure, and what the user sees if the dependency is
  down. "What the user sees" is never nothing on this app — a down dependency is
  a call that didn't happen, and it must be surfaced, not silent. If the impact
  is effectively zero, say so rather than omitting the note.

## CI timing

- **Report significant CI timing regressions.** After CI finishes on a push,
  compare against recent runs of *the same job on the same kind of ref*. Only call
  out significant slowdowns (rule of thumb: >25% or >30s on a job under ~5min).
- **Compare like with like: PR against PR, `main` against `main`.** The `main` run
  does release work a PR run skips, so it is legitimately slower.
