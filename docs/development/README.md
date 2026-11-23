# Development

- [Architecture](architecture.md) owns modules and implementation boundaries.
- [Native targets](platform-targets.md) owns version-specific names, pins, remapping capabilities and target update procedures.
- [Native client bindings](native-bindings.md) owns loader registration, optional menus, command origins and the portable source migration.
- [Core API](api.md) owns implemented definition/decoding/snapshot contracts.
- [Editing](editing.md) owns raw drafts, history, submissions and conflict review.
- [Owned storage](storage.md) owns bounded file and typed JSON backend behavior.
- [Native settings](native-settings.md) owns registered serializer/callback execution and CLIENT/local-COMMON factory wiring.
- [Native keys](native-keys.md) owns admitted Controls bindings and actual Options persistence.
- [Publication](publication.md) owns effective handles and context/revision checks.
- [Owners](owners.md) owns asynchronous actor sessions, shared admission and outcome receipts.
- [Operations](operations.md) owns shared local GUI/command services, progress and cancellation.
- [Native search reporting](search-reporting.md) owns retained pages, result notices, recipient continuity and native reservation accounting.
- [Local import](imports.md) owns admitted sources/formats, bounded preview and reviewed draft replacement.
- [Manual conflicts](conflicts.md) owns qualified observations, explicit choices and shared GUI/command resolution.
- [Reference sources](references.md) owns admitted registry/tag/resource data, bounded lookup and reload freshness.
- [Numeric presentation](numbers.md) owns exact display/storage units and their validation boundary.
- [Dependent settings](dependencies.md) owns bounded local availability, retained values and cached input evidence.
- [Client files](client-files.md) owns physical-client registration, startup selection, initialization, entry and recovery defaults.
- [Document batches](batches.md) owns complete-vector preflight, cross-document validation and ordered owner submission.
- [Client workflows](client-workflows.md) owns composed native views and retained per-document editors.
- [Server settings](server-settings.md) owns actual-server lifecycle, policy/native binding and local native-source access.
- [World recipes](world-recipes.md) owns unsaved creation-context admission and domain compilation.
- [Presentation](presentation.md) owns measured panel/geometry/navigation boundaries.
- [Form composition](composition.md) owns automatic rows, authored relationships, content groups, and document actions.
- [Interface writing](interface-writing.md) owns built-in labels, help, outcomes and narration.
- [Coverage](coverage.md) indexes implementation and remaining qualification.
- [Verification](verification.md) owns current evidence and material gaps.
- [Publishing](../publishing.md) owns artifact/version/update procedures.

## Proposed design

[Presentation and navigation for 0.4](../design/presentation-0.4.md) defines the proposed theme, workspace, Home, navigation, and graphics contracts. [Construction and lifecycle](../design/presentation-0.4-api.md) specifies admission, API inputs/results, read-only preview capture, and native resource retirement. The [migration and acceptance plan](../design/presentation-0.4-migration.md) separates existing services from future APIs and consumer work. These specifications are not implemented features or an instruction to start candidate development. Current API and qualification documentation below remains authoritative for the released product.

Build with `./gradlew --no-daemon check build`. Gradle checks Java formatting, documentation links and distribution metadata as well as applicable tests. Pinned dprint and actionlint check Markdown and CI syntax; `mise run check` and CI combine these checks.

`core` uses Java 21 without game classes and tests both games' pinned DFU/Gson runtimes. The [target matrix](platform-targets.md) selects native game/loader projects, Java toolchains, mappings and artifacts. The version catalog owns dependency pins; `gradle.properties` owns the library version. Launch the checksum-pinned Gradle wrapper with JDK 25.

`verifyPublicConsumer` runs the separate [headless examples](../../examples/headless/README.md) against the staged Maven publication. It must retain no source-set/project or native-game dependency. The compound example combines nested roles, maps/lists, tagged references, a missing optional source, diagnostics, provenance and validate/compare/explain/export with actual bare-profile persistence. The combined JAR's core API is consumed directly; no second core runtime JAR is installed.

`verifyNativeConsumer` compiles the separate [native addon and opt-in client probe](../../examples/native/README.md) against the exact same packaged Maven artifact. It launches no game as part of ordinary `check`. The explicit disposable client runner separately exercises that library JAR in Minecraft; source-container loading of the example/probe remains distinct from the packaged dependency.

No additional annotation processor is required. ModDevGradle supplies the native development pipeline. Add a processor only for a concrete contract and keep generated output reproducible and free of optional/client loading on the wrong side.

## Native runs

Development tasks create task-owned fixtures under each target's `runs/` directory. `:<target>:runClient` needs desktop focus; `:<target>:runServer` is headless. Select an explicit project such as `neoforge-1_21_1`. Accept the Minecraft EULA only for the intended disposable server. Never point these tasks at a personal world or installed instance.

Headless checks and core tests do not qualify widgets, narration, native callbacks, reconnect behavior or consumer integration. Record those checks separately against the actual packaged artifact.
