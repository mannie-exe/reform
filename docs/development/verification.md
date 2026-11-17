# Verification

This document records actual project checks. Planned native and consumer qualification is not supplied by a bootstrap build.

## Serialized editing actor

The full Gradle `spotlessApply check build --warning-mode all` passes with 143 core tests, zero failures/errors/skips, 920 generated cases, ten tooling controls and the exact-artifact headless public consumer. The final check took five seconds with relevant cached inputs. No Minecraft client/server or native form is run by this increment.

Nine new controls exercise worker-owned invalid typing, grouped history/defaults/revert/save, late typing while actual file IO is blocked, duplicate pending-save refusal, retained receipt after editor closure, explicit dirty-reload conflict choices, malformed-load diagnostics with retained draft, rejection of an old read after new typing, browse-without-initialization and removable lifecycle subscriptions. One control deliberately holds and releases the two physical workers in opposite order, proving that the same mutable editing session moves between distinct worker IDs under its serialized task verifier. Attribution uses cached validation results so the observer does not rerun the measured validator on its own thread. Another creates and closes 1,024 editor instances in the same live context without exhausting its active hook bound; that is not a heap/GC benchmark.

The actual blocked-write control uses one worker for the file effect while the other accepts and validates a later edit. Its acknowledgement retains the later draft while disk/runtime retain the submitted value. Closing the editor after replacement clears its published view but preserves the `SAVED` fact in the returned receipt. The public Maven consumer now additionally loads, edits and saves through `EditorActor` using only the packaged artifact.

| Artifact         | SHA-256                                                            |
| ---------------- | ------------------------------------------------------------------ |
| Combined mod JAR | `b9c1d8ba4da919ff3aad82f3811591049efa577b17125f948703f24420678f0f` |
| Source JAR       | `8bb713c947b44bf5fd9f1d038b5c34f76f42e49f87c2aabbf96838fa067e13fd` |
| Maven POM        | `d664d2b954c669521ed927f64052cb26a55d2b9c940357e6abcf84e9b0e3244e` |

[Editing](editing.md) owns the pure model and [owners](owners.md) owns the connected actor contract. Native presentation, actor/lifecycle gateways, registered/native backends, full operations/cancellation, richer types, convenience factories, consumer migrations and native/full-pack/cost qualification remain required. This headless actor qualification is not completion of the Phase 1 UI or a release.

## Worker-prepared publication

The full Gradle `spotlessApply check build --warning-mode all` passes with 134 core tests, zero failures/errors/skips, 920 generated cases, ten tooling controls and the exact-artifact headless consumer. The final check took five seconds with relevant cached inputs. No new Minecraft game process or native actor gateway is included.

The connected-owner callback witness reproduced sixteen pure validator/snapshot/equality callbacks on the native executor before the change. It now observes zero there across initialization, semantic no-op and failed reload. Pure preparation uses the actual separate Java worker; the native side captures and checks current authority/context/revision/observation before the prepared-state swap. This is executor-placement evidence, not an FPS, latency, heap or throughput benchmark.

Four latch-controlled asynchronous publication cases exercise permission loss, context closure and competing publication during worker preparation. The fourth first failed because an older worker result erased a newer failed observation with an unchanged effective revision; it now refuses `owner.observation_changed` and preserves both the earlier effective value and newer error report. Another connected-owner case closes the context during authorized readback after publication and retains the historical `SAVED`/`APPLIED` facts while omitting the unavailable observation. The earlier synchronous publication controls remain passing, including repeat validation and reentrant callbacks.

| Artifact         | SHA-256                                                            |
| ---------------- | ------------------------------------------------------------------ |
| Combined mod JAR | `8424848aaa8530d6ca08058734eeb5da81f27c08d206da36a3561cb2cd8329ca` |
| Source JAR       | `abcdf620b8d32f13711e590c4a65b7b53f8a87fd815fe826201c3428cc684177` |
| Maven POM        | `d664d2b954c669521ed927f64052cb26a55d2b9c940357e6abcf84e9b0e3244e` |

