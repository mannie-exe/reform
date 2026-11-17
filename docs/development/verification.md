# Verification

This document records actual project checks. Planned native and consumer qualification is not supplied by a bootstrap build.

## Owned storage slice

The storage slice passes the same full Gradle `spotlessApply check build --warning-mode all` command on Temurin 21.0.13 / Apple Silicon macOS. The final run took five seconds with relevant cached inputs: 81 core tests, zero failures/skips, eight packaging/tooling controls and the exact-artifact external consumer. No Minecraft game process or native settings callback is included.

New controls exercise pure/default/invalid portable bindings, read-without-initialization, explicit complete-file creation, exact-byte conflicts, foreign snapshots, strict UTF-8 and byte limits, case/symlink/non-file refusal, two simultaneous library initializers, a controlled noncooperating creator after the final baseline check, native POSIX mode preservation, explicit read-only refusal, no-op saves and cleanup. After 1,024 distinct completed paths the shared lock table retains zero keys. This is a lifetime observation, not a heap or throughput benchmark.

Fault controls retain intended saved evidence after checked, unchecked and exception-subtype failures; preserve a later external writer; and report unknown when post-replacement bytes are unreadable. Two initial failing controls showed the writer counting its own temporary file against directory admission and classifying an exception subtype after replacement as an unattempted conflict. Both pass after the correction. No test claims cross-process CAS for replacing an existing file.

Typed controls exercise the new schema envelope, explicit existing bare JSON, exact integer schema admission, malformed/unknown/unsupported data retention, original numeric spelling through envelope selection, unchanged-byte preservation, serialized-bound refusal before directory creation, store-bound read observations and real file receipt acknowledgement preserving subsequent edits. The independent Maven consumer now initializes, saves and reads a disposable real file using the packaged artifact, then verifies distinct saved/current draft values and removes its fixture.

| Artifact         | SHA-256                                                            |
| ---------------- | ------------------------------------------------------------------ |
| Combined mod JAR | `d8b1d5ea1254da55062621beaa0a27b11e117aa2ecdb7f36e5c72b26c2c8f1aa` |
| Source JAR       | `5f8592c5174325a46358d8635c6f7042d1e467013e2cfe52a685e6ab4e39eab8` |
| Maven POM        | `d664d2b954c669521ed927f64052cb26a55d2b9c940357e6abcf84e9b0e3244e` |

The storage API is synchronous worker-side code. Authorized asynchronous owners, actor/context checks, runtime publication, native TOML callbacks, registry collision admission and real migrations remain unimplemented. Linux/Windows filesystems, unsupported hard-link/atomic-move providers and hostile ancestor-directory replacement are not qualified by this local result. [Storage](storage.md) owns the actual guarantees and dependency-update checks; this increment is unpublished and Phase 1 remains incomplete.

## Draft/history slice

The experimental editing slice passes `./gradlew --no-daemon spotlessApply check build --warning-mode all` on the same pinned Java/native baseline. The final build took five seconds with relevant cached inputs: 59 core tests, eight artifact/release-tool controls and the exact-artifact independent public consumer pass. The native entry point remains unchanged; no client/server game run is included.

The new independent session model runs 120 generated sequences of up to 60 edit, undo/redo, reset/revert, delayed-save, refusal and reload operations. Its final seed is `-5699923791281119624`. Together with the previous numeric properties, four properties exercise 920 generated cases. A minimized failure `[0, 2, 14, 3]` at seed `-6603154812098735058` reproduced a no-op reset discarding redo because fresh row identities created a fake edit; the retained explicit regression and property pass after the correction.

Additional focused red/green controls reproduced an oversized normalized acknowledgement throwing during history rebasing, redo restoring the pre-observation saved value, and a valid compound conflict choice being refused by an intermediate tree budget. All now pass without weakening their controls. Observations are prepared before either journal/baseline changes; a budget refusal preserves the pending operation and draft instead of implying a persistence failure.

Other controls cover invalid input without owner construction, original number spelling, absent/null/empty distinctions, wrong scalar kind, blank unknown fields, stable diagnostics after row movement/edit/removal, immutable backing state, explicit history groups/imports, unknown-outcome retry prevention, reload/close invalidation, late invalid/reverted edits, manual/stale/whole-collection conflict review, history limits and owner-thread confinement. The public consumer resolves the exact combined JAR without game classes and exercises decode, incomplete drafts, undo, a delayed acknowledgement retaining later edits, manual review and stale reload refusal. These are pure-model claims; no native owner, permission check, file effect or screen behavior is implied.

| Artifact         | SHA-256                                                            |
| ---------------- | ------------------------------------------------------------------ |
| Combined mod JAR | `86ce3bbe6338d3a6fb69751184c0ce2d29658c812b6bc74dabc2df23dbbd773e` |
| Source JAR       | `a2242804e5d4c8486fa081d72303e0712c077e6e01652348244f9b11d1a4939e` |
| Maven POM        | `d664d2b954c669521ed927f64052cb26a55d2b9c940357e6abcf84e9b0e3244e` |

The current shapes still exclude built-in sets/maps/optionals/tagged alternatives and references. File/native owners, generated controls, commands, real consumer migrations and native/full-pack/cost qualification remain required by Phase 1. [Editing](editing.md) owns these implemented contracts; this is an unpublished development increment.

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

At that snapshot, drafts/history, file/native owners, native UI/commands, remaining recursive type families and all Compat/Skyweave migrations remained required. [Coverage](coverage.md) is the current capability index. The artifact is an unpublished development increment, not a Phase 1 release candidate.

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
