# Native registered settings

The native adapter is not implemented yet. This document owns the qualified execution constraints for that adapter; [storage](storage.md) owns independent JSON/file behavior. These constraints come from NeoForge 21.1.265 / FML 4.0.45 sources/bytecode and an actual isolated dedicated-server fixture. [Verification](verification.md) owns tested source identities and omissions.

## Preserve the native operation

`ConfigValue.set` mutates the loaded native configuration. A value without a restart requirement also updates its native cache immediately. This happens before disk persistence or a reload callback. World/game-restart values retain their already-warmed caches while their raw values change.

`ModConfigSpec.save()` invokes the actual loaded configuration. FML writes through NightConfig's atomic replacement writer, then acquires its native mod lock and synchronously dispatches `ModConfigEvent.Reloading` on the caller thread. No automatic IO-worker handoff occurs. Initial native loading in the fixture used `modloading-sync-worker`; explicit saves used `Server thread`. These are different lifecycle operations, not an instruction to run editing callbacks on the loading worker.

The native save path includes both IO and arbitrary mod listeners. Keep that public serializer/event path on its admitted native owner executor. This is a deliberate exception to worker-side owned JSON IO, with latency measured per qualified consumer. Do not privately copy FML persistence to move only part of the call, fire an imitation event or clear every restart cache after a save.

No-op prevention belongs before native mutation/save: the inspected native save still dispatches reload when values have not changed. Validate complete candidates and check source/native baselines before the first `set`. Generic text/structure preparation can use workers; foreign specification predicates and callbacks need an inspected executor contract rather than an assumption that thread-safe containers make them safe anywhere.

## Report three distinct observations

Keep saved bytes, uncached native values and domain effective values separate. In the fixture, denying directory writes prevented persistence and reload dispatch, but the preceding setters had already changed the live native value. Throwing from the reload listener instead left the intended file saved and the native raw values changed. Neither exception establishes that runtime state stayed unchanged.

The adapter must observe actual file/native state after an attempted failure and retain saved/failed/unknown persistence separately from application/callback status. A callback may have effects beyond its native values. Restoring a value in the container cannot prove rollback of arbitrary listeners. The probe restores only its own known inputs between controls; that is not a general rollback algorithm.

`getRaw()` is the native editing/persistence view. Native `get()` is a cached gameplay read that may initialize a previously unused cache. The fixture explicitly warms it to test restart behavior. Do not invoke it merely to invent an effective-value display without an owner-declared observation policy. Useful unknown/pending states are preferable to a false effective value; arbitrary mods can keep separate copied state in listeners.

## Context and lock boundaries

Use the actual admitted `ModConfig`, registered spec and loaded path. A filename or COMMON type does not establish server authority. `getFullPath()` is unavailable for a configuration without a native file backing; do not substitute an installation-default file for a synchronized or unavailable context. Keep CLIENT/COMMON/SERVER availability, actual world overrides and native synchronization with their owner.

Serialize cooperating commits through the owner service, retaining source and native-value preconditions. Do not carry an owned-file path lock into native callbacks. Inspected FML loading/reload paths hold their own mod lock, and a foreign listener can reenter other services; introducing the opposite library-lock order risks a cycle. This is a design constraint from the call graph, not qualification of a completed re:form/native-watcher concurrency adapter. No lock should span an asynchronous wait for its own executor.

Public scalar metadata and real native validators must agree. Unsupported or ambiguous values need a useful read-only/explicit-type path, not a guessed type or replacement default. Field presentation can be shared while native formats, comments, callback effects, restart behavior and native registration remain authoritative. Preserve actual consumer generation guards separately from native restart annotations.

## Reproduce the contract controls

The optional [native probe](../../neoforge/src/nativeProbe/java/example/reform/NativeConfigProbe.java) is a separate source set excluded from both product and source JARs. Ordinary runs do not register it. `check` compiles it so public native API drift is visible; actual game execution remains explicit.

Run `python3 tools/native_config_probe.py --java-home /path/to/jdk21` on a POSIX host. The runner builds/checks, creates a marked disposable flat fixture bound to loopback on an ephemeral port, accepts the EULA only there, and performs two sequential 2 GiB native boots. Its default deadline is 150 seconds per build/boot, with a log stop threshold. It refuses an existing fixture, requires its exact token for cleanup, retains a concise report under `build/reports/`, then removes its own world and raw logs. The test mode file is a fixture input, not a user-setting override source.

These are ModDev source-container launches. They qualify the pinned native lifecycle with a small real registered spec, not packaged-product loading, the future adapter, client UI, arbitrary native listeners, multiplayer synchronization or pack behavior. Keep those qualifications separate before migrating the consumers.
