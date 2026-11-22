# Architecture

## Current foundation

The repository separates a neutral core, shared game sources, game-version seams and loader bindings. Four product projects compile those sources against their own patched native classpaths. Loader entry points register only the library identity; they create no files, controls, menus, HUD or gameplay behavior. [Coverage](coverage.md) and [verification](verification.md) distinguish the released baseline and actual target qualification from build configuration.

| Source owner                           | Responsibility                                                      | Distribution                                      |
| -------------------------------------- | ------------------------------------------------------------------- | ------------------------------------------------- |
| `core`                                 | Independent definitions, editing, storage, ownership and operations | Java 21 classes included once in every product    |
| `minecraft-common`                     | Shared game-facing APIs and native command services                 | Compiled separately in each product's classpath   |
| `minecraft-1_21_1`, `minecraft-26_3`   | Actual game-version API differences                                 | Selected by the target matrix                     |
| `platform/neoforge`, `platform/fabric` | Loader identity, registration and native access                     | Selected by the loader                            |
| Product projects                       | Combined native artifacts, sources and publication metadata         | One installable JAR per selected game/loader pair |

`gradle/minecraft-targets.json` selects the four products and references dependency pins owned by `gradle/libs.versions.toml`. Each product source and fixture directory matches its project name. A shared game source directory is not a universal native binary.

[Native targets](platform-targets.md) owns explicit-version names and the procedure for adding or retiring supported combinations. Mapping/remapping capabilities and Java requirements are separate declarations.

The [core definitions](api.md), [editing model](editing.md), [storage backend](storage.md), [effective publication](publication.md) and [owner sessions](owners.md) keep their separate contracts. JSON and [registered native settings](native-settings.md) share editing while retaining their actual effect paths. [Presentation](presentation.md), [client factories](client-files.md), [workflows](client-workflows.md), [server settings](server-settings.md) and [world recipes](world-recipes.md) own their native bindings.

Products depend on core for compilation without declaring a second installed core artifact. Each game's DFU and Gson dependency metadata remains explicit; game libraries are not bundled or replaced. Fabric for Minecraft 1.21.1 remaps its game adapter, then combines the original core classes, preserving their byte identity across products. Unit/property-test dependencies remain test-only. Native presentation has no external runtime UI framework or expression engine.

## Required implementation boundaries

Separate stable document/field identities, definition/schema versions, editor sessions, owner contexts, saved/effective revisions and application effects. Filesystem locations and display labels are not identities or permission grants. Keep consumer paths/formats/defaults and native save/reload behavior intact during extraction.

Definitions/codecs describe complete values. Raw and invalid drafts, stable row identities and bounded history remain independent of domain constructors. Owners control authorization, persistence and publication. GUI and commands invoke those typed services; neither receives an unrestricted file writer.

Native/client types stay behind platform and physical-side admission. A dedicated server must load the library without resolving widgets or absent integrations. Optional compatibility belongs to its actual owner rather than becoming a core dependency.

Portable forms, file factories and native Controls owners use `design.inherent.reform.minecraft.client`. [Native client bindings](native-bindings.md) supplies the explicit loader services and source migration. Private key adapters preserve the loader's real capabilities; public form types expose neither FML nor Fabric command-source types. Native NeoForge configuration facilities keep their own namespace and actual owners.

Default file helpers will use explicit admitted roots and portable paths. Existing/native serializers retain their authority. No environment-variable override provider or ambient JVM-property settings layer is planned. Build flags and CI credentials remain operational inputs, not user-setting sources.

Consumers compile validated domain configuration into their runtime inputs. World generation must not traverse drafts, widgets or editor metadata. Profile saving, export, resource publication and new-world admission have separate outcomes. Terrain and village policy stay outside this library.

## Dependency updates

Update pins in `gradle/libs.versions.toml` and review the corresponding target matrix and artifacts. For game/loader changes, qualify sides, widgets/input/narration, native serializers/reload, registries/codecs and Brigadier against exact artifacts. A successful resolution or compilation is not evidence of those behaviors.

Review DFU numeric/partial-result behavior and JSON admission when changing codecs/parsers. Keep test-only tooling out of published runtime dependencies. Recheck packaging/POMs and compile an external public consumer after public API changes. API/ABI, consumer file schemas, wire versions and runtime context identities are separate compatibility obligations.

References: [NeoForge 1.21.1 configuration](https://docs.neoforged.net/docs/1.21.1/misc/config/), [ModDevGradle](https://github.com/neoforged/ModDevGradle), [Spotless](https://plugins.gradle.org/plugin/com.diffplug.spotless/8.11.0) and [Google Java Format](https://github.com/google/google-java-format).
