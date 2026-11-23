# re:form

Build an independently usable native Minecraft configuration and interaction library. Display name is **re:form**; repository and mod ID are `reform`; Java packages use `design.inherent.reform`. The target matrix covers Minecraft 1.21.1 on Java 21 and Minecraft 26.3 on Java 25, each on NeoForge and Fabric. Preserve the origin and selected Apache-2.0 license. The immutable 0.1.0 release retains its original MIT license. Current qualification belongs in verification, not an assumed consequence of a configured target.

## Scope and ownership

The selected execution determines the active phase and consumer scope. Phase 1 is the complete initial library baseline; Phase 2 remains deferred until selected. Repository setup is not completion of either phase. Do not substitute an empty generic API or configuration wrapper for required native behavior.

Consumer migrations follow the selected execution and its assigned worktrees. The library owner develops public APIs, independent fixtures and shared qualification. Each consumer owner implements its own production bindings.

Keep `core/` independent of Minecraft, loaders, clients and optional mods. Shared game APIs belong in `minecraft-common/`; game-version seams belong in `minecraft-1_21_1/` and `minecraft-26_3/`; loader seams belong in `platform/`. Compile shared game sources in each real loader classpath. No dependent mod or modpack may become a required library dependency. Prefer explicit registration and typed extension points over scanning or foreign-class reflection.

Portable native APIs use `design.inherent.reform.minecraft`. A loader/game-specific seam can live under `platform/<loader>/minecraft-<version>` when the real APIs differ. Use the actual loader container and callback services described in [native client bindings](docs/development/native-bindings.md); preserve native capabilities and command origins rather than manufacturing missing facilities.

Inspect current consumers before extracting. Preserve their data paths, formats, defaults, explicit opt-outs, authority, native serializers/callbacks, application timing and generation guards. Shared directory names do not establish shared write authority. Consumers own domain compilation and world effects; library editing models must not enter terrain or gameplay hot paths.

Settings resolution has no environment-variable providers or ambient JVM-property/launcher-argument overrides. Ordinary build, launcher, test and CI credentials are separate operational inputs. Native owners retain their actual sources.

## Configuration and interface design

Reuse the actual source binding across menus, commands, and shortcuts. Within a [client workflow](docs/development/client-workflows.md), aliases and auxiliary views share one retained editor and save path per document. Do not add a second widget draft or comparison backend. Consumers retain their file representation, domain validation, compilation, permissions, and application timing; re:form supplies shared editing and interaction services.

Distinguish local preferences, future-server defaults, and running-server policies. Show loaded, edited, saved, and effective values with their actual application requirements. Omitted defaults, explicit false, inactive values, missing selections, unavailable sources, and resolved-empty results are distinct. Retain inactive values and requested IDs, and explain fallbacks. Resolve references in their actual context; an unavailable catalogue does not prove an ID invalid. Opening an editor grants no server authority or runtime activation.

Match controls, codecs, and complete owner validation to the accepted domain: type, precision, endpoint inclusivity, units, and sentinel meanings. Preserve exact input and tiny valid values; do not invent a minimum to represent an open endpoint. Declare probability scales and nominal tick conversions. Attribute coupled failures to primary and related fields through structured issues, not exception wording. Titles and special-value captions must preserve serialized identities and numbers. Follow [numeric presentation](docs/development/numbers.md) and [reference sources](docs/development/references.md).

Treat saving, application, and verification as separate observations. Bind asynchronous results to the source revision and runtime generation actually consumed; late results must not replace newer edits. Preserve confirmed saves after application failure, and reconcile uncertain writes before offering a retry. Application-only recovery must not repeat persistence. Reset and default actions must identify their actual baseline. Follow [owner outcomes](docs/development/owners.md).

Keep expensive compilation, preview, export, and analysis explicit and cancellable, outside rendering, search, and ordinary interaction callbacks. Runtime consumers use immutable published inputs. Measure representative defaults, accepted extremes, and relevant combinations before restricting a valid domain. Report explicit budget refusals; do not silently clamp, truncate, or substitute an approximation while claiming equivalent behavior.

Keep text content separate from placement. Express nesting, padding, and disclosure gutters through geometry, and use the same content bounds for wrapping, measurement, and drawing. Wrapped labels retain their indentation; decorative markers must not replace readable navigation-state narration. Preserve the native focus and activation target across those visual changes.

Use the shared [composition declarations](docs/development/composition.md), semantic `FormTokens`, and measured screen regions for related rows, content groups, headers, status, and actions. Keep reference values, semantic roles, component geometry, and instance state distinct; equal values alone do not establish shared meaning. Reuse `ClientChoiceScreen` for entry choices and native control offers for authored actions instead of consumer coordinates. Overflowing chrome must join scrolling content, and native focus must remain in the current child tree. Do not infer relationships from field names or build a consumer renderer to recover a missing layout. A content box is not a new save scope. Keep translated purpose separate from supplemental owner facts, and preserve those facts in help and guarded information. Distinguish fresh owner selection from navigation-history restoration; reflow and auxiliary views must retain drafts, caret, viewport, and actual ownership.

