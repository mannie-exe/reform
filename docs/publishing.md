# Publishing

## Identity and artifacts

Display name is **re:form**. Repository, mod ID and Maven artifact are `reform`; Java packages use `design.inherent.reform`. `gradle.properties` owns the project version. `gradle/libs.versions.toml` owns the Minecraft, NeoForge and Java dependency pins.

`./gradlew check build` checks the product and stages three files under `build/distributions/`.

- `reform-<version>-mc1.21.1-neoforge.jar` contains the library core and NeoForge adapter once.
- `reform-<version>-sources.jar` contains their matching sources.
- `reform-<version>.pom` declares the public DFU and Gson dependencies.

The separate development core JAR is for headless development. Minecraft supplies its runtime serialization libraries. The Gradle wrapper is checksum-pinned and retains [Gradle's license](../LICENSE-GRADLE.txt). Archive timestamps and entry order are reproducible.

## Consumer resolution

The release assets supply an artifact-only Ivy repository for Gradle. Use an exact qualified version and install the same combined JAR with the dependent mod. NeoForge metadata must declare `reform` as a required dependency on both applicable installed sides.

```groovy
repositories {
    exclusiveContent {
        forRepository {
            ivy {
                url = uri('https://github.com/mannie-exe/reform/releases/download')
                patternLayout { artifact 'v[revision]/[artifact]-[revision]-mc1.21.1-neoforge.[ext]' }
                metadataSources { artifact() }
            }
        }
        filter { includeModule 'design.inherent', 'reform' }
    }
}
dependencies {
    implementation('design.inherent:reform:0.1.0') { transitive = false }
}
```

Compare the resolved JAR against the release checksum. Native mods can use a nontransitive dependency because Minecraft supplies DFU and Gson. The artifact-only repository does not read the supplied POM. Headless consumers must also declare `com.mojang:datafixerupper:8.0.16` and `com.google.code.gson:gson:2.10.1`, admitting `com.mojang` from `https://libraries.minecraft.net/` and other dependencies from Maven Central. The [headless example](../examples/headless/README.md) exercises the same dependencies through its staged Maven repository.

For an unpublished trial, `./gradlew :neoforge:publishMavenJavaPublicationToStagingRepository` creates a local Maven repository under `build/repository/`. Use a unique development coordinate for each frozen candidate, retain its exact bytes and verify the consumer's resolved hash. Keep machine-local repositories out of release builds. Publish re:form before releasing a consumer that requires it.

## CI and release procedure

The build workflow checks Linux, Windows and Intel macOS with Java 21, plus Markdown and workflow syntax. Its reusable Trivy workflow is a release dependency. Security scanning also runs weekly and on explicit dispatch. Every action is pinned to a commit. Pull requests use read-only Gradle caches, and only the release job receives write access to repository contents.

The POM producer uses UTF-8 and LF endings on every platform. The tag job downloads the three runners' distribution artifacts and compares all three files byte for byte. A mismatch, missing artifact, dirty source, incorrect version/tag, or existing staging directory stops preparation. `tools/release.py` adds the tagged source ZIP and SHA-256/SHA-512 manifests, then creates a draft GitHub release. Repeated creation cannot overwrite a published release.

1. Finish relevant implementation and qualification, including native and consumer checks for affected boundaries.
2. Update `mod_version`, [changelog](../CHANGELOG.md), [verification](development/verification.md) and supported-dependency guidance. Review licensing and packaged contents.
3. Commit the reviewed changes. Run `mise run check` and `mise run check:security`, then push the authorized source and inspect its hosted checks.
4. Create and push `v<version>` on the qualified commit. The tag must match `gradle.properties`. Inspect the tag workflow and draft assets, verify their hashes and compare the downloaded JAR with the qualified candidate.
5. Publish the inspected draft under the release authorization. Verify the public assets and a consumer resolution from the immutable release path.

`python3 tools/release.py v<version> --stage-only` performs local staging from CI downloads under `build/ci/build-<runner>/`. It still requires a matching source tag and clean checkout. Keep downloaded runner artifacts and release staging in ignored build directories until verification is recorded. Repository publication and consumer/modpack rollout have separate scopes.

## Dependency and adapter maintenance

Renovate proposes version-catalog, Gradle wrapper, mise tool and GitHub Actions updates on a weekly schedule. Merges are manual. Its custom manager also tracks the explicit Trivy version in the workflow. The native version pin and consumer data formats remain deliberate release choices.

| Changed boundary                     | Required review and affected checks                                                                                                                                                                                                       |
| ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| DFU, Gson and typed JSON             | Exact coercion, partial results, compressed records, null/omitted defaults, nested diagnostics and public headless/native consumers. [API](development/api.md), [editing](development/editing.md).                                        |
| NeoForge/FML and registered owners   | Actual registration, backing path, native serializer and save/watcher callbacks, restart caches, unload and current side/context. [Native settings](development/native-settings.md), [server settings](development/server-settings.md).   |
| Native controls and client lifecycle | Focus, text selection, drag/release, input modifiers, narrow reflow, initialization retry and retired callbacks. [Presentation](development/presentation.md), [native keys](development/native-keys.md).                                  |
| Multi-document workflows             | Retained per-owner drafts, scoped dependencies, exact reviewed captures, conditional preflight and truthful ordered outcomes. [Workflows](development/client-workflows.md), [batches](development/batches.md).                            |
| Registry/tag/resource providers      | Real owner registry, complete-source bounds, codec semantics and reload identity, without IO in ordinary rendering. [References](development/references.md).                                                                              |
| Commands and search                  | Current permission/recipient, exact operation identity, stable pages and owned native ticket accounting across cancellation and disconnect. [Operations](development/operations.md), [search reporting](development/search-reporting.md). |
| Storage, defaults and recipes        | Invalid existing files, concurrent creation, no-op bytes, saved/effective distinction, original recipe identity and explicit preview/admission. [Storage](development/storage.md), [world recipes](development/world-recipes.md).         |

Renovate keeps CI Java major changes explicit and leaves the Minecraft-owned DFU version under manual platform review. Mojang does not publish a Maven version index for that dependency. The formatter version in the catalog has an explicit Maven extraction rule; review its updates together with Spotless. Other proposed updates still require manual merge and the affected checks above.

`tools/security.init.gradle` resolves the core's actual runtime and test classpaths into a disposable Gradle lock under `build/security/`. `--write-locks` also emits an empty settings-catalog lock, which is ignored as a scan artifact. The regular build keeps its declared dependency resolution. CI generates the inventory in the checked Linux build and passes that exact artifact to the security workflow, avoiding another cold dependency download. Scheduled and manual security runs resolve their own inventory. Trivy scans this inventory for vulnerabilities and the source tree for secrets, skipping generated runs and caches. [Security reporting](../SECURITY.md) owns the exact, expiring dependency exception.

Keep credentials in the host or CI credential mechanism. In-game settings continue to use their declared files and native owners.
