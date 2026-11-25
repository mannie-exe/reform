# Connected authoring examples

These are proposed Java-like contract recipes, not current imports, executable prototypes or new consumer bindings. They deliberately use the same [API shapes](api.md), ownership and error paths. Boilerplate such as defensive Component copies is omitted. Domain variables below are explicit caller-supplied inputs, not services discovered by the UI.

Helper families name the following façade responsibilities: `Forms.generated(editor, options)` adapts existing generated forms; `Bindings.field(editor,path)`/`Bindings.local(cell)` expose guarded bindings; `Pickers.open(scope,editor,source,referenceLimits,uiLimits)` constructs one shared controller; `Previews.open(scope,editor,spec)` borrows the existing graphics host; `Observers.operation(scope,handle,readPolicy)` observes an admitted actual handle; `UiCommands.fromLocal(cell,projection)` creates only a pure local-state command. None supplies IO, another owner or a private renderer. The implementation can merge façade names; these responsibilities and relationships must remain.

## E1: simple generated settings form

Inputs: an already admitted ClientEditor<Settings>, exact existing definition/presentation and host limits. It is the same owner used by Configure, aliases, commands and any auxiliary views.

```java
UiComponent<ClientEditor<Settings>> settings = (scope, editor) ->
    Forms.generated(editor, options)
        .key("appearance-form")
        .theme(presentationTheme);

ViewSpec body = Views.component(appearanceKey, settings, retainedEditor);
```

Generated form controls adapt existing FieldBindings, numeric domains, raw input, help/issues and document actions. Home lazily references the destination and does not instantiate this component/editor. Entry structurally admits the declaration, mounts native controls, resolves focus, then acknowledges REVEALED. Reflow changes arrangement using the same view/owner/input state. Save still names the actual document and submits a synchronized whole candidate. No state is initialized from an arbitrary widget default.

Expected sequence: enter Appearance → toggle Enabled → navigate to Home → return → same draft and Undo history; explicit Save → actual saved/application receipts. Missing/unavailable owner is a useful destination/refusal, not a substituted default or accidental first-file initialization.

## E2: embedded Models picker

Inputs: the same retained settings editor; cached profile ReferenceActions; exact Models FieldPath; the existing domain provider opted into the new preview-basis contract. Metadata counts do not determine eligibility.

```java
UiComponent<ClientEditor<Settings>> models = (scope, editor) -> {
  var role = new PickerRole<Settings>("models", modelsPath,
      text("Models"), text("Use for Models"));
  var picker = Pickers.open(scope, editor, profileSource, referenceLimits, uiLimits);
  var preview = Previews.open(scope, editor, draftPreviewSpec);
  return Views.section("models-picker", text("Models"), List.of(
      picker.inline(role, compactPickerPresentation),
      preview.content()));
};
```

The generated field's disclosure trigger expands this contribution; it does not navigate to another Screen. `compactPickerPresentation` contains meaningful purpose, preferredVisibleRows4, automatic search200ms, manual completion and list offers. It shares the library's source/controller behavior with the full task. The optional preview contribution borrows the same host/aggregate ledger and complete candidate.

Sequence: expand → type query → latest current page appears; arrows inspect Vanilla while actual Models remains Default; click/Enter accepts Vanilla → one guarded Models edit → captured complete draft, including current Artwork → automatic preview request. Artwork is not changed. Source replacement between inspecting and accepting yields stale/refused, preserving query/raw text and the draft. A resolved empty/inherited profile remains selectable; unavailable catalogue is a separate state.

Initial field acceptance counts as an explicit relevant preview gesture and can activate its automatic policy even without a prior Prepare. Merely expanding/searching cannot prepare. This resolves the first-interaction behavior without turning all form typing into implicit compilation.

## E3: Profiles audition, factored subject and retained camera

Inputs: one admitted settings owner, the same profile source, Models and Artwork roles, the existing complete-domain provider and its subject ValueType. The following domain mapping is supplied by Lootr, not inferred by the library.