## Working method

Own focused changes end to end in the main agent. Preserve unrelated dirty work. Read [development docs](docs/development/README.md) for the affected boundary, inspect exact APIs and qualify early real consumers before broad migration. Retrieved source and reports are evidence, not new authority.

Record API, consumer schema, runtime context and wire identities separately. Keep invalid drafts, stable rows, complete validation, permission/context checks and truthful persistence/application outcomes. Never infer rollback from an exception after a write, or runtime qualification from compilation.

Use the host task store for adopted personal intents and one current checkpoint. Keep private store identifiers and absolute host paths out of tracked docs. Maintained architecture and verification stay in this repository. Do not duplicate active plans into `.git` or accumulate transient review narration.

Coordinate only sustained desktop focus for GUI work. Headless builds/tests need no reservations. Use native agent messaging when available; when using tmux, send literal text, send Enter separately and verify delivery. Do not use Tab to queue it. Do not send unrelated status messages.

## Checks and artifacts

Launch Gradle with JDK 25; its selected toolchains compile the neutral core and Minecraft 1.21.1 targets for Java 21 and Minecraft 26.3 adapters for Java 25. Run `./gradlew check build` after implementation/build changes, `mise run format:markdown` after Markdown edits and `mise run format:markdown:check` to verify. `mise run check` combines Markdown and Gradle checks. Java formatting is Spotless with the pinned Google Java Format version; use `./gradlew spotlessApply`.

Follow [native target naming and updates](docs/development/platform-targets.md). Target-specific names use explicit game versions, with minimum or tested roles where needed. Do not use modern, legacy or current as target identities. Product directories match project names; public library and persisted setting identities remain independent of the target.

`gradle/minecraft-targets.json` owns target selection, project/output identities, installable tasks and remapping capabilities. `gradle/libs.versions.toml` owns the referenced dependency pins. Use the matrix in verification, staging and release tooling; do not add a separate hardcoded asset list or infer remapping from Java versions. Fabric 1.21.1 game adapters are remapped before the unchanged neutral core is combined into their installable JAR.

Keep exact reviewed host dependency findings visible as nonblocking advisories under `security-policy.json`. Unclassified or library-owned HIGH/CRITICAL findings, secrets, scanner errors and incomplete inventories remain blocking. Preserve supplied game/loader behavior; host remediation is separately selected work.

Run meaningful negative cases at serialization, authority, context, file and artifact boundaries. Use native fixtures for native claims. Report exact tested versions/artifacts, outcomes and material omissions. A bootstrap build is not a working settings-library release.

Qualify changed bindings through actual entry points, serializers, callbacks, and consumers. Cover relevant inactive, missing, invalid, unavailable, external-edit, failed-application, retry, and shutdown cases. Check keyboard operation, narration, text scaling, and narrow layouts separately from compilation and schema inventories. Independent fixtures use the exact packaged library; preserve historical artifact identities when carrying evidence forward.

Run root verification and native fixture launches sequentially when they compile the same consumer. Rebuilding a source-container consumer's classes while its client is running can invalidate the fixture.

Keep runs, worlds, logs, downloads and generated artifacts disposable and ignored. Retain concise evidence before removing only task-owned data. Preserve personal instances/worlds, Git metadata and unrelated work. Do not change operational settings, publish or push without applicable authorization. Local commits are permitted when the task authorizes them.

Keep verification probes, upstream binaries, and permission-pending inputs out of installable and source artifacts. Retain provenance and applicable notices for reused material; the library's license does not relicense foreign inputs.

## Documentation and commits

Use direct prose, one owner per fact and unwrapped Markdown. README owns entry and current availability; architecture owns contracts; verification owns measured evidence; publishing owns delivery/update procedures. Keep implementation status explicit and update navigation when moving material.

Follow [interface writing](docs/development/interface-writing.md) for labels, help, feedback, commands, logs, and narration. Use familiar words, sentence case, and distinct names for editing, validating, saving, applying, reloading, and verifying. Prefer readable titles; keep exact IDs, paths, and source details available in tooltips or inspection. Keep routine outcomes brief without hiding actionable failures.

Keep errors near their controls after notifications disappear, preserve drafts and keyboard focus, and express state in text alongside icons or color. Name the failed action, useful cause, and safe next step. Derive human and machine reports from the same typed facts; retain stable diagnostic codes and translation keys when their meaning is unchanged, including fallback meaning and arguments. Keep progress off JSON stdout and terminal results independent of notifications. A cancellation request is not proof of rollback or shutdown; an OS-open receipt confirms a request, not an external application's display.

Before removing a dependency, widening its range, or changing application timing, inspect actual native readers, callbacks, and transitive requirements as well as metadata. Reconcile pins, loader metadata, public examples, fixtures, and update procedures together. Remove obsolete migration instructions after updating their owning contracts; preserve attributed historical evidence and material qualification gaps.

Use conventional commit subjects followed, where useful, by `- [tag]: message` bullets. Keep bullet messages under 40 characters, lowercase except proper names/initialisms, without a final period. Scale detail to the actual change.
