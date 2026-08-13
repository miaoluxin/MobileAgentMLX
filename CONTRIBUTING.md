# Contributing to MLX

Thanks for your interest! MLX is a fast-moving, feedback-driven project — every batch so far has been driven by real-device testing and adversarial audits.

## Getting started

1. Fork the repo and clone it.
2. Build the embedded Termux environment (once): `python3 scripts/build_termux_root.py`
3. Set up JDK 21 (the build **fails with JDK 26** — `JAVA_HOME` must point into the JDK 21 home directory) and the Android SDK (API 36).
4. Run the tests before you start: `./gradlew.bat :app:testDebugUnitTest` (241 tests, all green).

## Making changes

- **Match the existing style.** This codebase is disciplined: pure functions with unit tests, IO off the main thread, cache-aware prompts.
- **Cache discipline is a hard rule.** Static rules go into independent suffixes — never into BASE (it invalidates the global prefix cache). Dynamic content (dates, project names, window state) never enters the prefix. Tools/specs serialization must be sorted stably.
- **Prompts and tools must agree.** If you change a system prompt's claims, make sure the tools and engine actually deliver them — and vice versa.
- **Keep the counting contract.** Injected-message prefixes (`[长期目标]` / `[记忆回顾]` / `[技能注入]` / plan-feedback) must stay excluded from turn counts in all four places (engine, ChatScreen, rewindTo, backfillTurns).

## Testing & delivery

- New pure functions come with unit tests (`app/src/test/java/com/mlx/mobile/` mirrors the package structure).
- Run the full suite, then build: `./gradlew.bat :app:testDebugUnitTest` + `./gradlew.bat :app:assembleDebug`.
- Update `变更记录.md` with your batch entry (root cause, fix, test count).

## Pull requests

- One logical change per PR, with a clear description of the problem and the fix.
- Mention what you tested on-device (UI/interaction changes can't be fully covered by unit tests).

## Reporting bugs

Open an issue with: what you did, what you expected, what happened, and (if possible) the crash log from Settings → About → Crash logs. For security issues, see [SECURITY.md](SECURITY.md).