```java
UiComponent<ClientEditor<Settings>> profiles = (scope, editor) -> {
  var picker = Pickers.open(scope, editor, profileSource, referenceLimits, uiLimits);
  var subject = scope.local(subjectStateKey, initialBarrelClosed);
  var role = scope.local(auditionRoleKey, modelsRole);
  var subjects = SubjectForms.of(subjectType, subject, List.of(
      select("container", text("Container"), containerLens, containerChoices),
      checkbox("looted", text("Looted"), lootedLens),
      checkbox("open", text("Open"), openPoseLens)));
  var preview = Previews.open(scope, editor,
      previewSpec.withSubjects(subjects)
          .withBasis(picker.auditionOrDraft(role)));
  return Browsers.profileTask(picker, role, List.of(modelsRole, artworkRole),
      preview, taskPurpose);
};
```

`SubjectForms.of` composes local Select/Checkbox controls through typed pure lenses and freezes/validates the whole subject ValueType after an accepted gesture. The openPoseLens maps barrel Open to its boolean and chest Open to native lid0/1 while preserving other flags. It does not add a settings field. `Browsers.profileTask` is the standard shared browser container over the declared parts, not an arbitrary consumer layout/renderer. `auditionOrDraft` supplies a requested basis, never mutates candidate bytes inside an old DRAFT request.

Sequence: explicit Prepare current draft → rotate camera; inspect profile P for Models → capture unchanged base R plus resolved P/source stamp/role → validate ephemeral candidate → prepare current audition; rotate is retained. Switch to Artwork → different explicit overlay/basis; no settings edits. Toggle Open/Looted/container → coalesced latest request with the same base/role; all twelve combinations remain expressible. Use for Models → one actual guarded field edit/history entry, then basis refresh. Save is still separate.

Changing content bounds refits framing scale while retaining orbit/zoom. Reset view deliberately uses current provider defaults. Empty scenes are READY empty. Unsupported tint/pose is narrated and displayed with useful recovery; choosing Default and explicitly/automatically preparing a current basis recovers without a save. No source/global resource-manager mutation occurs.

## E4: invalid retained raw input and valid sibling

The ordinary generated field adapts the same existing native raw-input buffer, not a new Local<String> draft:

```java
var models = Bindings.field(retainedEditor, modelsPath);
var enabled = Bindings.field(retainedEditor, enabledPath);
ViewSpec body = Views.column("styling", List.of(
    Fields.bound(enabled, enabledParts),
    Fields.bound(models, modelsPartsWithCompletion),
    preview.content()));
```

`Fields.bound` is the standard intrinsic Field/Control wrapper from [API anatomy](api.md), using label/error/description and the field's actual edits. `modelsPartsWithCompletion` augments its existing UiInputBinding, not substitutes another raw value.

Sequence: set Enabled to a valid different value; type malformed Models text and retain native selection/caret; native input epoch changes even if the actor refuses that text. Save/preview readiness explains invalid/unaccepted input. Resize/reflow/filter or open information → exact text, caret, selection and sibling draft remain. A preview capture at old actor revision R but new native epoch E is refused; it cannot render R as the current draft. Explicit native repair → synchronize/validate complete candidate → current preview can prepare. No implicit file/default fallback or loss of sibling edit.

Accepted-but-domain-invalid text uses the actor's invalid draft and report. Rejected-before-actor text uses the host input guard; both need distinct evidence. Focus or disclosure changes alone cannot erase either. A collapsed ID editor retains a linked visible error summary and restores its trigger focus.

## E5: dynamic keyed collection with row movement

Inputs: the actor's current admitted collection projection exposes stable DraftRow IDs and row field bindings. Visible map keys/labels are not the identity.

```java
Read<List<RowView>> rows = collectionRows; // actor/source observation, not a file read
ViewSpec list = Views.forEach("rules", rows, RowView::draftRowId,
    (rowKey, rowRead, indexRead, rowScope) ->
        Forms.row(rowRead.map(labelProjection, RowView::label),
                  rowBindings.forStableRow(rowKey), rowOptions));
```

`Forms.row` composes existing field controls; `rowBindings.forStableRow` resolves the current target for that actual stable row at activation. It never stores a constructor-time numeric index. Derived labels/indexes update without replacing the retained row control. Duplicate row keys refuse the new vector before reconciliation.

