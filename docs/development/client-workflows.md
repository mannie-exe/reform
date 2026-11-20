# Client editing workflows

`ClientEditingWorkflow` composes independently owned client documents without another persistence layer. JSON preferences, existing files, native CLIENT/local-COMMON settings and Controls retain their actual sources, serializers, callbacks and application rules. Each document keeps one editing session while the workflow is open. Separate workflows create independent actors and retain the owners' revision and conflict checks.

## Declaration and entry

File, registered-native and keybinding factories expose `form(title)`. File/native forms also accept existing presentation declarations; file forms can borrow explicit reference-source bindings. These declarations open no editor, read no source and initialize no file. Availability suppliers must read cached local state. READY means the owner can be opened, not that its saved data has been validated or its domain effects are active.

`IntegratedSettings.form(title, route, permission, definition, options)` declares a composed form bound to the current local integrated host. Its initial availability observes the host, world, connection and exact admitted definition. Selecting the form admits a real local presentation on the server thread and borrows it for a retained native editor. `openEditor(route)` lets a composed caller await that admission without blocking the client thread. Existing immediate forms still support `editor(route)`. Host replacement, lost permission, connection closure and workflow shutdown retire the borrowed view. Installation defaults and running-world policies belong to distinct workflows even when they use the same logical document IDs.

Construct the workflow on the native client thread when entering the UI:

```java
var workflow = ClientEditingWorkflow.open(parent, title, List.of(
    preferences.form(Component.literal("Preferences")),
    keys.form(Component.literal("Controls")),
    nativeDefaults.form(Component.literal("Installation defaults"))));
Minecraft.getInstance().setScreen(workflow.screen());
```

The [ordinary addon](../../examples/native/src/main/java/example/reform/WorkflowExample.java) supplies four owners through this path. No addon executor, codec, shadow file or custom transport is needed. A consumer can use this entry in an explicitly registered native configuration factory; re:form never replaces an existing menu implicitly.

Declarations require one to 32 distinct document identities. Admission occurs before creating a managed workflow lifetime or opening any editor. A document opens lazily on selection. Search filters the declared titles and identities without querying values. Unopened rows say that saved/effective values have not been inspected. Unavailable bindings expose their actual diagnostics and explicitly admitted Retry action.

Pass a `ClientWorkflowLayout` to group navigation entries and operations by purpose. A `Placement` links a unique row to a registered route and optionally a field. Several placements can open the same retained editor at different fields. `ClientForm.atRoute` assigns a consumer-facing route independently of the actual definition identity. The workflow opens one editor per owner and checks the real definition before accepting a view. `DocumentState.ownerDocument()` exposes that identity alongside the route.

`ClientFormOptions<T>` supplies the same catalog, reference bindings, auxiliary field editors and optional import declaration for owned files, registered native owners, typed integrated policies and creation recipes. Editor and import factories receive the actual definition when the form opens. The integrated entry checks the current host, route and exact definition before creating an editor. These options add presentation to the actual owner and leave its persistence and application rules with that owner.

Each `ReviewScope` declares exactly which routes can write and which supply unchanged dependencies. A clean writable member is checked, and a dirty writable member enters the reviewed order. Mark a scope required to route its generated forms' save controls and keyboard shortcuts through that review. No other layout scope can submit those documents. A scoped workflow asks the user to choose a scope instead of opening all sources. Domain validation, additional guards and ordered writes use the same `DocumentBatch` service. Contributions can supply a native operation handle with actual authority and a bounded result projection for the shared operation view. Browsing contributes no effects, and Back retains the operation result.

`Group(path, placements, actions)` declares a hierarchy of stable `Category` IDs, native captions and help. The overview follows the path through up to four levels, supplies breadcrumbs and searches localized captions and help across categories. An explicit layout admits up to 128 groups and 512 placements. Multiple placements still share the same actual editor. All, Modified, Invalid and Restart use cached edit/diagnostic/receipt state or caller-declared timing. Field placements use their captured field baseline and relevant diagnostics. Filtering and returning from editors retain the current browse location and query. Neither list construction nor filtering loads a source or opens an owner.

