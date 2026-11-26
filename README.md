# re:form

Shared native configuration editing and command services for Minecraft mods.

re:form 0.3.0 supports Minecraft 1.21.1 on Java 21 and Minecraft 26.3 on Java 25, each on NeoForge and Fabric. It supplies responsive settings workspaces, typed recursive forms, shared commands, local profile authoring, owner-aware review and native creation-recipe editing. [Coverage](docs/development/coverage.md) lists the supported workflows. [Verification](docs/development/verification.md) records the exercised artifacts and limits.

Start with the [native addon](examples/native/README.md) for client/server bindings or the [headless example](examples/headless/README.md) for neutral definitions, editing and storage. Use the matching release tag for released examples. The mod ID is `reform`; target-specific Maven modules use `design.inherent:reform-<loader>-<game>`, as described in [native targets](docs/development/platform-targets.md). Game-independent consumers use the same core API. The immutable 0.1.0 release retains its original module and API.

## Installation

Download the combined `reform-0.3.0-mc<game>-<loader>.jar` for your exact game and loader from [the release](https://github.com/mannie-exe/reform/releases/tag/v0.3.0), then place it in `mods/` on both installed sides when a dependent mod requires it. For example, NeoForge 1.21.1 uses `reform-0.3.0-mc1.21.1-neoforge.jar`. The game supplies DFU and Gson. The [consumer resolution](docs/publishing.md#consumer-resolution) guide shows the artifact-only Gradle repository and dependency declaration. Install one target's combined JAR, not multiple variants.

## Development

The development branch is preparing 0.4.0 with scoped components, canonical field sections, searchable inline selectors, completion, and typed draft or audition previews. It supports framework-owned workspaces and caller-owned mounts; each capability is optional to adopt. Trials require the exact immutable candidate and hashes from their handoff. Use the 0.3.0 release tag for currently released examples. Implemented contracts live in [presentation](docs/development/presentation.md), [semantic components](docs/development/components.md), and [preview preparation](docs/development/previews.md). [Verification](docs/development/verification.md) records current qualification and limitations. The broader [semantic design](docs/design/semantic-ui/README.md) includes unfinished and deferred work; it is not a list of completed release features. Public 0.4.0 delivery remains pending its release checks.

Launch Gradle with JDK 25 and have JDK 21 available for the selected toolchains. Python 3.11 or newer runs repository tools. The wrapper, target matrix and version catalog define the build. [mise](https://mise.jdx.dev/) supplies the pinned Markdown formatter and workflow checker.

```sh
mise install
mise run check
```

Without mise, run `./gradlew --no-daemon check build`, dprint 0.51.1 for `dprint check`, and actionlint 1.7.12 for `actionlint`. Format Java with `./gradlew spotlessApply` and Markdown with `dprint fmt`.

The installable mod JAR, source JAR and Maven POM for each target are staged in `build/distributions/`. The core JAR is a development/headless artifact; do not install it beside a combined mod. Disposable game runs use `./gradlew :<target>:runClient` or `./gradlew :<target>:runServer`, for example `:neoforge-1_21_1:runClient`. A first dedicated-server run requires the operator to accept Minecraft's EULA in that fixture.

## Project boundaries

The core stays independent of Minecraft and loaders. Native adapters bind the selected game, loader and physical side. The library separates definitions, drafts, persistence, effective values and application effects; a saved file must not imply a running world has changed. Consumers retain their domain rules, paths, authority and generation identities. Settings use declared files or native owners, with no environment-variable override layer.

See [architecture](docs/development/architecture.md), [core API](docs/development/api.md), [coverage](docs/development/coverage.md), [contributing](CONTRIBUTING.md), [verification](docs/development/verification.md) and [publishing](docs/publishing.md). re:form is an inherent.design project by inherent.design contributors. [Apache-2.0](LICENSE) covers current source and future releases; published 0.1.0 retains MIT. [NOTICE](NOTICE) identifies bundled tooling and external dependencies.
