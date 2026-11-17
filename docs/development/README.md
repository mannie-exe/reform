# Development

- [Architecture](architecture.md) owns modules and implementation boundaries.
- [Core API](api.md) owns implemented definition/decoding/snapshot contracts.
- [Editing](editing.md) owns raw drafts, history, submissions and conflict review.
- [Owned storage](storage.md) owns bounded file and typed JSON backend behavior.
- [Coverage](coverage.md) indexes implementation and remaining qualification.
- [Verification](verification.md) owns current evidence and material gaps.
- [Publishing](../publishing.md) owns artifact/version/update procedures.

Build with `./gradlew --no-daemon check build`. Gradle checks Java formatting, documentation links and distribution metadata as well as applicable tests. Pinned dprint and actionlint check Markdown and CI syntax; `mise run check` and CI combine these checks.

`core` uses Java 21 and the pinned DFU/Gson dependencies without game classes. `neoforge` builds against Minecraft 1.21.1 / NeoForge 21.1.265. The version catalog owns dependency pins; `gradle.properties` owns the project version. Gradle's wrapper pins the distribution and checksum.

`verifyPublicConsumer` runs the separate `examples/headless` build against the staged Maven publication. It must retain no source-set/project or native-game dependency. The combined JAR's core API is consumed directly; no second core runtime JAR is installed.

No additional annotation processor is needed for the bootstrap. ModDevGradle supplies the native development pipeline. Add a processor only for a concrete contract and keep generated output reproducible and free of optional/client loading on the wrong side.

## Native runs

The development tasks create task-owned fixtures under `neoforge/runs/`. `:neoforge:runClient` needs desktop focus; `:neoforge:runServer` is headless. Accept the Minecraft EULA only for the intended disposable server. Never point these tasks at a personal world or installed instance.

Headless checks and core tests do not qualify widgets, narration, native callbacks, reconnect behavior or consumer integration. Record those checks separately against the actual packaged artifact.
