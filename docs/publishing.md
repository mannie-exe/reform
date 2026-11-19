# Publishing

## Identity and artifacts

Display name is **re:form**. Repository, mod ID and artifact are `reform`; Java packages use `design.inherent.reform`. The selected Maven coordinate is `design.inherent:reform:<version>`. No public Maven repository or stable release exists yet.

`gradle.properties` owns `mod_version`. Supported Minecraft, NeoForge and tool dependencies live in `gradle/libs.versions.toml`. Changes to build inputs require relevant qualification, not only a version bump.

DFU/serialization updates must retain exact coercion/partial-result controls, dynamic-map/tagged-envelope compression, nested record field paths and field-default/optional ordinary-null/compressed-slot behavior. Preserve explicit encoding choices, exact variant identities and refusals for ambiguous nested emptiness; test nullable fields against their actual omission policies. Requalify field-default metadata against each affected native codec and rerun the native registered owner when shared record serialization changes. Current [verification](development/verification.md) keeps those artifact scopes separate from generated-control and consumer-migration readiness.

Native registry/resource and executor updates also require the [reference-source controls](development/references.md): use the actual owner registry, preserve raw codec semantics and complete-source bounds, invalidate reload epochs and retain empty/missing/unknown distinctions. Do not infer full-pack source admission from the small native fixture or substitute resource enumeration into ordinary rendering/editing.

Server lifecycle/authority updates must inspect native stopping and exceptional stopped-event ordering, `CommandSourceStack` permission snapshots, current player/connection identity and actual SERVER config world roots. Rerun [server bindings](development/server-settings.md) alongside native serializer/callback controls. Preserve refused-before-operation and refused-before-persistence outcomes without inventing receipts, and distinguish a no-op save from a revision-changing edit when constructing stale-source tests. Dedicated console tests do not qualify live-player revocation, integrated GUI or remote administration.

Native control updates must review the pinned `AbstractContainerEventHandler` focus transitions, NeoForge `ScrollPanel` drag/release behavior and `AbstractSliderButton` input semantics. Requalify actual mouse-selected text focus, captured child dragging, scrollbar fallback, narrow related-field layout and initialization retry. Numeric display/slider metadata is separate from the owner's codec and file schema; retain exact text entry, explicit pointer quantization and the old constructor defaults when updating these APIs.

Registered-client updates must preserve the actual FML loading order and native registration reference. Treat same-file loaded-wrapper replacement as a backend observation change; retire actual unload/path changes and recheck backing-path authority at effects. Requalify startup corrections/file-watcher events separately from direct native Save callbacks, and observe restart caches at the actual callback boundary. A later native reload can legitimately change those caches. Keep CLIENT preferences, local COMMON defaults and server-authorized policies distinct.

Composed-view updates must retain the actual per-document actors, local drafts/history and backend receipts. Rerun the [native workflow](development/client-workflows.md) alongside affected standalone form/close/key suites. Distinguish background completion, pending exit refusal, explicit discard, hard source retirement and unexpected navigation. Rerun `--batch-only` for complete-vector preflight, typed cross-document checks, current authority, native source preparation, exact reviewed captures, clean-source checks and ordered receipts. A workflow-wide review is not cross-file atomicity, and an earlier save must not conceal a later owner refusal.

Editor/operation updates must preserve actual session/generation/version identities, stable row anchors, inactive drafts and typed structural-input admission. Requalify the combined public profile workflow and native read-only COMMON service before consumer migration; a successfully compiled new command input does not qualify its native widgets or serializer effects.

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

Future-default owner changes require both eager and deferred source checks: absent directories, explicit first creation, concurrent creators, invalid existing data, staged requirements and a handle that represents only saved defaults. Rerun `--defaults-only` alongside affected live/startup/import suites. Keep the native original recipe unchanged through defaults Save and import Preview/Stage; only explicit domain admission may replace its dimensions. Public source-catalog adapters must retain their actual source parser, bounds, authority, captured identity and separate command/GUI declarations.
