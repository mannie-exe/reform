# Client editing workflows

`ClientEditingWorkflow` composes independently owned client documents without another persistence layer. JSON preferences, existing files, native CLIENT/local-COMMON settings and Controls retain their actual sources, serializers, callbacks and application rules. Each document keeps one editing session while the workflow is open. Separate workflows create independent actors and retain the owners' revision and conflict checks.

## Declaration and entry

File, registered-native and keybinding factories expose `form(title)`. File/native forms also accept existing presentation declarations; file forms can borrow explicit reference-source bindings. These declarations open no editor, read no source and initialize no file. Availability suppliers must read cached local state. READY means the owner can be opened, not that its saved data has been validated or its domain effects are active.

`IntegratedSettings.form(title, route, permission, definition, options)` declares a composed form bound to the current local integrated host. Its initial availability observes the host, world, connection and exact admitted definition. Selecting the form admits a real local presentation on the server thread and borrows it for a retained native editor. `openEditor(route)` lets a composed caller await that admission without blocking the client thread. Existing immediate forms still support `editor(route)`. Host replacement, lost permission, connection closure and workflow shutdown retire the borrowed view. Installation defaults and running-world policies belong to distinct workflows even when they use the same logical document IDs.

The shared root remains visible while asynchronous admission is pending. Its presence does not mean that the selected editor exists yet. Await `openEditor(route)`, or observe `DocumentState.opened()` before requesting that retained editor. Actor readiness and the mounting of a particular native field remain separate checks. A synchronous `editor(route)` call cannot replace pending host admission with an empty editor or block the client thread.

Construct the workflow on the native client thread when entering the UI:

```java
var workflow = ClientEditingWorkflow.open(parent, title, List.of(
    preferences.form(Component.literal("Preferences")),
    keys.form(Component.literal("Controls")),
    nativeDefaults.form(Component.literal("Installation defaults"))));
Minecraft.getInstance().setScreen(workflow.screen());
```

The [ordinary addon](../../examples/native/src/main/java/example/reform/WorkflowExample.java) supplies four owners through this path. No addon executor, codec, shadow file or custom transport is needed. A consumer can use this entry in an explicitly registered native configuration factory; re:form never replaces an existing menu implicitly.

### Workspace shell in 0.4 development

The development workflow defaults to Home. Mounting Home lists declared top-level destinations without opening their owners. Select a destination, call `show(route)` or `showPlacement(id)`, or declare an explicit initial destination before expecting that document's controls to be mounted. `openEditor(route)` admits an editor; it does not navigate the body. Standalone settings screens continue to open their document directly.

The presentation overload accepts `ClientWorkspacePresentation` alongside forms and their layout. `WorkspaceIdentity` supplies a stable identity, title, and optional subtitle. `HeaderComposition` declares ordered rows containing identity, location, optional subtitle, and optional cached canvas slots. Standard header allocation depends on those offers and the current viewport/font, not the selected location's caption. A location that exceeds its reserved offer opens an ancestor menu with the full path available in narration and a tooltip. Overflowing fixed chrome enters the scrolling content in its declared reading order.

`HomePage` can name featured declared destinations; `withInitialDestination` can bypass Home deliberately, and `withoutHome` omits it. `validate(forms, layout)` checks these declarations without invoking canvas or owner factories. Opening repeats that admission before constructing managed lifetimes. Missing runtime dependencies remain availability states rather than missing declarations.

`showHome()` retains open editors and their drafts. Home has no previous document's Save or Document actions control. Its cached status distinguishes opened owners from unsaved drafts; review and retained operation results continue through their existing services.

An optional `ClientHeaderCanvas` is constructed once for the managed shell and closed at workflow retirement. Its rendering callback must use cached content; it must not perform source IO, decode images, compile resources, or simulate entities. A failed optional canvas leaves settings available and reports the display failure in its reserved region. Expensive previews use the separately owned [preview host](previews.md), not a header render callback.

The scoped canvas factory receives a `ClientViewContext`. Register partial-resource cleanup before allocation with `onClose`; the library attempts every registered cleanup if construction fails or the view retires. The shell closes the returned canvas before those resource hooks. A cleanup failure is retained with the original construction cause and does not skip later hooks. `requireCurrent` guards ordinary activation; `checkNativeThread` remains usable during cleanup after the context has closed to new work. The context supplies no document access, persistence operation, executor, or rendering capability. The supplier convenience is for cached canvases that do not construct partially owned resources.

These are development APIs. Complete theme propagation, typed navigation, responsive task surfaces, and their final combined qualification remain selected 0.4 work. Header role colors alone do not establish that every form or auxiliary screen supports a workflow theme. [Verification](verification.md) owns qualified artifacts and limits.

### Typed destination links

`workflow.navigation()` provides `ClientNavigation` over that workflow's retained owners and history. `ClientDestination` distinguishes Home, a declared category, a document route, a placement alias, an actual field, a presentation section, a declared operation, and an exact retained operation result. A section uses `FormTarget.Section`, not an invented settings path. A field carries the actual `FieldPath`, including stable collection-row identities. An optional placement identity keeps a field or section link in its declared alias context.

