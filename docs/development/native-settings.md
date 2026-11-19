# Native registered settings

`RegisteredConfig` is the experimental explicit native owner. This document owns its execution contract; [storage](storage.md) owns independent JSON/file behavior. The native constraints come from NeoForge 21.1.265 / FML 4.0.45 sources/bytecode, a direct native contract fixture and a separate packaged-library consumer. [Verification](verification.md) owns tested source identities and omissions.

## Admit an actual registered owner

The [server factory](server-settings.md) supplies this explicit backend's actual-server context, bounded workers, local native-source editor access and native-stop cleanup. Direct backend construction remains available for advanced native owners; neither entry replaces FML loading or its serializer.

Construct `RegisteredConfig` on the actual `OwnerContext` executor with the admitted `ModConfig` and a document ID in that registered mod's namespace. The adapter uses that exact registered `ModConfigSpec`, loaded configuration identity and native path. It does not register a spec, initialize a missing file, correct saved values or install lifecycle/actor lookup. The caller still owns context creation, actual access callbacks and closure before its executor stops.

`definition()` exposes a fixed nested record of declared native fields. Common scalar types and nonempty string lists have built-in types; ambiguous empty lists and custom values require an explicit `FieldPath`/`ValueType` mapping. Unknown type addresses are refused. Default suppliers and native metadata are read on the owner executor; pure codecs/snapshots and draft work remain worker-safe. The virtual document's schema identity does not add a version field to TOML.

The standard owner admits 262,144 file bytes, 1,024 declared entries, 64 sessions and 16 document operations. Declared structure is bounded to 32 levels. Normalized source/draft admission separately bounds depth, nodes, text and numeric input. Constructor limits are explicit; large or ambiguous native documents do not silently receive replacement defaults. This initial adapter does not yet derive the full range/description/presentation policy or consumer-specific new-world rules.

Actor sessions implement the shared `DocumentSession<Map<String, Object>>` contract. Reads use the actual bounded regular native file and strict UTF-8, parse with NightConfig on the worker, then check actual native validators and saved/raw agreement on the native executor. Invalid TOML remains untouched. Exact-byte source revisions include comments; matching typed values alone do not admit an external edit. Native filenames are preserved, rather than being renamed to the portable convention for newly owned JSON documents. Leaf symlinks are refused.

Preparation requires a complete valid draft and the actual native field predicates. Commit consumes its issued proposal on submission, including permission or queue refusal, as JSON owners do. A retry needs fresh preparation. Before the first setter it rechecks access, exact loaded identity/path, uncached native values, actual predicates and the original saved bytes. A bounded synchronous last file check closes the worker-to-owner queue interval; it is a deliberate native preflight cost. Native mutation/save stays on the actual owner executor. No library file lock spans setters, native save or listeners, and there is no cross-process compare-and-swap guarantee.

The shared `EditorActor` can load/edit/save this owner without a second native draft/history implementation. Registered native settings do not support `applySaved`: the adapter refuses application-only retries instead of replaying the serializer or inventing a reload event. The server factory supplies explicit server lifecycle/editor wiring. Physical-client native registration, integrated GUI/command entry, broader metadata/effect policy and other contexts remain Phase 1 work; consumer migration follows its separately authorized scope.

## Preserve the native operation

`ConfigValue.set` mutates the loaded native configuration. A value without a restart requirement also updates its native cache immediately. This happens before disk persistence or a reload callback. World/game-restart values retain their already-warmed caches while their raw values change.

`ModConfigSpec.save()` invokes the actual loaded configuration. FML writes through NightConfig's atomic replacement writer, then acquires its native mod lock and synchronously dispatches `ModConfigEvent.Reloading` on the caller thread. No automatic IO-worker handoff occurs. Initial native loading in the fixture used `modloading-sync-worker`; explicit saves used `Server thread`. These are different lifecycle operations, not an instruction to run editing callbacks on the loading worker.

The native save path includes both IO and arbitrary mod listeners. Keep that public serializer/event path on its admitted native owner executor. This is a deliberate exception to worker-side owned JSON IO, with latency measured per qualified consumer. Do not privately copy FML persistence to move only part of the call, fire an imitation event or clear every restart cache after a save.

No-op prevention belongs before native mutation/save: the inspected native save still dispatches reload when values have not changed. Validate complete candidates and check source/native baselines before the first `set`. Generic text/structure preparation can use workers; foreign specification predicates and callbacks need an inspected executor contract rather than an assumption that thread-safe containers make them safe anywhere.

## Report three distinct observations

Keep saved bytes, uncached native values and domain effective values separate. In the fixture, denying directory writes prevented persistence and reload dispatch, but the preceding setters had already changed the live native value. Throwing from the reload listener instead left the intended file saved and the native raw values changed. Neither exception establishes that runtime state stayed unchanged.

