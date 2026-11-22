# Client file registration

`ClientFileSettings.clientJson(registration, definition)` is the physical-client convenience factory for a genuinely new JSON document. Pass the actual owning [native client registration](native-bindings.md), before native client setup. The namespace must match that actual mod container, and the declared loader configuration root plus document path must pass the existing portable binding rules. Registration validates metadata and uniqueness without filesystem IO or runtime defaults.

The [independent native addon](../../examples/native/src/main/java/example/reform/ClientExample.java) declares the definition and makes this factory call. It supplies no executor, writer, path resolver or editing session. Its definition uses [record derivation](api.md); constraints and defaults remain explicit.

## Existing and custom owned formats

`existingJson(registration, definition, format, location...)` binds an explicit envelope or bare JSON representation beneath the loader's configuration root. `properties(registration, definition, location...)` binds the declared flat UTF-8 properties representation. Supply portable path segments separately; neither helper renames an existing file or injects a JSON envelope into it. `clientFile(registration, store)` admits an explicitly constructed `TextStore<T>` for a custom owned format. That advanced caller supplies its real binding, parser, serializer and limits; the library supplies the same lifecycle, owner, handle, generated forms and commands.

`clientFile(registration, store, applicationEffects, liveApplication)` also binds a [verified live effect](owners.md#verified-live-application) when a separate client owner consumes the settings. Supply the existing native effect and a readback that can observe it during client setup and later saves. A settings value consumed directly from re:form's handle needs only ordinary publication.

All owned text-file registrations share document and conservative portable-path collision admission. A different document cannot register the same lexical normalized path, including case variants. This metadata check performs no filesystem lookup and does not replace the owned backend's actual root, regular-file, symlink and source-revision admission. Native keybindings and registered NeoForge writers retain their separate adapters and actual serializers.

Constructing a definition validates and freezes its declared defaults. File registration reuses that definition; this is separate from creating a source file or publishing defaults as runtime values. An existing source can supply different values, and an invalid source stays unavailable instead of becoming those defaults.

The store's immutable value-projection and draft limits also govern the generated editing and import services. Value JSON is the editing/import/export representation, not a conversion of the owned file format. The default local import catalog still accepts explicit document-value/envelope JSON; it is not an automatic properties/custom-format importer.

Ordinary file helpers publish live owned client values. A properties filename alone never makes a live owner into a startup-only owner. Installation defaults, native registered configs, world policy and server authority remain separate bindings.

Use `stagedFile(registration, store, initialization, effects)` when a separate service consumes the file on an explicit reload, the next server start or another declared boundary. `DEFER_MISSING` exposes editable unsaved defaults without creating directories or files. Explicit save uses the real parser, prepared serializer and revision guard and reports `STAGED` with the declared requirements. The adapter offers no effective handle or apply-only operation. The service that applies the file keeps its own authority and feedback.

An asynchronous resource reload belongs in an explicit owner operation, displayed through a workflow action. Keep one staged file owner and compose its actual confirmed persistence receipt with the native reload future and the resource service's matching publication receipt. Reuse the same `TextStore` for worker-side resource reads, with its actual source revision and typed candidate. A failed or cancelled reload does not undo a successful save. Application-only retry checks the expected saved revision against a fresh read and performs no persistence; changed, missing or invalid sources require explicit review. The operation retains saved and applied outcomes separately. `LiveApplication` remains a bounded synchronous effect/readback contract; starting a future there cannot establish application completion.

## New-world defaults

`newWorldDefaultsJson(registration, definition)` registers a new owned JSON defaults source using the same portable path, envelope and create-once initialization as ordinary client JSON. It publishes saved defaults for explicit later use and returns STAGED with a NEW_WORLD requirement. It never applies them to an open recipe or running world. The generated actions say **Save defaults**, **Save reviewed defaults** and **Save defaults and close**. Application-only retry is unavailable because it cannot activate these choices in a current world.

`newWorldDefaultsFile(registration, store, initialization)` retains an existing/custom source and explicitly chooses `FileOwner.Initialization.CREATE_DEFAULTS` or `DEFER_MISSING`. Deferred initialization reads without creating a file or directory. A missing source supplies only an attributed unsaved draft from the declared defaults, with the original absent-source revision; the defaults handle has no qualified value. An explicit Save creates the file conditionally even when its draft equals those initial defaults. A concurrent creator is not overwritten. Invalid, unsupported or unreadable existing data never becomes factory defaults.

READY in this mode admits the editor, including a deliberately unsaved default draft; it does not certify saved bytes or a selected generator. The source observation names absent data as unsaved. After initialization from an existing valid source or a confirmed save, `runtime()` represents those published saved defaults for later use, independently of their staged application requirement. Consumers must not treat that handle as the recipe of any open or running world. Native registered COMMON sources retain their own separate adapter and lifecycle.

`savedValueSource(sourceId, label)` explicitly exposes a read-only admitted import source. It captures current valid **saved values** through the actual file parser on the worker, not an editor draft, a missing-file fallback or a potentially stale runtime handle. Capture checks the owner's actual lifetime and READ authority; absent/invalid data refuses import. Exported bytes are bounded document-value JSON, not the backend's file format. The source identity and captured content hash remain visible in import review. This capability grants no path-writing or world-admission authority.

A [world recipe](world-recipes.md) can explicitly declare this source through its import catalog. Saving defaults leaves the selected recipe untouched; Preview compares captured values, Stage replaces one undoable draft and Apply remains the native creation owner's separate operation.

## Immutable startup selection

`startupProperties(registration, definition, selected, location...)` and `startupFile(registration, store, selected)` edit next-start choices without changing the current startup selection. `selected` is the consumer's actual immutable bootstrap `Candidate<T>` for that exact definition. An absent selection stays unknown; neither saved values nor definition defaults fill that gap. A consumer may explicitly capture its bootstrap input before registration, using its own actual parser/admission. The convenience registration itself reads no source.

Owner construction can prepare that selected snapshot asynchronously. The document remains INITIALIZING while construction and source initialization run. READY describes an admitted editable saved source; its effective handle can still have no qualified value when the actual selection is unavailable. A malformed saved file can instead leave the document UNAVAILABLE while a separately qualified active value remains readable. Repairing the source and choosing Retry does not change the selected startup input.

The shared core startup owner reports saved choices as STAGED, with explicit game-restart requirements and no application-only service. The generated form names its actions **Save for restart**, **Save reviewed choices** and **Save for restart and close**. Inspection separates source, draft and actually selected values. Saving and confirmed close never imply that loaded features changed. The consumer still owns the real next-start activation procedure; re:form does not reload mixins or rebuild its feature selection.

Each construction/initialization attempt has its own completion identity. It releases its active slot before notifying observers, so an observer can start a retry without the earlier attempt clearing it. Shutdown closes the actual context and settles the retained attempt even before an owner exists. A late callback cannot install an owner or initialize a file in the closed context. The first initialization result remains historical; status and explicit retry results retain their own outcomes.

## Lifecycle and published state

| State        | Meaning                                                                                                                    |
| ------------ | -------------------------------------------------------------------------------------------------------------------------- |
| REGISTERED   | Metadata exists; no native owner/handle has been created and no saved data has been inferred                               |
| INITIALIZING | Admitted native client setup is reading/initializing the actual source through the asynchronous owner                      |
| READY        | The declared source is admitted for editing; active startup values or explicitly deferred saved defaults can remain absent |
| UNAVAILABLE  | Initialization or observation failed; retain the source and inspect the actual report, receipt and optional runtime view   |
| CLOSED       | The native client lifetime ended; the actual runtime handle is closed and new effects are refused                          |

`status()` wraps the actual `SettingsHandle.View` where available, rather than publishing a second value authority. `runtime()` is absent before the native owner exists; its handle has explicit effective availability afterward. Reading either performs no file IO. `initialization()` describes the first attempt, including unavailable/closed outcomes. It is not a promise that every attempt succeeds, and cancelling a returned future copy does not cancel effects. Never block a native thread waiting on it.

Initialization completion concerns configuration, not the availability of a title screen, player, world or rendering resources. A completion observer can start an explicit retry immediately. The attempt being completed releases its tracking slot before invoking observers, so shutdown retains and settles a newer pending retry instead of stranding it.

Unexpected setup, owner-construction or completion-observation exceptions keep the document unavailable and log the original cause with its document ID and lifecycle stage. Structured policy refusals retain their supplied report without becoming unexpected-error log entries. User-facing reports do not expose source content or substitute effective defaults. A setup cleanup exception is retained as suppressed context on the original failure.

Native client setup supplies the actual client executor and loader root. Missing validated factory data is created once through the shared file owner's create-if-absent initialization. Existing valid data is read unchanged; malformed/unreadable/unsupported data is retained with an error. The new-JSON location/envelope remain `config/<mod-id>/<document-key>.json` and `{schemaVersion, values}`. Existing/custom bindings retain their declared paths and representations. No helper adds an environment/JVM/launcher settings-value provider.

Each document has its own owner context, one installation-local access binding and shared document admission. Pure validation/editing/IO use a common bounded worker pool; native capture/publication and lifecycle use the client thread. This is installation-local client authority, with no server permission or synchronization implied. Runtime consumers should capture/compile immutable published values at their real boundary; repeated defensive copies from `View.value()` are not a worldgen hot-path API.

The four-argument `clientJson(registration, definition, applicationEffects)` construction overload supplies immutable [application declarations](owners.md#declared-application-boundaries) before registration/setup. Ordinary construction declares only publication of this owner's value; it does not certify domain cache activation. Explicit metadata changes notices, not initialization, file ownership, callbacks or runtime-handle publication. No mutable post-setup configuration hook is installed.

## Entry and recovery

`registerMenu(title)` explicitly installs the native mod configuration entry and refuses an existing factory. It adds no inventory button, HUD or shortcut. Custom composition can call `screen(parent, title)` instead. READY opens the same public `SettingsScreen` and shared actor services; loading/unavailable/closed opens a measured status screen with native narration and appropriate Back/Retry actions. Opening a screen never initializes a file.

The parent screen is a return destination; it does not retain an editor's ownership. Replacing an independent settings screen outside its declared auxiliary handoff abandons that editor. Close an independent form before opening an unrelated one. Use a [client workflow](client-workflows.md) to retain drafts and actors across document views.

`retryInitialization()` is explicit recovery for an unavailable admitted owner. It observes the actual source again; valid repaired data can publish without rewriting it. The first attempt's result stays historical, while current status carries the latest receipt. A file saved before a later error stays saved; closing or retrying is not rollback.

Physical-client runtime admission and shutdown are shared with [native keybindings](native-keys.md), independently of either file format. Game shutdown closes registered documents and managed creation contexts before shutting down their shared worker executor. Cleanup continues across context failures. Core owners retain their active-effect/queued-refusal semantics; this does not promise a process-crash transaction or a universal synchronous exit drain.

File and native form entry also share construction cleanup: ownership transfers only after the complete screen exists. If an auxiliary editor or source binding refuses construction, its newly opened session/actor/operations close without closing the borrowed document context. A later valid view can still open.

## Reference sources

`references(source, keyType, valuePolicy, limits)` creates explicit reference actions under this document's ready actual client owner/access. It creates no new executor and captures no native data. The caller retains the source, declares its complete capture/reload policy and closes it when no longer needed; owner shutdown also closes it. Do not create another source on every frame or screen rebuild.

`screen(parent, title, sources)` accepts borrowed source bindings keyed by their actual logical IDs. Its native Browse picker uses the same lookup services and stages through the existing editor. Missing bindings preserve manual input with an unavailable state. Closing a picker keeps the parent editor and source open; unexpected navigation closes that editor. No menu or automatic source discovery is installed by these overloads.

The independent [reference example](../../examples/native/src/main/java/example/reform/ReferenceExample.java) uses `bindCaptured` to capture the actual block/tag registry and explicitly enumerate client language-resource names, compile their indexes on the owned worker lane, and publish only a fresh complete snapshot. It invokes no optional supplier for its deliberately absent donor. Source refresh is explicit in this example; real consumers must bind their actual registry/resource reload and world-context lifetime. A client global block registry is not a substitute for a new world's frozen dynamic registry context.

## Explicit commands

`registerCommands("preferences")` explicitly contributes a native client command root once during client construction. Registration refuses a conflicting root and unregisters its event listener at shutdown. It adds no server command or permission and requires READY client settings. `commandBranch(literal, source, output)` instead supplies a Brigadier branch and native-thread result sink for explicit composition. Default feedback goes to the current client player; no chat recipient is fabricated at the main menu.

The branch uses [shared local operations](operations.md). Its editor is created lazily for an actual command and retained for that installation context. Suggestions inspect only already available declarations. Commands and GUI share the real owner/runtime but retain separate drafts. Commands stage values before explicit save; stale/closed targets and sources whose native player is no longer current cannot receive late feedback. The helper admits 32 operation definitions, 64 active operations, 128 retained observations and 8,192 visible feedback characters, independently of JSON file admission. Generic bindings can select other declared bounds.

`importCatalog(sources)` declares explicitly admitted source capabilities with the same value/envelope JSON choices and repairable-draft policy as the generated form. `registerCommands(literal, catalog)` or `commandBranch(literal, catalog, source, output)` enables [registered import commands](imports.md#registered-native-commands). The catalog must use this actual definition instance. Ordinary overloads expose no import choices. A catalog/source name never resolves a server path, and listing/suggestions perform no capture or file IO.

## Bounds and qualification

The convenience registry admits 128 document identities. Its shared pool has two workers, a 256-task queue and 30-second idle thread expiry. Each document retains the core standard context limits, a 16-operation owner lane and 64-edit actor lane. Its JSON admission is 256 KiB, 64 depth levels, 16,384 nodes, 32,768 string characters, 128 numeric characters and absolute decimal scale 4,096. These are explicit helper defaults, separate from field constraints, game networking and owner-specific advanced adapters.

[Verification](verification.md) separates core controls and exact native runs. The independent factory example passes FML registration, saved reopen, malformed retention/recovery, native Brigadier operations and pending reentrant retry/shutdown in the independently attributed native suites, including recursive controls and source-bound reference pickers. Dedicated-side loading separately preserves the absence of the client document. Other owner/native backends, full operations/control composition and measured costs remain Phase 1 work. Actual consumer source adoption requires its separate authorized scope.
