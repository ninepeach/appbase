# Working rules for agents

These rules apply to every AI agent and human contributor working in this repository.

## Source of truth

- `specs/redesign.md` is the approved target design (v2). Read it before changing code.
- Positioning: small but complete; simple interfaces, robust internals; use the native platform.
- When code and a spec disagree, do not silently change either. Fix the code to match the spec, or propose a spec change in the PR description.

## How to work

1. Work on one migration phase from `specs/redesign.md` section 9 at a time, on its own branch (`phase-N-short-name`).
2. Before pushing, run locally and make sure all pass:
   ```
   cd android
   ./gradlew build
   ```
   (Until the wrapper exists, use `gradle --no-daemon -PwithDemo=true :core:assembleDebug :ui:assembleDebug :appbaseTest:assembleDebug` and `bash tests/run.sh`.)
3. Open a pull request. Do not push directly to `main`. Never force-push `main`.
4. In the PR description, list each acceptance criterion of the phase and its actual result (passed / failed / not run). Never report a test or build as passing unless you ran it.
5. Update section 10 (implementation status) of `specs/redesign.md` in the same PR, recording only verified results.
6. Before pushing, fetch and rebase on the latest `main`; never overwrite other contributors' changes.

## Design rules to keep

- Modules collaborate through typed Service interfaces; no global string-keyed registries.
- External coroutine cancellation always propagates. Expected failures (rejection, timeout, permission, remote errors) use `Outcome.Failure`. Programming and configuration errors throw. Do not wrap suspend calls in `runCatching`.
- Every bounded resource has an explicit, observable overflow behavior matching its semantics (see `specs/redesign.md` section 3, principle 6). Never silently drop accepted work.
- `UIManager` is the only public UI entry point and is main-thread confined; `Navigator` and `Presenter` are internal.
- The plugin mechanism stays explicit and small: no scanning, reflection, annotation processing, dynamic loading or DI containers.
- Interface grants are an architectural constraint, not a security sandbox.

## Style

- Kotlin official style; one statement per line; public APIs documented with KDoc.
- No `GlobalScope`, no `runBlocking` outside tests, no `!!`.