The adapter must observe actual file/native state after an attempted failure and retain saved/failed/unknown persistence separately from application/callback status. A callback may have effects beyond its native values. Restoring a value in the container cannot prove rollback of arbitrary listeners. The probe restores only its own known inputs between controls; that is not a general rollback algorithm.

`RegisteredConfig.Receipt` retains whether native mutation started and whether native save returned. A completed save with authorized matching readback is `SAVED`; unchanged original bytes after an attempted failure are `FAILED`; other unconfirmed final contents are `UNKNOWN`. A failure before setters is an explicit unattempted refusal. Native exceptions do not trigger generic rollback.

Matching saved/raw values and returned listeners are reported as `STAGED`, with per-field native world/game-restart or owner-managed requirements. This initial adapter has no domain-effective observation policy, so it does not claim that arbitrary copied mod state applied. Changed readback can be `PARTIAL`. Failed save/listener application is `UNAVAILABLE`, independently of confirmed disk success. READ revocation or context closure suppresses final readback; the receipt retains execution facts but reports final persistence as `UNKNOWN`.

`getRaw()` is the native editing/persistence view. Native `get()` is a cached gameplay read that may initialize a previously unused cache. The fixture explicitly warms it to test restart behavior. Do not invoke it merely to invent an effective-value display without an owner-declared observation policy. Useful unknown/pending states are preferable to a false effective value; arbitrary mods can keep separate copied state in listeners.

The observation also retains [application declarations](owners.md#declared-application-boundaries) captured from each field's actual `ValueSpec.restartType()` during native owner construction. This reads metadata rather than `ConfigValue.get()`, so it does not warm gameplay caches. Restart-free fields remain owner-managed. Recheck metadata access, restart enum meaning and the cold-cache control alongside serializer/listener behavior after NeoForge updates. These are future notices, not the receipt from an earlier save.

## Context and lock boundaries

Use the actual admitted `ModConfig`, registered spec and loaded path. A filename or COMMON type does not establish server authority. `getFullPath()` is unavailable for a configuration without a native file backing; do not substitute an installation-default file for a synchronized or unavailable context. Keep CLIENT/COMMON/SERVER availability, actual world overrides and native synchronization with their owner.

Serialize cooperating commits through the owner service, retaining source and native-value preconditions. Do not carry an owned-file path lock into native callbacks. Inspected FML loading/reload paths hold their own mod lock, and a foreign listener can reenter other services; introducing the opposite library-lock order risks a cycle. This is a design constraint from the call graph, not qualification of a completed re:form/native-watcher concurrency adapter. No lock should span an asynchronous wait for its own executor.

Public scalar metadata and real native validators must agree. Unsupported or ambiguous values need a useful read-only/explicit-type path, not a guessed type or replacement default. Field presentation can be shared while native formats, comments, callback effects, restart behavior and native registration remain authoritative. Preserve actual consumer generation guards separately from native restart annotations.

## Reproduce the contract controls

The optional [native probe](../../neoforge/src/nativeProbe/java/example/reform/NativeConfigProbe.java) is a separate source set excluded from both product and source JARs. Ordinary runs do not register it. `check` compiles it so public native API drift is visible; actual game execution remains explicit.

Run `python3 tools/native_config_probe.py --java-home /path/to/jdk21` on a POSIX host. The runner builds/checks, creates a marked disposable flat fixture bound to loopback on an ephemeral port, accepts the EULA only there, and performs two sequential 2 GiB native boots. Its default deadline is 150 seconds per build/boot, with a log stop threshold. It refuses an existing fixture, requires its exact token for cleanup, retains a concise report under `build/reports/`, then removes its own world and raw logs. The test mode file is a fixture input, not a user-setting override source.

These are ModDev source-container launches. They qualify the pinned native lifecycle with a small real registered spec, not packaged-product loading, the future adapter, client UI, arbitrary native listeners, multiplayer synchronization or pack behavior. Keep those qualifications separate before migrating the consumers.

The separate `python3 tools/native_addon_server_probe.py --registered --java-home /path/to/jdk21` runner qualifies the public adapter/shared editor against the exact packaged Maven library in two sequential dedicated boots. The addon and probe still use ModDev source containers. Its own marked, loopback-only, ephemeral-port, flat seed 42 fixture and 2 GiB limit follow the independent example's guard/cleanup contract. Ordinary example runs do not register the probe spec. It covers native no-op, strict/native validation, issued-token/session/authority/source checks, warm/cold restart caches, actual save/listener failures, malformed retention, shared editing, redacted readback, reentrant close and reopened saved data. It does not qualify arbitrary watcher/listener reentrancy, native GUI/server synchronization or actual consumer generation rules.
