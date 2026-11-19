# Client editing workflows

`ClientEditingWorkflow` composes independently owned client documents without another persistence layer. JSON preferences, existing files, native CLIENT/local-COMMON settings and Controls retain their actual sources, serializers, callbacks and application rules. Each document keeps one editing session while the workflow is open. Separate workflows create independent actors and retain the owners' revision and conflict checks.

## Declaration and entry

File, registered-native and keybinding factories expose `form(title)`. File/native forms also accept existing presentation declarations; file forms can borrow explicit reference-source bindings. These declarations open no editor, read no source and initialize no file. Availability suppliers must read cached local state. READY means the owner can be opened, not that its saved data has been validated or its domain effects are active.

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

## Retained editors and navigation

`editor(document)` returns the same `ClientEditor<?>` for repeated calls in this workflow. Its generated `screen()` and typed `actions()` refer to one actual session. The view uses those same operations for validation, history, inspection, submission and recovery. `ClientEditor.retain(settingsScreen)` lets an advanced client retain a newly constructed generated form; ownership transfers only from an inactive view. The caller must close that retained editor. The document/runtime itself remains borrowed.

`show(document)` navigates from this workflow's active overview to its retained form. Back returns to the same overview without closing that editor. Raw invalid drafts, native key popup changes, undo/redo and unaccepted local input belong to the retained view/session. Normal Back waits for pending input or reload, while a submitted save may continue under that same retained view. The overview reports each actual pending operation and receipt separately.

Opening another workflow does not share the first workflow's draft. A competing save must still pass actual owner preflight; a managed-owner revision refusal is distinct from a source conflict that has an available manual review. The overview displays observation and operation diagnostics rather than inferring success from a clean validation report or an earlier receipt.

An ended editor is never silently replaced. Explicit `discard(document)` releases that editor before a later selection creates a fresh session. This operation refuses a known pending effect. It does not undo completed persistence or restore a closed source context. Hard source lifetimes remain with their owners; the workflow does not fabricate a replacement context or values.

## Saving and closure

Each document submits through its existing owner. Saving Controls still writes Options; saving a registered native document still uses FML's serializer and callbacks. Saving one document neither saves nor clears another draft. Saved, effective, staged, refused and unknown results retain their existing meanings.

Normal workflow exit waits for known pending work and unreconciled results. This includes queued work started through the public actions API, using cached operation-scope accounting. Discard decisions check pending work again when confirmed; displaying a decision grants no later permission to retire an active editor. Retained drafts require an explicit Review changes, Discard draft or Keep editing decision. Review returns to a dirty document; it performs no hidden batch write. Per-document discard has its own decision in the overview. Previously completed writes remain completed.

The workflow owns editor lifetimes only. Closing it releases every entered editor, including after unexpected navigation away from a child or decision view. Actual game shutdown also closes managed workflows, even if their overview was never displayed. Forced `close()` is lifetime cleanup, not rollback or a promise to cancel an effect already entered. Owner shutdown and operation reconciliation retain their existing guarantees.

This baseline does not supply multi-document preflight, domain-wide candidate invariants, ordered commits or cross-file atomicity. Review and ordered submission across owners remain separate Phase 1 work; the retained-session API is their common editing foundation. [Verification](verification.md) records the exact native evidence and its limits.
