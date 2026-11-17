# Verification

This document records actual project checks. Planned native and consumer qualification is not supplied by a bootstrap build.

## Bootstrap

Verified the `0.1.0-SNAPSHOT` foundation on Apple Silicon macOS with Temurin 21.0.13, Gradle 9.2.1, Minecraft 1.21.1 and NeoForge 21.1.265.

| Check                                                                                                        | Observed result                                                                                                                                                                                 |
| ------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `./gradlew --no-daemon spotlessApply check build` using JDK 21                                               | Pass; initial build in 24 seconds                                                                                                                                                               |
| Clean `check build :neoforge:publishMavenJavaPublicationToStagingRepository --warning-mode all` using JDK 21 | Pass in 5 seconds, with no deprecation warning after fixing the copy task's execution-time project access                                                                                       |
| Artifact/release tooling                                                                                     | Eight controls pass: real distribution, foreign class/duplicate owned runtime refusal, identical staging/source/checksums, changed/missing artifacts, dirty source and snapshot release refusal |
| Clean rebuild comparison                                                                                     | Binary JAR, source JAR and POM match the first build byte for byte                                                                                                                              |
| Markdown, local links and Actions syntax                                                                     | dprint 0.51.1, ten documents/sixteen local links and actionlint 1.7.12 pass                                                                                                                     |
| Maven staging                                                                                                | Local publication succeeds; dependency metadata includes DFU/Gson without a second owned core runtime artifact                                                                                  |
| Wrapper                                                                                                      | Official Gradle 9.2.1 wrapper JAR matches SHA-256 `423cb469ccc0ecc31f0e4e1c309976198ccb734cdcbb7029d4bda0f18f57e8d9`                                                                            |

The installable JAR contains one Java 21 loader-entry class. Core has no implemented classes yet. Java/property-test engines are configured; their test tasks correctly report no source. The eight controls above test build/release tooling, not settings-library behavior.

Bootstrap artifact SHA-256:

| Artifact         | Digest                                                             |
| ---------------- | ------------------------------------------------------------------ |
| Combined mod JAR | `566714b06afaea3b6d3b6baa20bcdd68eb01e698e61ef4405f084e040105379e` |
| Source JAR       | `9a09eac2f926fab9b0286f2d2662b15f93cc583e6dcc4695065cfbbc5f8369f3` |
| Maven POM        | `1ce2b3b03e54a1efa8d51661e8ea585cdfa3c417e451376b4143d8eed602c095` |

No native client/server run, settings UI, public API consumer or Compat/Skyweave migration is qualified yet. GitHub's cross-platform workflow has not run from this local bootstrap; local reproducibility does not establish cross-runner equality. No tag or release is created by these checks.

## Required evidence

Retain the command, outcome, source revision or content identity, artifact hash, tested profile and material limits for a consequential claim. Keep large logs/runs private and disposable after extracting useful evidence. Re-run only checks relevant to changed or unresolved behavior.

`verifyDistribution` checks metadata, Java class version, duplicate/bundled content, source artifacts and POM ownership. It does not prove that NeoForge loads a candidate or that a runtime setting behaves correctly. Library-alone, consumer, dedicated-server, native UI and pack checks require their own evidence.