[Publication](publication.md) owns the asynchronous versus explicit synchronous contract. Native spec predicates/serializers are not declared worker-safe by this test. Convenience factories, actual native actor/backend/operation/UI/command services, richer types, both migrations and selected-pack/cost checks remain Phase 1 requirements. The increment is local and unpublished.

## Asynchronous JSON owner slice

The full Gradle `spotlessApply check build --warning-mode all` passes with 128 core tests, zero failures/errors/skips, ten tooling controls and the exact-artifact independent headless consumer. Four properties retain 920 generated cases. The final build took five seconds with relevant cached inputs. These tests use real separate native-owner and worker Java executors with disposable files; no Minecraft process, personal instance or native actor gateway is involved.

Twelve context controls exercise native/worker thread dispatch, current read/write authority, unavailable authorization, queued refusal without executor draining, active-result draining, bounded admission/access/hooks, repeated/rejected executor delivery, reentrant close and cleanup failure. Five document-lane controls cover the shared active/queued bound, release before terminal observers, action failure, queued cancellation at close and closure after selecting but before starting the next operation. The retained cleanup red/green case reproduced two hooks throwing the same exception preventing the remaining hook from running. A separate native-gate composition red/green case reproduced a structured permission refusal being mislabeled as unavailable authorization.

Thirteen connected-owner controls cover explicit initialization, browse-without-creation, complete save/application, one-shot and session-bound proposals, invalid/revoked input without persistence, two actors serialized through publication, saved-but-refused application-only retry, changed saved-byte refusal/review, failed reload retaining only qualified active values, and revoked READ suppressing new contents. Latch-controlled actual file replacement cases close the native context immediately before and after replacement: the former refuses/cleans the temporary file; the latter drains and reports `SAVED` with unavailable application. Neither is reported as a rolled-back or unattempted save.

The external Maven consumer now uses the public asynchronous owner, actual separate executors and file effects. It verifies browsing, initialization, save/application, later-draft acknowledgement, current authorization refusal and application-only retry preserving exact bytes and modification time. It still resolves the exact combined artifact without Minecraft/NeoForge classes; this is not the ordinary native addon/generated-form qualification.

| Artifact         | SHA-256                                                            |
| ---------------- | ------------------------------------------------------------------ |
| Combined mod JAR | `781690c66b2b12b2dcd50fa8ddbd04cb56536e6afce77e739d4e1b0947caf8ff` |
| Source JAR       | `61b7c5fb6f47a8c8b610128cb9dcee3daada2dace6db69ddaf041805c73f7074` |
| Maven POM        | `d664d2b954c669521ed927f64052cb26a55d2b9c940357e6abcf84e9b0e3244e` |

[Owners](owners.md) owns the actual admission/lifetime/receipt contract. No atomic native-permission/filesystem transaction, cross-process CAS, native backend, explicit operation cancellation, convenience factory, worker-prepared publication cost bound or complete Phase 1 readiness is claimed. GUI/Brigadier, richer types, native actor/connection qualification, both consumer migrations and selected-pack/cost checks remain required. The earlier FML fixture below remains evidence for its exact native contract, not this newer JSON service. This increment is local and unpublished.

## Effective publication slice

The full Gradle `spotlessApply check build --warning-mode all` passes with 97 core tests, zero failures/skips, ten tooling controls and the independent exact-artifact headless consumer. Relevant cached inputs reduce the final check to five seconds. Four existing properties retain 920 generated cases. No new game/client run is included in this publication slice.

Sixteen publication controls cover explicit uninitialized/unavailable state, current permission checks through the final effect boundary, consumed attempts, exact definition/foreign/stale revision refusal, semantic no-op identity/value retention, failed reload with qualified retained values, closure, mutable-value isolation, repeated validation and owner-thread confinement. Retained red/green controls reproduced reentrant validation overwriting a newer value, an actor callback returning after closing its context, unavailable authorization marked complete, and equal semantic input replacing the published representation. They now pass without weakening the controls. Additional callback-failure and close-during-validation controls preserve publication state or honest closed state.

