# Development

- [Architecture](architecture.md) owns modules and implementation boundaries.
- [Core API](api.md) owns implemented definition/decoding/snapshot contracts.
- [Editing](editing.md) owns raw drafts, history, submissions and conflict review.
- [Owned storage](storage.md) owns bounded file and typed JSON backend behavior.
- [Native settings](native-settings.md) owns qualified serializer/callback constraints.
- [Publication](publication.md) owns effective handles and context/revision checks.
- [Owners](owners.md) owns asynchronous actor sessions, shared admission and outcome receipts.
- [Operations](operations.md) owns shared local GUI/command services, progress and cancellation.
- [Reference sources](references.md) owns admitted registry/tag/resource data, bounded lookup and reload freshness.
- [Client JSON](client-json.md) owns physical-client registration, initialization, entry and recovery defaults.
- [World recipes](world-recipes.md) owns unsaved creation-context admission and domain compilation.
- [Presentation](presentation.md) owns measured panel/geometry/navigation boundaries.
- [Coverage](coverage.md) indexes implementation and remaining qualification.
- [Verification](verification.md) owns current evidence and material gaps.
- [Publishing](../publishing.md) owns artifact/version/update procedures.

Build with `./gradlew --no-daemon check build`. Gradle checks Java formatting, documentation links and distribution metadata as well as applicable tests. Pinned dprint and actionlint check Markdown and CI syntax; `mise run check` and CI combine these checks.

`core` uses Java 21 and the pinned DFU/Gson dependencies without game classes. `neoforge` builds against Minecraft 1.21.1 / NeoForge 21.1.265. The version catalog owns dependency pins; `gradle.properties` owns the project version. Gradle's wrapper pins the distribution and checksum.

`verifyPublicConsumer` runs the separate [headless examples](../../examples/headless/README.md) against the staged Maven publication. It must retain no source-set/project or native-game dependency. The compound example combines nested roles, maps/lists, tagged references, a missing optional source, diagnostics, provenance and validate/compare/explain/export with actual bare-profile persistence. The combined JAR's core API is consumed directly; no second core runtime JAR is installed.

`verifyNativeConsumer` compiles the separate [native addon and opt-in client probe](../../examples/native/README.md) against the exact same packaged Maven artifact. It launches no game as part of ordinary `check`. The explicit disposable client runner separately exercises that library JAR in Minecraft; source-container loading of the example/probe remains distinct from the packaged dependency.

No additional annotation processor is needed for the bootstrap. ModDevGradle supplies the native development pipeline. Add a processor only for a concrete contract and keep generated output reproducible and free of optional/client loading on the wrong side.

## Native runs

The development tasks create task-owned fixtures under `neoforge/runs/`. `:neoforge:runClient` needs desktop focus; `:neoforge:runServer` is headless. Accept the Minecraft EULA only for the intended disposable server. Never point these tasks at a personal world or installed instance.

Headless checks and core tests do not qualify widgets, narration, native callbacks, reconnect behavior or consumer integration. Record those checks separately against the actual packaged artifact.
