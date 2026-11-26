# Minecraft and loader targets

Use explicit Minecraft versions for target-specific names. A target's identity remains the same when another game version is added. Relative names such as modern, legacy or current cannot identify a dependency, project, source family, configuration or task.

The library identity remains re:form, with mod ID `reform` and package root `design.inherent.reform`. Game and loader identities select its native products; they do not enter persisted document IDs, user settings keys or translated field identities.

## Names and ownership

| Concern                       | Convention                                       | Example                          |
| ----------------------------- | ------------------------------------------------ | -------------------------------- |
| Product project and directory | `<loader>-<minecraft with underscores>`          | `neoforge-1_21_1`                |
| Game API source directory     | `minecraft-<minecraft with underscores>`         | `minecraft-26_3`                 |
| Loader source directory       | `platform/<loader>`                              | `platform/fabric`                |
| Loader and game API seam      | `platform/<loader>/minecraft-<version>`          | `platform/fabric/minecraft-26_3` |
| Version-catalog pin           | `<dependency>-<minecraft with underscores>`      | `dfu-26_3`                       |
| Additional qualification pin  | Explicit version plus its role                   | `neoforge-1_21_1-tested`         |
| Codec runtime configuration   | Explicit game version                            | `minecraft26_3Runtime`           |
| Codec check                   | Explicit game version and scope                  | `testMinecraft26_3Codecs`        |
| Maven artifact ID             | `reform-<loader>-<minecraft>`                    | `reform-fabric-1.21.1`           |
| Installable filename          | `reform-<library version>-mc<game>-<loader>.jar` | `reform-0.2.0-mc26.3-fabric.jar` |

The target matrix in `gradle/minecraft-targets.json` owns target selection, project/output directories, native task selection, remapping and mapping namespaces. `gradle/libs.versions.toml` owns the referenced pins. Shared tool or loader pins can remain shared when that exact version supports every referring target. Give them version-specific pins when their supported inputs diverge.

The ordinary loader pin is the declared minimum. An additional tested pin selects another qualification input; its name is not evidence that a run passed. Verification records the actual tested versions and artifact identities. Keep game-owned DFU and Gson versions tied to the corresponding Minecraft manifest rather than upgrading them independently.

Optional viewer inputs distinguish the selected runtime (`jeiVersionRef`) from an explicitly lower API floor (`jeiMinimumVersionRef`) where declared. The build compiles against that floor and emits it in optional dependency metadata; ordinary native viewer runs retain the selected runtime, while an explicit API-floor run selects the lower original. Neither field alone proves native compatibility. Update the version-specific public API seam, artifact admission checks, and old/new runtime qualification together before widening admission. [Recipe links](recipe-links.md) owns the adapter behavior.

Remapping is an explicit target capability. The build does not infer it from Java versions or relative game age. Fabric 1.21.1 uses named development inputs and an intermediary installable artifact; Fabric 26.3 uses official unobfuscated names. Java requirements independently select compilation and runtime toolchains.

## Build pins and installed versions

Build and qualification inputs stay exact and reproducible. Installed dependency ranges should admit compatible patch and minor updates within the supported API family, rather than require the exact artifact used to build. Keep Minecraft targets explicit: a permissive loader range does not establish support for another game version. NeoForge ranges end at the next game line; Fabric's minimum loader/API predicates coexist with the exact game predicate. Qualify the declared minimum and selected newer inputs independently.

The target matrix also declares the FML ABI family: 4.x for NeoForge 1.21.1 and 12.x for NeoForge 26.3. This permits that family's minor/patch updates while refusing another major before library linking. Changing the family needs native lifecycle qualification; FML 12's deferred client setup worker is a concrete example of behavior that compilation alone misses. The selected NeoForge distribution supplies the actual FML build; it is not independently replaced or bundled by re:form.

Consumers should declare a minimum re:form version providing the APIs they use and an exclusive incompatible-family ceiling. Before 1.0, a minor release can change that family; for example, a consumer migrated to 0.2 can admit compatible 0.2 patch releases while excluding 0.3 until reviewed. Development trials still use unique exact coordinates and hashes. The 0.1-to-0.2 Java API migration is not made compatible by widening metadata.

Private native hooks need an adapter-specific decision. Prefer stable public contracts and compatible ranges where evidence supports them. A genuinely version-sensitive hook may require narrower admission before target classes load. If a feature can safely be unavailable, retain its user's selection and explain the installed version, supported contract and recovery. If losing the feature would alter authority, saved data or generation integrity, refuse before effects instead. Never relax saved-world identities or claim rollback to accommodate a dependency update.

For unexpected failures, retain the original cause and report the owner/operation, selected game and loader, library and affected dependency versions, and the safe recovery action. Recoverable failures need an inspectable diagnostic and useful logging; catastrophic linkage, required-Mixin or integrity failures need actionable fatal context. Do not catch fatal errors and continue with partly installed hooks or partly applied writes. Avoid repeated per-frame messages, source contents and secrets in diagnostics. Qualification records distinguish these requirements from the failure paths actually exercised.

## Add or update a target

1. Inspect the exact game manifest, loader/API releases and native APIs. Select a coherent Gradle/plugin/JDK combination and record the relevant library and mapping changes. A newer build plugin can require a newer wrapper even when the product's Java baseline is unchanged.
2. Add explicit version pins and target entries. An existing version-specific key must continue to identify that game version. Add a new key when the game changes. Update the admitted combination checks when deliberately extending support; do not bypass a missing-target refusal to obtain a green build.
3. Supply the required version and loader bindings. Share code where contracts match, while compiling it in every actual target classpath. If a game API source family can serve another version, declare and qualify that reuse explicitly. Public source compatibility, saved schemas and native runtime behavior remain separate obligations.
4. Review metadata, side/lifecycle admission, commands/permissions, inputs, rendering, serializers and resource/creation contexts. Preserve real owner paths, callbacks and authority. An unavailable native facility needs an honest portable equivalent rather than a fabricated implementation.
5. Run the relevant minimum and additional qualification inputs, both serialization runtimes, packaged artifact/refusal checks and independent consumers. Qualify changed native behavior with actual clients/servers. Preserve the distinction between compilation, headless execution, native rendering and consumer adoption.
6. Update the supported-target and dependency procedures, examples, CI/security inventory and release expectations from the same matrix. Freeze unique candidate coordinates/hashes before handing them to consumers. Published artifacts remain immutable.

Retiring a target is a deliberate compatibility decision requiring the selected scope's authorization and documentation. Do not turn a failing target into an optional or skipped check during an implementation that requires it.

[Architecture](architecture.md) owns source boundaries. [Verification](verification.md) owns observed results. [Publishing](../publishing.md) owns delivery, immutable coordinates and release authorization.
