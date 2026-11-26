# Working rules for agents

These rules apply to every AI agent and human contributor working in this repository.

## Source of truth

- `specs/redesign.md` is the approved target design (v3). Read it before changing code.
- Positioning: small but complete; simple interfaces, robust internals; use the native platform.
- When code and a spec disagree, do not silently change either. Fix the code to match the spec, or update the spec in the same commit and explain why in the commit message.

## How to work

1. Work on one migration phase from `specs/redesign.md` section 9 at a time. Do not start the next phase in the same session unless asked.
2. Pushing directly to `main` is allowed (no pull requests required), under these conditions:
   - Before pushing, `git fetch` and rebase on the latest `origin/main`; never overwrite other contributors' changes.
   - The build and all tests must pass locally:
     ```
     cd android
     ./gradlew build
     ```
     (Until the wrapper exists: `gradle --no-daemon -PwithDemo=true :core:assembleDebug :ui:assembleDebug :appbaseTest:assembleDebug` and `bash tests/run.sh`.)
   - Never force-push `main`. Never rewrite published history.
   - If CI on `main` turns red after your push, fixing it is your next task.
3. Commit messages and the final report list each acceptance criterion of the phase and its actual result (passed / failed / not run). Never report a build or test as passing unless you ran it.
4. Update section 10 (implementation status) of `specs/redesign.md` in the same push, recording only verified results.

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
