# Shared local operations

`DocumentActions<T>` binds one admitted `EditorActor<T>` to typed services shared by native forms and commands. It receives the real `OwnerContext` and its actual `Access`. Field selectors, operation UUIDs and document names grant no authority. The [client JSON factory](client-json.md) supplies this wiring for ordinary client preferences; other owners can construct it without replacing their parser, serializer or application policy.

The current services cover reload, cached inspection, raw scalar editing, undo/redo, revert/defaults, validation, source/effective inspection, semantic comparison, value export, save and application-only retry. GUI and command editors retain separate drafts over the same shared owner. Recursive controls, full operation presentation, conflict/import workflows, domain operations and migrations remain required by [coverage](coverage.md).

## Registration and execution

`OperationDescriptor<I, O>` declares a typed input, required right, effect labels, entry points and optional logical target. Explicitly register handlers in an actor-bound `OperationScope`, then freeze registration. Submission checks the exact registered descriptor instance and fresh authority; matching names do not make a foreign descriptor a capability. Effect labels describe intent, not completed effects.

Inputs remain immutable during admission. Workers freeze, encode and strictly decode against the declared type and scope limits. Pure preparation can run in parallel. Providers enter in submission order on the native executor; that lane releases when the provider returns its stage, allowing subsequent edits during a save. Providers compose stages and explicitly dispatch pure/IO work. They must not block the native thread and must return immutable receipts/views or freeze their custom results.

An optional prerequisite gates entry, not authority. Native rights are checked again after it completes. `DocumentActions` coalesces first-use loading and preserves edit/save order while a baseline loads. An unavailable source never substitutes factory defaults or silently retries; reload is explicit.

Standard scope bounds are 64 definitions, 16 active operations and 64 retained operations. Input admission allows 64 KiB, 64 depth levels, 16,384 nodes, 32,768 string characters, 128 numeric characters and absolute decimal scale 4,096. Only terminal operations can be evicted. These local limits are independent of files, networking and domain workloads. `DocumentActions` needs room for all fifteen definitions.

`OperationHandle` exposes immutable snapshots and a completion stage. Snapshots identify the context/target and carry revision, phase, completed count, optional total, cancellation request and result/report. Progress cannot retreat in the same phase. Unknown totals stay unknown. `COMPLETED` means the provider produced a result; its save receipt may still report refused, partial, unknown or unapplied effects.

## Cancellation and close

Explicit cancellation checks the actual scope's READ authority. Queued cancelled work does not enter its provider. Running work receives a cooperative request; the provider stops at a safe boundary or drains an admitted effect. `Execution.checkpoint()` belongs before a new effect or after stopping the provider's work, never as a claimed rollback of an outstanding save.

Scope closure refuses new work and requests cancellation. Running effects still settle their retained handles with actual results. Cancelling a future copy does not cancel the operation. Closing ordinary `DocumentActions` closes its editor/session and operation scope, not the shared context/runtime handle. Explicit `owningContext` transfers a shorter-lived context to that editor, as used by [world recipes](world-recipes.md). Native lifecycle code owns the context and borrowed executors. [Owners](owners.md) defines persistence and acknowledgement.

## Inspection and export

Validation identifies draft generation/version and saved revision. It performs complete checks and actual owner preparation without committing or publishing. READ suffices for preparation; the proposal never grants WRITE authority. Inspection separates draft, baseline, owner-declared `SourceInfo`, optional qualified effective observation and latest receipt. A displayed path is not a file capability. Native inspection does not warm gameplay caches to manufacture an effective value.

Comparison separates typed semantic changes from raw authored changes. Valid spellings such as `1` and `1.0` may differ as authored text but represent the same typed value. Invalid drafts retain diagnostics instead of reporting an empty successful comparison.

Export produces bounded in-memory **document-value JSON**, with document/schema/context/draft/source identity, byte count and SHA-256. It is not the owner's file format, a write or resource publication. Returned bytes are defensive copies. A frontend may explicitly offer copying a small value; it cannot choose an arbitrary output path or implicitly publish it.

## Native commands

`SettingsCommands.branch` returns a Brigadier literal builder for an explicit native source binding. It uses native arguments and exact declared `FieldIndex` lookup. Selectors escape literal dots, backslashes and control characters. Undeclared paths and arbitrary JSON patches are refused; lists/custom values retain a structured-editor boundary.

Commands are `describe`, `get [field]`, `set <field> <value>`, `reload`, `undo`, `redo`, `revert`, `defaults`, `validate`, `compare`, `explain`, `export`, `save`, `admit`, `apply`, `status <uuid>` and `cancel <uuid>`. Native quoting supports spaces. `set` stages a draft; saving is explicit. Context sources use explicit `admit` and refuse file `save`. Return value `1` means accepted asynchronous invocation, not completed mutation count. Bounded feedback reports persistence and application separately.

Bindings resolve actual actors, provide noncreating availability/lookup and route feedback on the current native executor. Late delivery requires the same context and actions instance. A UUID looks up only that scope's retained work. Feedback failure cannot replay an effect or change its acceptance. The generic bridge introduces no command root, server gateway or remote protocol.

Updates to Brigadier, Components, executors or lifecycle require rechecking quoting/suggestions, bounded feedback, revoked/stale recipients, cold bursts and actual receipt semantics. [Verification](verification.md) records native execution and its limits.
