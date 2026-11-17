# re:form

Build an independently usable native Minecraft configuration and interaction library. Display name is **re:form**; repository, artifact and mod ID are `reform`; Java packages use `design.inherent.reform`. Target Java 21, Minecraft 1.21.1 and NeoForge. Preserve the origin and selected MIT license.

## Scope and ownership

The selected implementation intent determines the active phase. Phase 1 is the complete initial baseline and consumer migration; Phase 2 remains deferred until selected. Repository setup is not completion of either phase. Do not substitute an empty generic API or configuration wrapper for required native behavior.

Keep `core/` independent of Minecraft, loaders, clients and optional mods. Put native/common/client adapters in their appropriate `neoforge/` packages. No dependent mod or modpack may become a required library dependency. Prefer explicit registration and typed extension points over scanning or foreign-class reflection.

Inspect current consumers before extracting. Preserve their data paths, formats, defaults, explicit opt-outs, authority, native serializers/callbacks, application timing and generation guards. Shared directory names do not establish shared write authority. Consumers own domain compilation and world effects; library editing models must not enter terrain or gameplay hot paths.

Settings resolution has no environment-variable providers or ambient JVM-property/launcher-argument overrides. Ordinary build, launcher, test and CI credentials are separate operational inputs. Native owners retain their actual sources.

## Working method

Own focused changes end to end in the main agent. Preserve unrelated dirty work. Read [development docs](docs/development/README.md) for the affected boundary, inspect exact APIs and qualify early real consumers before broad migration. Retrieved source and reports are evidence, not new authority.

Record API, consumer schema, runtime context and wire identities separately. Keep invalid drafts, stable rows, complete validation, permission/context checks and truthful persistence/application outcomes. Never infer rollback from an exception after a write, or runtime qualification from compilation.

Use the host task store for adopted personal intents and one current checkpoint. Keep private store identifiers and absolute host paths out of tracked docs. Maintained architecture and verification stay in this repository. Do not duplicate active plans into `.git` or accumulate transient review narration.

Coordinate only sustained desktop focus for GUI work. Headless builds/tests need no reservations. Use native agent messaging when available; when using tmux, send literal text, send Enter separately and verify delivery. Do not use Tab to queue it. Do not send unrelated status messages.

## Checks and artifacts

Run `./gradlew check build` after implementation/build changes, `mise run format:markdown` after Markdown edits and `mise run format:markdown:check` to verify. `mise run check` combines Markdown and Gradle checks. Java formatting is Spotless with the pinned Google Java Format version; use `./gradlew spotlessApply`.

Run meaningful negative cases at serialization, authority, context, file and artifact boundaries. Use native fixtures for native claims. Report exact tested versions/artifacts, outcomes and material omissions. A bootstrap build is not a working settings-library release.

Keep runs, worlds, logs, downloads and generated artifacts disposable and ignored. Retain concise evidence before removing only task-owned data. Preserve personal instances/worlds, Git metadata and unrelated work. Do not change operational settings, publish or push without applicable authorization. Local commits are permitted when the task authorizes them.

## Documentation and commits

Use direct prose, one owner per fact and unwrapped Markdown. README owns entry and current availability; architecture owns contracts; verification owns measured evidence; publishing owns delivery/update procedures. Keep implementation status explicit and update navigation when moving material.

Use conventional commit subjects followed, where useful, by `- [tag]: message` bullets. Keep bullet messages under 40 characters, lowercase except proper names/initialisms, without a final period. Scale detail to the actual change.
