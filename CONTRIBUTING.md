# Contributing to Setu

Thanks for helping. Start with `docs/ARCHITECTURE.md` and `docs/PROTOCOL.md`.

## Build

```sh
./gradlew :protocol:test :routing:test :sim:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
cd relay && npm ci && npm run typecheck && npm test
cd map && npm ci && npm test
```

JDK 17 and an Android SDK with platform 37 are required for the app.

## Rules

- Keep `protocol` and `routing` free of Android imports.
- Any wire format change updates `docs/PROTOCOL.md` and `docs/vectors/` in the same PR.
- Every byte and radio wake-up costs battery; justify both.
- Conventional commit messages (`feat:`, `fix:`, `docs:`).
- Follow the [Code of Conduct](CODE_OF_CONDUCT.md). Report security issues privately ([SECURITY.md](SECURITY.md)).
