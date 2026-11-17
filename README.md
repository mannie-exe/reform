# re:form

Shared native configuration editing and command services for Minecraft mods.

This repository is under development. The current foundation provides a Java 21 core module, NeoForge 1.21.1 bootstrap, reproducible packaging, formatting and build/release tooling. It does not yet provide a usable configuration API, settings screen or consumer migration. No stable release is available.

re:form is an independent library. Skyweave, Skyweave: Compatibility Patches and the Create: Skyweave modpack are intended consumers, not dependencies.

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

See [architecture](docs/development/architecture.md), [contributing](CONTRIBUTING.md), [verification](docs/development/verification.md) and [publishing](docs/publishing.md). [MIT](LICENSE) covers the project's code; [NOTICE](NOTICE) identifies bundled tooling and external dependencies.
