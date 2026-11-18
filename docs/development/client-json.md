# Client JSON registration

`ClientJsonSettings.clientJson(modBus, container, definition)` is the physical-client convenience factory for a genuinely new JSON document. Register it in the owning client mod constructor before FML client setup. The namespace must match that actual mod container, and the declared loader configuration root plus document path must pass the existing portable binding rules. Registration validates metadata and uniqueness without filesystem IO or runtime defaults.

The [independent native addon](../../examples/native/src/main/java/example/reform/ClientExample.java) declares the definition and makes this factory call. It supplies no executor, writer, path resolver or editing session. Its definition uses [record derivation](api.md); constraints and defaults remain explicit.

## Lifecycle and published state

| State        | Meaning                                                                                                                  |
| ------------ | ------------------------------------------------------------------------------------------------------------------------ |
| REGISTERED   | Metadata exists; no native owner/handle has been created and no saved data has been inferred                             |
| INITIALIZING | Admitted FML client setup is reading/initializing the actual source through the asynchronous owner                       |
| READY        | Initialization observed actual effective publication                                                                     |
| UNAVAILABLE  | Initialization or observation failed; retain the source and inspect the actual report, receipt and optional runtime view |
| CLOSED       | The native client lifetime ended; the actual runtime handle is closed and new effects are refused                        |

`status()` wraps the actual `SettingsHandle.View` where available, rather than publishing a second value authority. `runtime()` is absent before the native owner exists; its handle has explicit effective availability afterward. Reading either performs no file IO. `initialization()` describes the first attempt, including unavailable/closed outcomes. It is not a promise that every attempt succeeds, and cancelling a returned future copy does not cancel effects. Never block a native thread waiting on it.

Initialization completion concerns configuration, not the availability of a title screen, player, world or rendering resources. A completion observer can start an explicit retry immediately. The attempt being completed releases its tracking slot before invoking observers, so shutdown retains and settles a newer pending retry instead of stranding it.

FML client setup supplies the actual client executor and loader root. Missing validated factory data is created once through the existing create-if-absent JSON owner. Existing valid data is read unchanged; malformed/unreadable/unsupported data is retained with an error. The default location/envelope remain `config/<mod-id>/<document-key>.json` and `{schemaVersion, values}`. This factory does not migrate existing/native/world files or add an environment/JVM/launcher settings-value provider.

Each document has its own owner context, one installation-local access binding and shared document admission. Pure validation/editing/IO use a common bounded worker pool; native capture/publication and lifecycle use the client thread. This is installation-local client authority, with no server permission or synchronization implied. Runtime consumers should capture/compile immutable published values at their real boundary; repeated defensive copies from `View.value()` are not a worldgen hot-path API.

## Entry and recovery

`registerMenu(title)` explicitly installs the native mod configuration entry and refuses an existing factory. It adds no inventory button, HUD or shortcut. Custom composition can call `screen(parent, title)` instead. READY opens the same public `SettingsScreen` and shared actor services; loading/unavailable/closed opens a measured status screen with native narration and appropriate Back/Retry actions. Opening a screen never initializes a file.

`retryInitialization()` is explicit recovery for an unavailable admitted owner. It observes the actual source again; valid repaired data can publish without rewriting it. The first attempt's result stays historical, while current status carries the latest receipt. A file saved before a later error stays saved; closing or retrying is not rollback.

Game shutdown closes registered preference documents and managed creation contexts before shutting down their shared worker executor. Cleanup continues across context failures. Core owners retain their active-effect/queued-refusal semantics; this does not promise a process-crash transaction or a universal synchronous exit drain.

## Explicit commands

`registerCommands("preferences")` explicitly contributes a native client command root once during client construction. Registration refuses a conflicting root and unregisters its event listener at shutdown. It adds no server command or permission and requires READY client settings. `commandBranch(literal, output)` instead supplies a Brigadier branch and native-thread result sink for explicit composition. Default feedback goes to the current client player; no chat recipient is fabricated at the main menu.

The branch uses [shared local operations](operations.md). Its editor is created lazily for an actual command and retained for that installation context. Suggestions inspect only already available declarations. Commands and GUI share the real owner/runtime but retain separate drafts. Commands stage values before explicit save; stale/closed targets and sources whose native player is no longer current cannot receive late feedback. The helper admits 32 operation definitions, 64 active operations, 128 retained observations and 8,192 visible feedback characters, independently of JSON file admission. Generic bindings can select other declared bounds.

## Bounds and qualification

The convenience registry admits 128 document identities. Its shared pool has two workers, a 256-task queue and 30-second idle thread expiry. Each document retains the core standard context limits, a 16-operation owner lane and 64-edit actor lane. Its JSON admission is 256 KiB, 64 depth levels, 16,384 nodes, 32,768 string characters, 128 numeric characters and absolute decimal scale 4,096. These are explicit helper defaults, separate from field constraints, game networking and owner-specific advanced adapters.

[Verification](verification.md) separates core controls and exact native runs. The independent factory example passes FML registration, saved reopen, malformed retention/recovery, native Brigadier operations and pending reentrant retry/shutdown in four JVMs. Dedicated-side loading separately preserves the absence of the client document. Other owner/native backends, full operations/control composition, measured costs and both migrations remain Phase 1 work.