`inspect(target)` returns cached `DestinationAvailability`: ready, unopened, pending, unavailable, missing, or closed. It does not open an owner, load a file, validate a candidate, or acquire authority. An unopened section can remain unknown until its actual model is projected. Cached readiness is never permission to reveal data or enter an effect.

`open(target)` returns a `NavigationAttempt` with its own UUID, the exact requested target, and a completion stage. `result()` is empty while admission is pending. A terminal result retains its outcome, structured report, and actual failure cause. These identities are local presentation receipts, not owner operation IDs, permission tokens, or persisted settings.

Opening an unopened document or field requests the existing owner's admission and initial READ load on the client thread. A ready retained editor uses `DocumentActions.requireInspection()` to recheck its actual READ authority and baseline, then reveals immediately without another source read or operation submission. The prior page remains selected while that work is pending. A newer destination supersedes the older attempt; an admitted late editor may remain in the retained-owner cache but cannot replace the newer page. A failed admission preserves the origin and reports the actual refusal. Missing fields and removed stable rows never resolve by a display index or similarly named field. Source/context failures remain refusals rather than being rewritten as missing data.

An operation link reveals the declared effect without invoking it. An operation-result link must name the UUID of a currently retained handle and satisfy its observation policy. It never starts or repeats that effect; a successful external screen request is `HANDED_OFF`, not proof that the result rendered. Explicit effect buttons continue through the existing operation scope.

A request made during mounted view ticks is coalesced until their queued rebuilds settle. Actual admission and target existence are rechecked before selection; `REVEALED` completes only after the target has mounted. A newer selection supersedes the earlier one, and context closure clears deferred selection.

These development links do not yet implement all selected 0.4 consumer-view, optional-viewer, bookmark, and responsive task contracts. Native evidence and exact candidate identities belong in [verification](verification.md).

Declarations require one to 32 distinct document identities. Admission occurs before creating a managed workflow lifetime or opening any editor. A document opens lazily when its destination or a page containing its fields is selected. Search combines declared titles and identities with admitted field metadata from already opened owners, without opening or reading unselected sources. Unopened rows say that saved/effective values have not been inspected. Unavailable bindings expose their actual diagnostics and explicitly admitted Retry action.

Pass a `ClientWorkflowLayout` to group navigation entries and operations by purpose. A `Placement` links a unique row to a registered route and optionally a field. Several placements can open the same retained editor at different fields. `ClientForm.atRoute` assigns a consumer-facing route independently of the actual definition identity. The workflow opens one editor per owner and checks the real definition before accepting a view. `DocumentState.ownerDocument()` exposes that identity alongside the route.

`ClientFormOptions<T>` supplies the same catalog, reference bindings, auxiliary field editors and optional import declaration for owned files, registered native owners, typed integrated policies and creation recipes. Editor and import factories receive the actual definition when the form opens. The integrated entry checks the current host, route and exact definition before creating an editor. These options add presentation to the actual owner and leave its persistence and application rules with that owner.

Each `ReviewScope` declares exactly which routes can write and which supply unchanged dependencies. A clean writable member is checked, and a dirty writable member enters the reviewed order. Mark a scope required to route its generated forms' save controls and keyboard shortcuts through that review. No other layout scope can submit those documents. A scoped workflow asks the user to choose a scope instead of opening all sources. Domain validation, additional guards and ordered writes use the same `DocumentBatch` service. Contributions can supply a native operation handle with actual authority and a bounded result projection for the shared operation view. Browsing contributes no effects, and Back retains the operation result.

`Group(path, placements, actions)` declares up to four levels of stable `Category` IDs, captions and help. The workspace keeps that hierarchy in a searchable expandable tree. An explicit layout admits up to 128 groups and 512 placements. A selected logical page can combine fields or complete subtrees from several actual owners. Equal field paths across owners remain distinct; aliases and overlapping ancestor/child placements mount one retained setting once. All, Modified, Invalid and Restart use cached draft/diagnostic/receipt state or caller-declared timing. Navigation metadata and search do not open unselected sources.

The native tree uses the neutral `DestinationGraph<K, T>` for complete node, parent, duplicate, cycle, and depth admission. Its copied nodes preserve authored order; ancestry comes from declared parents, independently of visit history. A graph can require one declared root, while existing direct-entry workspaces retain their forest without manufacturing Home. Keys and cached target payloads keep their existing types; graph construction never invokes them. The workflow's static budget includes every category prefix: 128 groups at four levels, 512 placements, and Home can require 1,025 destinations. Already-open owners contribute their separate bounded catalogue and scalar-search projections. This shared topology is not yet the complete 0.4 typed navigation target/open API.

## Retained editors and navigation

`editor(document)` returns the same `ClientEditor<?>` for repeated calls in this workflow. Its `form()` and typed `actions()` refer to one actual session. `screen()` returns the actual native entry: a standalone screen or this workflow's shared root. The view uses those same operations for validation, history, inspection, submission and recovery. `ClientEditor.retain(settingsScreen)` lets an advanced client retain a newly constructed generated form; ownership transfers only from an inactive view. The caller must close that retained editor. The document/runtime itself remains borrowed.