The external public consumer now demonstrates actual saved/effective/draft separation: a file save succeeds, later typing survives acknowledgement, a changed authorization result refuses publication, and a fresh saved read supports publication-only retry without another file write. That callback is controlled local fixture state; actual native actor resolution, server/connection lifetime, service queues and native adapter application are not implied.

| Artifact         | SHA-256                                                            |
| ---------------- | ------------------------------------------------------------------ |
| Combined mod JAR | `7b0620b4835c81bd9a66321ccdda8d3f2ed0d975d93a14b30778ad7dd549e47b` |
| Source JAR       | `8d87e7109f4d3f0c5cd725d9ba86c9089f7e7e670b42a3675eff099ab60891f4` |
| Maven POM        | `d664d2b954c669521ed927f64052cb26a55d2b9c940357e6abcf84e9b0e3244e` |

[Publication](publication.md) owns the primitive's scope. At that cut, full asynchronous owners/receipts/operations, factory/native surfaces, richer types, both real consumer migrations and native/pack/cost qualification remained required. The native FML fixture below is evidence for its pinned native contract, not qualification of that publication API or a completed framework native adapter.

## Native registered callback contract

`python3 tools/native_config_probe.py --java-home /path/to/jdk21` passes two sequential 2 GiB dedicated-server boots using Minecraft 1.21.1 / NeoForge 21.1.265 / FML 4.0.45 / Temurin 21.0.13 on Apple Silicon macOS. The first boot passed eighteen controls in 11.089 seconds; the second passed three in 9.250 seconds, including Gradle startup in each duration. The runner's preceding full build/check also passed: 81 core tests, ten tooling controls and the exact-artifact headless public consumer. Source-mode loading and library-native integration are separate claims.

A real registered COMMON spec verifies loaded ownership, raw/live/world/game-restart distinctions, synchronous save/reload on `Server thread`, disk-before-callback order, native no-op reload dispatch, permission-denied persistence without reload but with already-mutated live state, and listener failure after intended persistence. A second JVM retains raw `[4,4,4]` and activates the restart-bound cached values. Initial loading used `modloading-sync-worker`. One warm save with the small probe spec took 763417 ns; that single observation is not a cost bound for arbitrary callbacks or the pack.

The controls deliberately reproduce both sides of the receipt problem: NightConfig `WritingException` with unchanged disk and changed live values, and FML `ModLoadingException` after the intended file was saved. [Native settings](native-settings.md) owns the resulting executor, cache, context and lock constraints. The fixture does not qualify a re:form native owner, client/integrated execution, native SERVER synchronization, unknown type handling, watcher/reentrancy coordination, arbitrary listeners or pack migration.

The opt-in probe JAR digest is `00334db1055cbc7942461f2d12cb70d09dcc7de90004c2f12f9584478edadb59`; its executed compiled class digest is `0ee85812a132f330039e67617db33a0cad16c6493e1e81bd55f7ff06f718371f`. The class bytes match that JAR, but launches use ModDev source containers rather than loading the packaged product/probe. At this native fixture cut, product/source/POM digests matched the storage slice. `check` compiles the source fixture; ordinary mod runs and distribution JARs exclude it. The runner refused-existing/exact-cleanup-token controls pass; its final receipt records both terminal native runs, removed fixture and removed raw logs. Compact exact source/dependency/call/result identities are retained in the adopted evidence store.

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

The storage API is synchronous worker-side code. At that cut, authorized asynchronous owners, actor/context checks, runtime publication, native TOML callbacks, registry collision admission and real migrations remained unimplemented. Linux/Windows filesystems, unsupported hard-link/atomic-move providers and hostile ancestor-directory replacement are not qualified by this local result. [Storage](storage.md) owns the actual guarantees and dependency-update checks.

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