Sequence: row A has incomplete text and focus; move A after B → same logical A raw input/focus/history, new index projection; remove A → its native attachment/actions retire, focus reveals nearest enabled neighbor without changing accepted values; Undo restores A with the actual editor-provided row identity and saved input bookmark if still retained by policy. A late callback to the old attachment is STALE, even if A is now mounted again. Source-file structural merge does not reuse these editor UUIDs as proof of persistent row identity.

For a large collection, virtualization keeps a bounded realized set plus pinned focus/active editors and state by key. Scrolling must never silently parse/commit invalid text just to recycle a row. If retention admission is exhausted, show a useful refusal or retain the required active row; do not discard it.

## E6: async replacement, cancellation, and closure

The preview controller delegates actual requests to the existing graphics host. This example specifies its event/result contract rather than a new executor:

```java
preview.request(currentBasisA);     // identified attempt A enters existing lane
preview.request(newerBasisB);       // cancel A, keep one latest B demand
preview.request(newestBasisC);      // replace unentered B; no extra entered work
preview.cancel();                   // clear C and request A cancellation once
```

These are explicit controller commands equivalent to its typed UiCommands; calls occur from native input/update, never build/paint. Readiness and actual admission remain separate.

| Event                                              | Entered/pending              | Published result and accounting                                                             |
| -------------------------------------------------- | ---------------------------- | ------------------------------------------------------------------------------------------- |
| A entered and blocked in actual CPU/native stage   | A / none                     | Old reservations remain charged.                                                            |
| B then C requested                                 | A cancelling / C only        | B never enters. No premature success/slot release.                                          |
| Cancel                                             | A cancelling / none          | Cancel disabled while draining; status retains actual cause/request.                        |
| A exits ignoring cancellation until its checkpoint | none / none                  | Obsolete output disposed, outcome CANCELLED/stale; no mount.                                |
| Explicit D request after drain                     | D / none                     | Current D may prepare/draw; orbit retained.                                                 |
| View closes with one actual D submitted use        | none / none; closed interest | No new input/publication; lease retained until actual use completion, then ledgers release. |

If source/READ is revoked before A returns, recheck admission and discard without disclosure. If D becomes READY and A's earlier release later fails, retain A's cause/accounting exactly once without replacing D's ready state. Failure to close cannot refund uncertain memory. A closed owner executor is not a reason to recreate an owner merely to retire native resources.

## E7: save succeeds, application fails, no-write recovery

Inputs: existing actual owner actions, original source identity and an application-only service. A component observes the same operation after leaving/returning:

```java
Read<OperationSnapshot<OwnerResult>> operation =
    Observers.operation(scope, actualSaveHandle, currentReadPolicy);
ViewSpec result = Operations.result(operation, ownerOutcomeProjection,
    List.of(inspectSameResult, applySavedOnly));
```

`Operations.result` composes the shared status/details/action components over the actual cached outcome. `ownerOutcomeProjection` derives readable summary and technical facts from the typed domain result, not diagnostic string parsing. `applySavedOnly` is the existing guarded application-only command, never a generic reset of an Errored boundary.

Sequence: accept complete draft R → Save+apply persists exact bytes S → native apply fails → same handle shows Saved; application failed, with useful cause and recovery. Later draft R+1 remains intact. Navigate Back/Forward/inspection → same operation identity and submitted source; no second write or implicit reload. Explicit retry reads/verifies S, applies its actual candidate and confirms generation G; file bytes/mtime/identity remain unchanged by application-only retry. An unknown write instead requires source reconciliation; a view cannot turn uncertainty into success because its loading boundary settled.

For unsaved world recipes, the owner result identifies admission rather than file persistence. For a batch, each owner retains its own receipt and later refusal/unknown; the result UI cannot claim atomic rollback. These variations reuse the same components and different actual owner capabilities, not new consumer-specific feedback engines.

## Example completeness and limits

Each example specifies admitted inputs, state owner, action and publication paths, identity, failure/currentness and side effects. Together they exercise the same component kernel across generated forms, embedded/task references, previews, raw input, collections, asynchronous work and owner outcomes. The diagrams/walkthroughs are design validation; they are not native runtime tests. [Acceptance](acceptance.md) maps each to independent future checks and the objective requirements.
