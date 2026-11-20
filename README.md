# re:form

Shared native configuration editing and command services for Minecraft mods.

re:form 0.1.0 targets Java 21, Minecraft 1.21.1 and NeoForge 21.1.265. It supplies typed recursive forms, shared settings commands, local profile authoring, owner-aware review and native creation-recipe editing. The same build was qualified with the Skyweave and Skyweave: Compatibility Patches candidates. [Coverage](docs/development/coverage.md) lists the supported workflows. [Verification](docs/development/verification.md) records the exercised artifacts and limits.

Start with the public [native addon](examples/native/README.md) for ordinary client and server settings or the [headless example](examples/headless/README.md) for neutral definitions, editing and storage. The mod ID is `reform`. The Maven coordinate is `design.inherent:reform:<version>`. Game-independent consumers use the same core API.

## Installation

Download the combined `reform-0.1.0-mc1.21.1-neoforge.jar` from [the release](https://github.com/mannie-exe/reform/releases/tag/v0.1.0) and place it in `mods/` on both installed sides when a dependent mod requires it. The game supplies DFU and Gson. The [consumer resolution](docs/publishing.md#consumer-resolution) guide shows the artifact-only Gradle repository and required NeoForge dependency.

## Development

Use a JDK 21 installation and Python 3.11 or newer. The checked-in Gradle wrapper and version catalog define the build. [mise](https://mise.jdx.dev/) supplies the pinned Markdown formatter and workflow checker.

```sh
mise install
mise run check
```

Without mise, run `./gradlew --no-daemon check build`, dprint 0.51.1 for `dprint check`, and actionlint 1.7.12 for `actionlint`. Format Java with `./gradlew spotlessApply` and Markdown with `dprint fmt`.

The installable mod JAR, source JAR and Maven POM are staged in `build/distributions/`. The core JAR is a development/headless artifact; do not install it beside the combined mod. Disposable game runs use `./gradlew :neoforge:runClient` or `./gradlew :neoforge:runServer`. A first dedicated-server run requires the operator to accept Minecraft's EULA in that fixture.

## Project boundaries

The core stays independent of Minecraft and loaders. NeoForge owns platform and physical-side adapters. The intended library separates definitions, drafts, persistence, effective values and application effects; a saved file must not imply a running world has changed. Consumers retain their domain rules, paths, authority and generation identities. Settings use declared files or native owners, with no environment-variable override layer.

See [architecture](docs/development/architecture.md), [core API](docs/development/api.md), [coverage](docs/development/coverage.md), [contributing](CONTRIBUTING.md), [verification](docs/development/verification.md) and [publishing](docs/publishing.md). [MIT](LICENSE) covers the project's code; [NOTICE](NOTICE) identifies bundled tooling and external dependencies.
