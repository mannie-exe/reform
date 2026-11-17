# Verification

This document records actual project checks. Planned native and consumer qualification is not supplied by a bootstrap build.

## Initial core definition slice

The experimental definition/decoding slice passes `./gradlew --no-daemon spotlessApply check build --warning-mode all` on Temurin 21.0.13 / Apple Silicon macOS. The final build took five seconds with relevant cached inputs. It includes 30 core tests, eight distribution/release-tool controls and the independent headless consumer. No native game run or GUI is included.

The three jqwik properties exercise 800 generated cases. The final run used `fractionNeverBecomesAnInteger`: `-5826370259910023940`, `integralDecimalSpellingRetainsValue`: `-615484575818841307`, `signedLongRoundTrip`: `4307583556652066820`. The native DFU controls reproduce integer truncation, overflow wrapping and error-with-partial behavior before the stricter guards.

Controls cover strict duplicate/encoding/trailing/depth/node/string/number admission, original source retention, negative zero, floating overflow/underflow, exact authored range checks, failed partial rejection, decoder tree isolation, nested defaults/snapshots, cycle/limit refusal, related-field diagnostics, invalid defaults/unknown/missing fields, unavailable validators and one encoder invocation per complete encode. A native-codec lifecycle regression was reproduced with a failing test, then fixed by retaining the original complete result; its regression passes. These tests do not qualify arbitrary consumer callbacks or native serializers.

`verifyPublicConsumer` builds a separate Java application using only the staged Maven publication, with an exact resolved-JAR SHA-256 check against the current candidate. It exercises valid/invalid/fraction/default paths and proves Minecraft/NeoForge classes are absent from that process's classpath. Both DFU and Gson are declared public compile dependencies because the API exposes their types. This is a headless API slice, not the required normal-mod/editor or real-consumer qualification.

| Artifact         | SHA-256                                                            |
| ---------------- | ------------------------------------------------------------------ |
| Combined mod JAR | `7ba35a7172df1e90a39a7874868e509479ff45ad49ac11323a3122a1bf2f122c` |
| Source JAR       | `b2cf208195328fca18d23f59ff80e27666ebabe32ab9efdce3e3cbc1e5ed1f70` |
| Maven POM        | `d664d2b954c669521ed927f64052cb26a55d2b9c940357e6abcf84e9b0e3244e` |

Drafts/history, file/native owners, native UI/commands, remaining recursive type families and all Compat/Skyweave migrations remain required. [Coverage](coverage.md) is the current capability index. The artifact is an unpublished development increment, not a Phase 1 release candidate.

## Bootstrap at 250c20f

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
