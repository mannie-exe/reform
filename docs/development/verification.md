# Verification

This record distinguishes build checks, native behavior and consumer integration by the artifact exercised. The pinned target is Java 21, Minecraft 1.21.1 and NeoForge 21.1.265. Native UI and installed-pack observations were made on Apple Silicon with Temurin 21.0.13+9.

## Release artifact

The qualified combined re:form 0.1.0 JAR has SHA-256 `445a6ca31cf37801be756afbe4abc103083fddcaab7b6f2a9edb1b34210c62db`. Its sources have SHA-256 `d2017e0fc54bcba764cfa263740d87129a6d48c57cafde69bb5d3bc13297d4b1`. A clean archive of product source `71a3e97` rebuilt the same combined JAR, source JAR, POM and Gradle module metadata. Subsequent delivery and documentation changes are checked against those qualified bytes.

`./gradlew check build` covers 541 core cases, eight adapter cases, tool tests, packaged artifact inspection, independent headless execution and native addon compilation. The headless consumer runs without game classes. The native example compiles against the combined Maven artifact. [Publishing](../publishing.md) owns the hosted matrix, security checks and release procedure.

## Coordinated Phase 1 candidate

Both Skyweave and Skyweave: Compatibility Patches use the same exact re:form 0.1.0 JAR. Each declares it as a required dependency on both physical sides. Their domain validation, storage formats, native callbacks and world-generation identity remain with the consumer.

| Consumer                       | Qualified combined JAR SHA-256                                     | Relevant evidence                                                                                                                                  |
| ------------------------------ | ------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| Compat 0.41.0                  | `45aec356701353a8c625845cb9148217f966973cccb216aea5297998808becaa` | Actual Configure, live Hoofprint save, delegated native settings, guarded generation/shape review, integrated policy and shader operation outcomes |
| Skyweave development candidate | `16c5ba6ad7865af80ae1cabcf3035a6f8d30112c1653943f60f65d50ba0b0428` | Actual Configure effects/retry, complete future defaults, native Customize/Apply/Create/Save and Quit/reopen, shared DH identity                   |

The installed joint fixture uses published Create: Skyweave 0.21.0. Empack 0.8.7-beta installed 515 server and 538 client files verified against their manifests. Fixture changes select the reviewed candidate JARs, loopback and an ephemeral server port, two-chunk view/simulation distances, seed 42 and a disabled BlueMap test listener. Client and server heaps are capped at 6 GiB.

Three dedicated boots reach the console, answer `list`, save and stop. The existing Sky Arch save retains complete `WorldGenSettings` and its DH identity. A new Skyweave save creates a distinct identity, then a second JVM retains both its generation settings and identity. Each native Configure entry opens the shared searchable overview, renders at 427×240 logical size and returns to the title screen. Compat's actual Local files action opens its overview from the context chooser.

The server trial used Compat JAR `221099a46a5781a1d584f6fa8d2ee6828c8a4a6eb16bc585f10ea520a850f86d`. The final artifact restores inspection of cached shader failures. Its three changed classes are reached through the physical-client mod entry point, and one localization file changes. All 1,113 other archive entries, including common/worldgen code and loader metadata, are byte-identical. The final installed client was rerun against `45aec356…`. Its separate real Iris test covers rebuild, malformed-recipe failure, cached read-only status and successful retry.

Joint checks exposed a camel-case Hoofprint storage key reused as a lowercase control identity and a Skyweave DH bridge admitting only older Compat versions. The corrected packages retain the original stored fields and DH marker format. The initial 0.20.0 pack fixture also refused three retired Redstone Additions switches through Compat's existing strict parser. Published pack 0.21.0 removes those authored keys.

The packaged client uses pinned named ModDev game libraries, installed pack files and a fixture-only observer, with product source roots absent. This qualifies the named entry and lifecycle paths. Individual consumer fixtures establish particular settings writes and effects. Prism installer behavior, complete gameplay and full world-generation quality retain their own qualification.

## Independent native checks

| Scope                                | Artifact                                                                            | Observations                                                                                                                                                          |
| ------------------------------------ | ----------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Dedicated policies and commands      | 0.1.0                                                                               | First boot and reopen pass 33 assertions, 12 command observations and 15 search controls each, followed by native shutdown                                            |
| Integrated policies and workflows    | 0.1.0                                                                               | First boot and reopen pass 46 and 11 assertions, including shared navigation, permission retirement, real TOML persistence, watcher changes and context closure       |
| Hierarchical catalog                 | adoption.11, JAR `b76d272b25f5f8418dc1272020ad9097dbedf1698a88e1bf006f5f9b4c6d6896` | Three category levels, 131 placements of one owner, description search, retained drafts, All/Modified/Invalid/Restart, localized field reveal and wide/narrow layouts |
| Scoped submission                    | adoption.6, JAR `f6d4f84c801e6a6ebc9e88c443a0fde61e4154a1fbddaff0ec6369e93a8c6359`  | Real ordered checked/writable owners, scoped preflight, separate receipts, guarded dependencies and saved reopen                                                      |
| Complete recipe review               | adoption.8, JAR `f4570b4eb79406aa7a2db16ad889799f2b118aec5140993bfa88acc25b12d904`  | Complete recipe above 2 MiB, retained hidden input, stale reviewed-action refusal and separate native context admission                                               |
| Native dependencies and availability | adoption.9, JAR `5ee1bf307c5b89ed1c029c1bc4114b453e1c3b3faeb161996d54cd0fcf0abf16`  | Complete native/domain validation, restart declarations, unavailable scalar/subtree fields, focusable explanation and unchanged source before Save                    |

