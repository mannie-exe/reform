# re:form

Shared native configuration editing and command services for Minecraft mods.

This repository is under development. The current core supplies explicit/derived typed records, lists/sets/maps, explicitly encoded optional values and tagged alternatives, declared field defaults, strict decoding, raw edit sessions, bounded history, conflict review, owned JSON/properties storage, immutable startup-file selection, effective-value handles and actor-bound owner/editor sessions alongside the Java 21 / NeoForge 1.21.1 foundation. Shared local operation services, native Brigadier commands, recursive/reference forms, identified review/submission and operation feedback, [client file lifecycle](docs/development/client-files.md), [registered client settings](docs/development/native-settings.md#physical-client-registration) and [actual-server file/native bindings](docs/development/server-settings.md) are exercised by an [independent addon](examples/native/README.md). An unsaved world-recipe owner/factory also has headless and bounded native flat-world checks. Broader contexts, composition and Phase 1 qualification remain under development. No stable release is available.

Owner-admitted registry/tag/resource sources additionally provide bounded lookup and suggestions with distinct missing/unknown/empty states. Typed structural editing preserves actual editor and row identities; a [combined public example](examples/headless/README.md) exercises profile editing through shared services and real JSON persistence. Native recursive controls, reference pickers, units/ranges/sliders, import/manual conflict review, native keybindings and [catalog/detail navigation](docs/development/presentation.md#catalog-and-focused-detail) have development implementations. [Identified review](docs/development/operations.md#identified-source-and-value-review) supplies bounded source/baseline/draft/effective details and owner application notices through the public service API; its native inspection view has disposable client and command qualification. Broader composition, effect/recovery presentation and Phase 1 qualification remain incomplete. [Verification](docs/development/verification.md) identifies the exact artifacts exercised by each check. Consumer adoption proceeds separately. Skyweave, Skyweave: Compatibility Patches and the Create: Skyweave modpack are intended consumers, not dependencies.

## Development

Use a JDK 21 installation and Python 3.11 or newer. The checked-in Gradle wrapper and version catalog define the build. [mise](https://mise.jdx.dev/) supplies the pinned Markdown formatter and workflow checker.

```sh
mise install
mise run check
```

Without mise, run `./gradlew --no-daemon check build`, dprint 0.51.1 for `dprint check`, and actionlint 1.7.12 for `actionlint .github/workflows/build.yml`. Format Java with `./gradlew spotlessApply` and Markdown with `dprint fmt`.

The installable mod JAR, source JAR and Maven POM are staged in `build/distributions/`. The core JAR is a development/headless artifact; do not install it beside the combined mod. Disposable game runs use `./gradlew :neoforge:runClient` or `./gradlew :neoforge:runServer`. A first dedicated-server run requires the operator to accept Minecraft's EULA in that fixture.

## Project boundaries

The core stays independent of Minecraft and loaders. NeoForge owns platform and physical-side adapters. The intended library separates definitions, drafts, persistence, effective values and application effects; a saved file must not imply a running world has changed. Consumers retain their domain rules, paths, authority and generation identities. Settings use declared files or native owners, with no environment-variable override layer.

See [architecture](docs/development/architecture.md), [core API](docs/development/api.md), [coverage](docs/development/coverage.md), [contributing](CONTRIBUTING.md), [verification](docs/development/verification.md) and [publishing](docs/publishing.md). [MIT](LICENSE) covers the project's code; [NOTICE](NOTICE) identifies bundled tooling and external dependencies.
