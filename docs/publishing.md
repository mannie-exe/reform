# Publishing

## Identity and artifacts

Display name is **re:form**. Repository, mod ID and artifact are `reform`; Java packages use `design.inherent.reform`. The selected Maven coordinate is `design.inherent:reform:<version>`. No public Maven repository or stable release exists yet.

`gradle.properties` owns `mod_version`. Supported Minecraft, NeoForge and tool dependencies live in `gradle/libs.versions.toml`. Changes to build inputs require relevant qualification, not only a version bump.

`./gradlew check build` stages the combined mod JAR, sources and POM under `build/distributions/`. `./gradlew :neoforge:publishMavenJavaPublicationToStagingRepository` creates a local Maven repository under `build/repository/` for public-consumer checks. Neither command uploads anything. Do not install the separate development core JAR alongside the combined mod.

The combined JAR contains own core/platform classes once. POM metadata declares the Java dependencies without a second owned core artifact. Artifact timestamps/order are reproducible. The wrapper distribution is checksum-pinned; its scripts/JAR retain [Gradle's license](../LICENSE-GRADLE.txt).

## CI and release procedure

CI checks/builds on Linux, Windows and Intel macOS, checks Markdown separately, and retains staged artifacts. Apple Silicon and native gameplay/UI qualification remain separately recorded. Successful matrix builds are a release gate, not a promise of cross-platform rendering behavior.

An explicitly pushed `v<version>` tag can prepare a **draft** GitHub release after all jobs pass. The tag must equal the checked project version and name a non-snapshot semantic version. Release staging compares all runner artifacts byte for byte, adds a source archive and SHA-256/SHA-512 manifests, and refuses an existing release. No step publishes the draft or uploads to Modrinth, CurseForge or a public Maven repository.

Before an authorized release:

1. Complete the selected phase's implementation and qualification, including external packaged consumers and native checks.
2. Update the version, changelog and owning verification. Resolve license/provenance and distribution eligibility.
3. Commit the reviewed result, run the full checks, and push/tag only when authorized.
4. Inspect CI, the draft assets/checksums and exact source tag. Publish only with applicable release authority.

`python3 tools/release.py v<version> --stage-only` validates downloaded `build/ci/build-<runner>/` artifacts and stages a release without contacting GitHub. It still requires the matching local tag and source commit. Ordinary development builds use `-SNAPSHOT`; changing a build property is not an in-game settings override.

Keep CI tokens in the runner's credential mechanism. Runtime configuration does not use environment-variable overrides. Consumer releases, modpack pin updates, personal instances and world operations remain separately authorized tasks.