The integrated 0.1.0 fixture forces a real FML watcher reload after a byte-equivalent TOML file replacement. Saving the old observation returns `REFUSED / NOT_ATTEMPTED`, preserves the draft and file, and produces zero native saves. Explicit Reload saved and conflict review retain the typed change against the fresh source. The next save returns `SAVED / STAGED` through one native save callback on the server thread. A fresh JVM reads both saved policies.

Editor and catalog class entries are byte-identical between adoption.11 and 0.1.0. The later native-owner diagnostic change is covered separately by the 0.1.0 dedicated and integrated runs. Earlier native evidence retains its original artifact identity. It is not counted as a full rerun on a later version.

## Phase 1 standalone candidate

Before consumer migration, the `0.1.0-SNAPSHOT` artifact `2a44c742d872549077ab86e0098246ce63c9243512ea6e6b8d19764b65f6bff6` passed 533 core, eight adapter and 31 tooling cases, independent packaged consumers, native UI, installed minimum/full-pack startup and save/reopen controls. The sources were `e7069964c7302c029fc3add39f5956646b9d8f9f05eee1671891b6fd3cf01768`.

Its seven native submission boots exercise edited drafts during a held preview, cooperative cancellation/drain, identified results, export review and explicit native admission. A loopback TCP client with native observation code exercises ordinary server settings/search commands, dimension changes, new-login refusal and native operator revocation. Matching-definition remote forms are deferred.

The earlier standalone record and incremental native controls, including numeric editors, references, collections, keys, import, conflict repair, text/caret retention and native chooser behavior, remain attributable to their exact artifacts in the [development evidence](https://github.com/mannie-exe/reform/blob/95dad8c44eb883f9363248af94513a0fec9e2c74/docs/development/verification.md). Current [coverage](coverage.md) maps supported features to their owning contracts.

## Native search reporting

Independent dedicated and real-player fixtures exercise stable bounded pages, coalesced notices, captured origin, native permission, same-connection respawn and separate job cancellation/drain. Distinct native chunk tickets remain owned by their jobs until actual settlement. Settings drafts retire on respawn while an admitted search recipient can follow its same-connection result page. Consumer-specific candidate enumeration, placement and scheduling retain their own checks. [Search reporting](search-reporting.md).

## Bounded editing costs

A packaged public workload used a 384 MiB heap and JFR while other native fixtures were active on the host. It builds nested profile records and role maps, validates complete drafts, reloads real JSON and reviews 32 owner documents. These are warmed service timings and calling-thread allocations.

| Workload                         | 62 catalog entries | 482 catalog entries |
| -------------------------------- | -----------------: | ------------------: |
| Complete catalog projection, p95 |           0.338 ms |            0.990 ms |
| Search cached labels, p95        |           0.007 ms |            0.018 ms |
| Validate an edited draft, p95    |           1.033 ms |            3.792 ms |
| Reload and decode JSON, p95      |           3.576 ms |            5.285 ms |
| Allocation per validation        |           0.53 MiB |            3.69 MiB |

The 32-document review contains twelve profiles per owner. Fifteen complete reviews have a 74.170 ms median and 195.107 ms maximum. Sixty exports have a 0.738 ms p95. Native frame timing, cold disk, long-session retention and other hosts are outside this sample.

## Reproduction and evidence

`mise run check` validates Markdown, workflow syntax, Java, tests and packaged consumers. `mise run check:security` scans resolved library/test dependencies and source secrets. [Security reporting](../../SECURITY.md) owns the reviewed dependency exception.

Explicit native controls use disposable fixtures and the exact packaged Maven library. The addon/probe source containers stay separate from the product. The runners retain bounded reports and matched captures under `build/reports/`, then remove their marked worlds and logs.

```sh
python3 tools/native_addon_server_probe.py --policies --java-home /path/to/jdk21
python3 tools/native_addon_server_probe.py --registered --java-home /path/to/jdk21
python3 tools/native_form_probe.py --integrated-only --java-home /path/to/jdk21
python3 tools/native_form_probe.py --workflow-only --java-home /path/to/jdk21
python3 tools/native_form_probe.py --catalog-only --java-home /path/to/jdk21
python3 tools/native_form_probe.py --recipe-only --java-home /path/to/jdk21
```

The form runner exposes additional focused modes through `--help`. Run affected cases after changing their owner, parser, widget, permission or lifetime contract. Desktop runs require current shared-focus access. Physical IME, host clipboard, spoken audio, Windows/Linux native rendering, shader image quality and maximum document/heap cost retain explicit qualification limits.

Keep exact commands, source identities, artifacts, outcomes and relevant omissions with consequential checks. Retain compact evidence and only required immutable inputs. Phase 2's richer presentation and remote forms have separate implementation scope.
