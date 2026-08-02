# Twilmo

Twilmo makes and receives phone calls on a Twilio number from an Android phone —
reliably, with near-zero idle battery cost. Inbound calls wake the phone by
high-priority push (Twilio holds the call while the device comes up); outbound
calls go through the same Twilio Voice SDK stack. Between calls the app runs
nothing beyond an occasional registration renewal that keeps the number
reachable.

- **What it does and why**: [SPEC.md](SPEC.md)
- **Plan**: [TODO.md](TODO.md)
- **Engineering conventions**: [AGENTS.md](AGENTS.md)

## Building

The Android project scaffolding has not landed yet (see `TODO.md` Phase 0b).
Once it does:

```sh
./gradlew assembleDebug   # build debug APK
./gradlew test            # unit tests
./gradlew lint            # lint
```

Requires JDK 17+ and an Android SDK (`ANDROID_HOME`).
