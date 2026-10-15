# Versioning

* For each new feature, increase the minor version
* For each bugfix, increase the fix version

# Android Development Guidelines

## Project conventions

- Inspect the repository before proposing architecture or dependency changes.
- Preserve the project's current architecture (MVVM, MVI, or otherwise).
- Use Kotlin and the UI framework already adopted by the project.
- Respect the existing dependency version catalog and Gradle configuration.
- Never invent dependency versions or upgrade libraries without a reason.
- Prefer small, focused changes over large refactors.

## Skill routing

Before starting a task, check whether a relevant installed skill exists.

- Architecture, ViewModels, Flow: `android-kotlin-architecture`
- Compose implementation, state, performance: `compose-expert`
- Kotlin/Compose code review: `android-kotlin-compose`
- Test selection: `choosing-what-to-test`
- Coroutine tests: `testing-coroutines-with-runtest`
- Flow tests: `testing-flows-with-turbine`
- Compose UI test selectors: `finding-nodes-by-tag-text-content`
- Device crash investigation: `extracting-logs-with-logcat`

Load only the skills relevant to the current task. Do not load the entire catalog.

## Implementation process

1. Identify the relevant modules, existing patterns, and tests.
2. Outline an implementation plan for nontrivial changes.
3. Make the smallest change that solves the task.
4. Add or modify appropriate tests.
5. Run relevant verification commands.
6. Review the diff before finishing.

## Android quality standards

- Prefer structured concurrency and lifecycle-aware state collection.
- Keep business logic outside Composables.
- Handle loading, empty, success, and error UI states.
- Use string resources for user-facing Android UI text.
- Consider accessibility and edge-to-edge layout.
- Do not introduce unnecessary dependencies.

## Verification

Use the project's available tasks. Common examples:

- `./gradlew :app:assembleDebug`
- `./gradlew :app:lintDebug`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:connectedDebugAndroidTest` when a suitable device is available

Adapt module names, variants, and commands to the actual repository.

When verification fails, report the failure. Never claim unexecuted tests have passed.

## Completion report

Summarize:
- Changes made
- Skills applied
- Tests and checks run
- Remaining limitations or risks