## Retained editors and navigation

`editor(document)` returns the same `ClientEditor<?>` for repeated calls in this workflow. Its generated `screen()` and typed `actions()` refer to one actual session. The view uses those same operations for validation, history, inspection, submission and recovery. `ClientEditor.retain(settingsScreen)` lets an advanced client retain a newly constructed generated form; ownership transfers only from an inactive view. The caller must close that retained editor. The document/runtime itself remains borrowed.

`show(document)` navigates from this workflow's active overview to its retained form. Back returns to the same overview without closing that editor. Raw invalid drafts, native key popup changes, undo/redo and unaccepted local input belong to the retained view/session. Normal Back waits for pending input or reload, while a submitted save may continue under that same retained view. The overview reports each actual pending operation and receipt separately.

Opening another workflow does not share the first workflow's draft. A competing save must still pass actual owner preflight; a managed-owner revision refusal is distinct from a source conflict that has an available manual review. The overview displays observation and operation diagnostics rather than inferring success from a clean validation report or an earlier receipt.

An ended editor is never silently replaced. Explicit `discard(document)` releases that editor before a later selection creates a fresh session. This operation refuses a known pending effect. It does not undo completed persistence or restore a closed source context. Hard source lifetimes remain with their owners; the workflow does not fabricate a replacement context or values.

## Saving and closure

Each document submits through its existing owner. Saving Controls still writes Options; saving a registered native document still uses FML's serializer and callbacks. Saving one document neither saves nor clears another draft. Saved, effective, staged, refused and unknown results retain their existing meanings.

Normal workflow exit waits for known pending work and unreconciled results. This includes queued work started through the public actions API, using cached operation-scope accounting. Discard decisions check pending work again when confirmed; displaying a decision grants no later permission to retire an active editor. Retained drafts require an explicit Review changes, Discard draft or Keep editing decision. Review enters the explicit combined preflight described below; the decision itself performs no write. Per-document discard has its own decision in the overview. Previously completed writes remain completed.

The workflow owns editor lifetimes only. Closing it releases every entered editor, including after unexpected navigation away from a child or decision view. Actual game shutdown also closes managed workflows, even if their overview was never displayed. Forced `close()` is lifetime cleanup, not rollback or a promise to cancel an effect already entered. Owner shutdown and operation reconciliation retain their existing guarantees.

## Combined review and ordered saving

Review all documents explicitly enters every declared owner, including rows hidden by search. It uses the same retained actors, checks clean documents without writing and selects edited documents for ordered submission. Unavailable, pending or conflicting owners cannot silently disappear from the reviewed vector. Acknowledged form input reconciles edits accepted through another view/service; genuinely refused local text remains retained and must be retried or explicitly reverted before combined review. The overload of `open` accepting `DocumentBatch.Validator` adds pure complete-candidate checks; typed values are selected through the actual registered definition instance. The default has no additional domain constraints.

The native batch review retains its exact captured values, source and application metadata. Inspect fields opens that capture through the shared inspection view with backend and cross-document diagnostics; returning retains the batch and editor history. Later edits invalidate submission. Refresh repeats complete preflight. Save reviewed documents repeats backend checks and current authority before writing, then retains each owner's result in the displayed order. Clean Controls do not write Options. There is no cross-file atomicity or global source lock.

Submission result presents separate saved, staged, refused, unattempted and unknown outcomes through the shared operation view. Back leaves admitted work running; cancellation is explicit and drains entered writes. The overview retains the last batch review/result until a new review replaces it. Pending batch work prevents ordinary workflow or per-document discard; forced lifetime closure keeps actual effect results and offers no rollback. Reconciliation and application-only recovery remain with the relevant document.

[Document batches](batches.md) owns the service contracts and bounds. [Verification](verification.md) records the exact native evidence and its limits.