A custom submission action must check `ClientEditor.canSubmit()` on the client thread immediately before invoking the actual document commit. This cached presentation guard refuses unaccepted native text, pending edits/operations, invalid drafts, unresolved conflicts and owners requiring their built-in review scope. A valid dirty actor alone is insufficient: rejected widget input can coexist with a valid sibling edit. The guard performs no flush, source read or authorization and does not replace the commit's backend checks. Owner-specific controls and domain effects retain their own preflight. Use the conditional commit with the reviewed `DraftRevision` when the action promises to submit an earlier reviewed capture. Later edits retain their normal meaning.

`show(document)` and `showPlacement(id)` select and reveal content within the active native workspace. Ordinary browsing keeps that root screen, each actual draft and its history. Native key capture, imports and other complex editors remain explicit auxiliary views; their root handoff retains every participating owner. Raw invalid drafts and unaccepted local input survive a field becoming hidden. A submitted save can continue while the same workflow stays open.

All, Modified, Invalid and Restart filters use the same cached owner state for navigation entries and their mounted fields. Unmatched aliases are hidden rather than leaving destinations whose settings disappeared. Filtering neither opens unselected owners nor changes a draft.

Back and Forward traverse bounded requested navigation identities within that root. Returning to a page restores its retained viewport, field focus and current owner action scope. Moving focus into navigation does not discard the last field identity; restoring it still requires a current available control. A new destination after Back replaces the forward branch without changing any draft or edit history. Navigation and editing history are separate.

Use `workflow.screen()` or the retained editor's `screen()` as the native entry point. Use `ClientEditor.form()` for its `SettingsForm<T>`: the actual retained fields, cached state and operations, independently of native screen ownership. It is a form interface rather than another `Screen`. Standalone `SettingsScreen<T>` also implements that interface. Current field/control accessors operate through the actual mounted host; hidden widgets are not returned. `NativeScreens.open` leaves an already current non-null entry in place, preserving the workflow and its actors.

Opening another workflow does not share the first workflow's draft. A competing save must still pass actual owner preflight; a managed-owner revision refusal is distinct from a source conflict that has an available manual review. The overview displays observation and operation diagnostics rather than inferring success from a clean validation report or an earlier receipt.

An ended editor is never silently replaced. Explicit `discard(document)` releases that editor before a later selection creates a fresh session. This operation refuses a known pending effect. It does not undo completed persistence or restore a closed source context. Hard source lifetimes remain with their owners; the workflow does not fabricate a replacement context or values.

## Saving and closure

Each document submits through its existing owner. Saving Controls still writes Options; saving a registered native document still uses FML's serializer and callbacks. Saving one document neither saves nor clears another draft. Saved, effective, staged, refused and unknown results retain their existing meanings.

Normal workflow exit waits for known pending work and unreconciled results. This includes queued work started through the public actions API, using cached operation-scope accounting. Discard decisions check pending work again when confirmed; displaying a decision grants no later permission to retire an active editor. Retained drafts require an explicit Review changes, Discard draft or Keep editing decision. Review enters the explicit combined preflight described below; the decision itself performs no write. Per-document discard is available through that owner's secondary actions and has its own decision. Previously completed writes remain completed.

The workflow owns editor lifetimes only. Closing it releases every entered editor, including after unexpected navigation away from a child or decision view. Actual game shutdown also closes managed workflows, even if their overview was never displayed. Forced `close()` is lifetime cleanup, not rollback or a promise to cancel an effect already entered. Owner shutdown and operation reconciliation retain their existing guarantees.

## Combined review and ordered saving

Review all documents explicitly enters every declared owner, including rows hidden by search. It uses the same retained actors, checks clean documents without writing and selects edited documents for ordered submission. Unavailable, pending or conflicting owners cannot silently disappear from the reviewed vector. Acknowledged form input reconciles edits accepted through another view/service; genuinely refused local text remains retained and must be retried or explicitly reverted before combined review. The overload of `open` accepting `DocumentBatch.Validator` adds pure complete-candidate checks; typed values are selected through the actual registered definition instance. The default has no additional domain constraints.

The native batch review retains its exact captured values, source and application metadata. Inspect fields opens that capture through the shared inspection view with backend and cross-document diagnostics; returning retains the batch and editor history. Later edits invalidate submission. Refresh repeats complete preflight. Save reviewed documents repeats backend checks and current authority before writing, then retains each owner's result in the displayed order. Clean Controls do not write Options. There is no cross-file atomicity or global source lock.

Submission result presents separate saved, staged, refused, unattempted and unknown outcomes through the shared operation view. Back leaves admitted work running; cancellation is explicit and drains entered writes. The overview retains the last batch review/result until a new review replaces it. Pending batch work prevents ordinary workflow or per-document discard; forced lifetime closure keeps actual effect results and offers no rollback. Reconciliation and application-only recovery remain with the relevant document.

[Document batches](batches.md) owns the service contracts and bounds. [Verification](verification.md) records the exact native evidence and its limits.
