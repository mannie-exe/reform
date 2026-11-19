# Shared local operations

`DocumentActions<T>` binds one admitted `EditorActor<T>` to typed services shared by native forms and commands. It receives the real `OwnerContext` and its actual `Access`. Field selectors, operation UUIDs and document names grant no authority. The [client JSON factory](client-files.md) supplies this wiring for ordinary client preferences; other owners can construct it without replacing their parser, serializer or application policy.

The current services cover reload, cached inspection, raw scalar editing, undo/redo, revert/defaults, validation, source/effective inspection, semantic comparison, value export, save and application-only retry. Attached [local import](imports.md) supplies admitted-source preview and reviewed draft replacement; [manual conflicts](conflicts.md) supplies explicit comparison/choice/resolution. Each has its own bounded scope over the same actor. GUI and command editors retain separate drafts over the shared owner. The native [operation view](presentation.md#identified-submission-and-operation-feedback) presents identified outcomes and application-only recovery; broader owners and domain jobs remain required by [coverage](coverage.md).

`DocumentActions.edit(entry, DraftEdit)` additionally exposes [shape-checked structural editing](editing.md#public-structural-edits) through typed local inputs. This service addresses actual editor/row/variant identities and requires READ; it changes a draft only. The native Brigadier tree below retains its declared-field scalar syntax. A custom frontend can use the shared structural service without introducing an arbitrary patch operation.

[Reference actions](references.md) use the same local operation services for explicit source lookup and filtering. They bind source/context freshness and admit only bounded owned results; frontend draft/request identity remains a separate presentation requirement.

`supportedOperations()` exposes the immutable registration-time source declaration for frontend hints. It supplies neither current permission nor a success promise. Unsupported application-only owners must not be emulated by another save. Actual commands and operations still check native authority, source and context at their own boundaries.

## Registration and execution

`OperationDescriptor<I, O>` declares a typed input, required right, effect labels, entry points and optional logical target. Explicitly register handlers in an actor-bound `OperationScope`, then freeze registration. Submission checks the exact registered descriptor instance and fresh authority; matching names do not make a foreign descriptor a capability. Effect labels describe intent, not completed effects.

Inputs remain immutable during admission. Workers freeze, encode and strictly decode against the declared type and scope limits. Pure preparation can run in parallel. Providers enter in submission order on the native executor; that lane releases when the provider returns its stage, allowing subsequent edits during a save. Providers compose stages and explicitly dispatch pure/IO work. They must not block the native thread and must return immutable receipts/views or freeze their custom results.

An optional prerequisite gates entry, not authority. Native rights are checked again after it completes. `DocumentActions` coalesces first-use loading and preserves edit/save order while a baseline loads. An unavailable source never substitutes factory defaults or silently retries; reload is explicit.

Standard scope bounds are 64 definitions, 16 active operations and 64 retained operations. Input admission allows 64 KiB, 64 depth levels, 16,384 nodes, 32,768 string characters, 128 numeric characters and absolute decimal scale 4,096. Only terminal operations can be evicted. These local limits are independent of files, networking and domain workloads. `DocumentActions` needs room for all nineteen definitions, including identified review, backend preflight and conditional commit.

`OperationHandle` exposes immutable snapshots and a completion stage. Snapshots identify the context/target and carry revision, phase, completed count, optional total, cancellation request and result/report. Progress cannot retreat in the same phase. Unknown totals stay unknown. `COMPLETED` means the provider produced a result; its save receipt may still report refused, partial, unknown or unapplied effects.

## Cancellation and close

Explicit cancellation checks the actual scope's READ authority. Queued cancelled work does not enter its provider. Running work receives a cooperative request; the provider stops at a safe boundary or drains an admitted effect. `Execution.checkpoint()` belongs before a new effect or after stopping the provider's work, never as a claimed rollback of an outstanding save.

Scope closure refuses new work and requests cancellation. Running effects still settle their retained handles with actual results. Cancelling a future copy does not cancel the operation. Closing ordinary `DocumentActions` closes its editor/session and operation scope, not the shared context/runtime handle. Explicit `owningContext` transfers a shorter-lived context to that editor, as used by [world recipes](world-recipes.md). Native lifecycle code owns the context and borrowed executors. [Owners](owners.md) defines persistence and acknowledgement.

## Inspection and export

Validation identifies draft generation/version and saved revision. It performs complete checks and actual owner preparation without committing or publishing. READ suffices for preparation; the proposal never grants WRITE authority. Inspection separates draft, baseline, owner-declared `SourceInfo`, optional qualified effective observation and latest receipt. A displayed path is not a file capability. Native inspection does not warm gameplay caches to manufacture an effective value.

Comparison separates typed semantic changes from raw authored changes. Valid spellings such as `1` and `1.0` may differ as authored text but represent the same typed value. Invalid drafts retain diagnostics instead of reporting an empty successful comparison.

Export produces bounded in-memory **document-value JSON**, with document/schema/context/draft/source identity, byte count and SHA-256. It is not the owner's file format, a write or resource publication. Returned bytes are defensive copies. A frontend may explicitly offer copying a small value; it cannot choose an arbitrary output path or implicitly publish it.

## Identified source and value review

`DocumentActions.review(entry)` captures one actual editor observation and prepares its comparison and raw value details on the worker. `DocumentReview.target()` is a shared `DraftRevision` identifying the document/context/session/generation/version and observed source revision. Source, draft, baseline and effective details belong to that capture, even if later editing continues. They are not silently joined from separately drifting requests. Final result disclosure rechecks READ and editor/source availability on the actual owner executor; cancellation stops projection at its cooperative boundaries. A source closed during projection refuses new review material. This read-only operation performs no source reload, owner preparation, save, application or domain compilation.

Entries retain observed baseline, raw draft and optional owner-observed effective value descriptions. Collections and alternatives remain whole values; no positional alignment invents stable row identities in independently materialized effective data. Invalid, inactive, missing, null, empty and retained input stay distinct. When malformed structure cannot address the declared fields, a single whole-root entry preserves that input. This is a diagnostic representation, not the saved file format or a replacement draft.

An absent effective observation differs from an unavailable/closed handle and from a failed details capture. A previously qualified value can coexist with the owner's failed-reload report. The review retains those facts separately and never warms a native gameplay cache to manufacture a value. Source information describes the observed owner source; it does not certify that the file is unchanged now, supply a universal inheritance trace or predict future application effects. The owner remains responsible for declared policy and actual receipts.

Raw projection defaults admit 512 field entries, 65,536 visited nodes and 524,288 description characters. Source/selector/path text shares that description budget; semantic comparison has its own bounded projection using the same configured field/character ceilings. These are logical presentation bounds, separate from already retained validation data, owner callback cost, files, packets and heap bytes. Explicit exhaustion refuses the complete projection without changing the draft. The constructor overload taking `DocumentReview.Limits` changes these review limits; existing constructor overloads use the standard policy. Invalid comparisons retain diagnostics instead of claiming zero valid changes.

`DocumentReview.report()` combines source, comparison and effective-observation/capture diagnostics without hiding their separate reports. A clean draft comparison does not clear a failed source observation. The native `review` command uses that same aggregate and retains its existing recipient/context/operation feedback guards.

`applicationEffects()` retains the observation's [owner-declared application boundaries](owners.md#declared-application-boundaries). Each entry's optional `application()` is the nearest containing declaration; absence remains unknown. The full metadata and each projected entry notice share the raw-description character budget. Invalid drafts can retain their declarations without becoming saveable. These notices are not inferred from a past receipt and do not change the separately observed effective value.

The native [inspection view](presentation.md#source-and-value-inspection) borrows these services and labels stale captures. Its opt-in [submission and operation feedback](presentation.md#identified-submission-and-operation-feedback) uses the exact conditional operation below. Inspection by itself remains read-only and never promises that the current world can accept new settings.

## Complete backend preflight

`DocumentActions.preflight(entry)` captures an identified inspection and complete typed candidate, freshly reads the actual backend revision and prepares that candidate without submitting or writing. READ suffices; a prepared result grants no WRITE authority. Invalid values retain their diagnostics without constructing a temporary owner value. Final disclosure requires the same current editor target, source availability and READ authority. Backend preparation is discarded rather than retained as a future write capability. [Document batches](batches.md) combine these actual captures across owners and repeat preflight before ordered effects.

## Conditional submission

`commit(entry, reviewedRevision)` submits one typed `commit_reviewed` operation. It selects actual persistence or unsaved-context admission from the owner, rechecks WRITE and compares all six revision fields inside the actor's serialized submission-capture task. Intervening queued edits, reloads or foreign identities refuse with `editor.review_changed` before preparing a submission or calling domain compilation. A caller-time comparison of a cached GUI view is insufficient.

Once that exact submission is captured, later editing remains allowed. The existing prepared-owner checks, cooperative cancellation and actual receipt/acknowledgement path still govern completion. They preserve later edits and distinguish saved, refused, unknown and application outcomes. This guard is neither cross-process file CAS nor a future-effects declaration; actual source conflict detection and owner authority remain required. Ordinary unreviewed `save`, `admit` and `commit` retain their explicit behavior. Typed revision inputs admit complete UUIDs, nonnegative generation/version and a source-revision string up to 4,096 characters within the operation's existing input bounds.

## Native commands

`SettingsCommands.branch` returns a Brigadier literal builder for an explicit native source binding. It uses native arguments and exact declared `FieldIndex` lookup. Selectors escape literal dots, backslashes and control characters. Undeclared paths and arbitrary JSON patches are refused; lists/custom values retain a structured-editor boundary.

Commands are `describe`, `get [field]`, `set <field> <value>`, `reload`, `undo`, `redo`, `revert`, `defaults`, `validate`, `compare`, `explain`, `export`, `save`, `admit`, `apply`, `status <uuid>` and `cancel <uuid>`. Native quoting supports spaces. `set` stages a draft; saving is explicit. Context sources use explicit `admit` and refuse file `save`. Return value `1` means accepted asynchronous invocation, not completed mutation count. Bounded feedback reports persistence and application separately.

An explicitly supplied `ImportCatalog` adds the [registered import branch](imports.md#registered-native-commands). Source/format listing and native suggestions read admitted metadata without opening an editor. Preview/review and strict stage use the same attached import service; source names and review/operation UUIDs cannot grant cross-source or cross-editor authority. Import status/cancellation address that bounded review scope, not the document scope.

The [conflict branch](conflicts.md#native-commands) exposes explicit review, choices and draft-only resolution. GUI and command frontends share its admission rules. Resolution and subsequent complete validation are distinct outcomes; saving remains explicit.

Bindings resolve actual actors, provide noncreating availability and lookup, and route feedback on the current native executor. Late delivery requires the same context and actions instance. Status and cancellation look up a UUID in an existing editor's retained work. Feedback failure cannot replay an effect or change its acceptance. [Server policy commands](server-settings.md#native-server-commands) bind the same bridge to the dedicated console and current players.

`Reply.correlation()` identifies an already admitted operation and its response channel (`QUEUED`, `COMPLETED`, `STATUS` or `CANCELLATION`). Replies carrying a snapshot must agree with its actual ID and channel; the four-argument constructor derives that correlation. A late disclosure/source guard can instead return ERROR with the same correlation and no snapshot. Its bounded human message also identifies the operation/channel. An earlier admission refusal has no invented operation identity. `operationId()` provides the ID without requiring a material snapshot.

Route asynchronous replies by operation ID and response channel. Completion and cancellation can overlap for the same operation; an older request can also refuse while its successor remains pending. Neither a single-pending-request guess nor parsing visible text supplies that identity. Correlation preserves current recipient/owner/source checks and grants no authority to disclose a refused result.

Updates to Brigadier, Components, executors or lifecycle require rechecking quoting/suggestions, bounded feedback, revoked/stale recipients, cold bursts and actual receipt semantics. [Verification](verification.md) records native execution and its limits.

Long-running discovery providers use [search reporting](search-reporting.md) for stable result pages and separate native cleanup. A discovery provider supplies its own scheduler and audience lifetime.
