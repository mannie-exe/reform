# Architecture

## Current foundation

The repository has two source modules and one installable NeoForge mod artifact. The bootstrap entry point registers only the mod identity; it creates no files, controls, menus, HUD or gameplay behavior. Configuration contracts and consumer migrations remain implementation work.

| Module     | Responsibility                                                              | Distribution                                                           |
| ---------- | --------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| `core`     | Minecraft-independent types, definitions, editing, ownership and operations | Development/headless JAR; own classes also enter the combined mod once |
| `neoforge` | Minecraft/NeoForge lifecycle, native backends, commands and client adapters | Combined installable JAR, sources and public Maven metadata            |

The platform depends on core for compilation but does not declare a second owned core runtime artifact. DFU and Gson dependency metadata remains explicit. Minecraft-provided libraries are not bundled or replaced. Unit/property-test dependencies stay test-only. There is no runtime UI framework, expression engine or optional mod dependency in the bootstrap.

## Required implementation boundaries

Separate stable document/field identities, definition/schema versions, editor sessions, owner contexts, saved/effective revisions and application effects. Filesystem locations and display labels are not identities or permission grants. Keep consumer paths/formats/defaults and native save/reload behavior intact during extraction.

Definitions/codecs describe complete values. Raw and invalid drafts, stable row identities and bounded history remain independent of domain constructors. Owners control authorization, persistence and publication. GUI and commands invoke those typed services; neither receives an unrestricted file writer.

Native/client types stay behind platform and physical-side admission. A dedicated server must load the library without resolving widgets or absent integrations. Optional compatibility belongs to its actual owner rather than becoming a core dependency.

Default file helpers will use explicit admitted roots and portable paths. Existing/native serializers retain their authority. No environment-variable override provider or ambient JVM-property settings layer is planned. Build flags and CI credentials remain operational inputs, not user-setting sources.

Consumers compile validated domain configuration into their runtime inputs. World generation must not traverse drafts, widgets or editor metadata. Profile saving, export, resource publication and new-world admission have separate outcomes. Terrain and village policy stay outside this library.

## Dependency updates

Update pins in `gradle/libs.versions.toml` and regenerate/review affected artifacts. For Minecraft/NeoForge changes, qualify class-loading sides, widgets/input/narration, native serializers/reload, registries/codecs and Brigadier against exact artifacts. A successful Gradle resolution is not evidence of those behaviors.

Review DFU numeric/partial-result behavior and JSON admission when changing codecs/parsers. Keep test-only tooling out of published runtime dependencies. Recheck packaging/POMs and compile an external public consumer after public API changes. API/ABI, consumer file schemas, wire versions and runtime context identities are separate compatibility obligations.

References: [NeoForge 1.21.1 configuration](https://docs.neoforged.net/docs/1.21.1/misc/config/), [ModDevGradle](https://github.com/neoforged/ModDevGradle), [Spotless](https://plugins.gradle.org/plugin/com.diffplug.spotless/8.11.0) and [Google Java Format](https://github.com/google/google-java-format).
