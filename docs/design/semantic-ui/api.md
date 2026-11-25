# Proposed API shapes

These are design-level Java contracts, unavailable in dev.96. Snippets omit imports/boilerplate and are not compiled product APIs. The named inputs, results, ownership and algorithms are normative; an implementation may consolidate names while preserving the contracts and migration map. Existing names such as DocumentActions, DraftTarget, ReferenceActions and GraphicLimits retain their actual implementation meaning.

## Namespace and construction

Neutral geometry, key/relationship descriptors, limit records and pure reconciliation belong beside existing core presentation types. Native ViewSpec/Components/control factories and borrowed host services belong in `design.inherent.reform.minecraft.client` or its explicit ui subpackage. Game-version/loader details stay in current seams. No source-container-only public dependency is introduced.

```java
interface UiComponent<P> {
  ViewSpec build(UiScope scope, P props); // once per component identity
}

interface Read<T> {
  UiObservation<T> snapshot(); // cached, owner-thread read; no IO/effect
  <U> Read<U> map(ProjectionId id, Function<T, U> pure);
}

interface Local<T> extends Read<T> {
  UiActionResult set(T frozenValue, UiActionContext context);
}

interface Binding<T> {
  Read<T> value();
  UiCommand<T> changes(); // actual source determines admission, not a raw setter
}

interface UiScope {
  <T> Local<T> local(ViewStateKey<T> key, T initial);
  <T> Read<T> observe(AdmittedObservation<T> existingObservation);
  <T> Read<T> derive(ProjectionId id, List<Read<?>> inputs,
                     PureProjection<T> computation);
  CloseHook onRetire(Runnable cleanup);
  void requestFocus(FocusKey target); // resolves after actual mount/layout
}
```

`UiObservation<T>` is AVAILABLE(value, revision) or UNAVAILABLE(code, reason, dependency identities). AVAILABLE may contain an explicitly optional/null-shaped value; unavailable is never encoded as null. `AdmittedObservation` is a typed borrow from an existing service with current READ/lifetime policy, not a public arbitrary permission callback. PureProjection receives only frozen values of the declared inputs, not a scope/action context. Derived unavailable/error results retain their dependency path.

UiScope wraps an existing ClientViewContext for the component. It supplies no worker pool, disk/native writer, graphics device or global model. It registers one ordered cleanup owner with that context and manages child hooks internally; it does not change the existing OwnerContext's public registration-order behavior. Direct existing services keep their current cleanup order. No scope may escape into worker node access.

ComponentKey/descriptor declares kind/version and immutable plain-prop equality plus exact borrowed capability identity. Plain props initialize one lifetime. Dynamic fields must be Read/binding members; unequal plain props under the same key/kind refuse rather than produce a stale capture. RowFactory's rowRead/indexRead are the changing-input contract. A local state's initial value is used only on first admission of its StateKey/schema, not on reflow or another declaration with that same key. Local cells are qualified by their creating component scope; dispose ends branch-local state, while deliberate ancestor/view-root bindings survive child removal or route suspension under the bounded retention policy.

## Commands and field bindings

```java
interface UiCommand<I> {
  Read<ActionAvailability> availability();
  UiActionResult invoke(I input, UiActionContext context);
}

sealed interface UiActionResult {
  record Applied() implements UiActionResult {}        // local action only
  record Pending(OperationHandle<?> operation) implements UiActionResult {}
  record Refused(Report report) implements UiActionResult {}
  record Stale(String code) implements UiActionResult {}
  record Unsupported(String code) implements UiActionResult {}
}

interface FieldBinding<T, V> {
  FieldIdentity identity(); // exact definition + owner + path/stable row
  Read<FieldState<V>> state();
  UiCommand<ValueEdit<V>> edits(); // existing owner edit, local or through its admitted gateway
}
```

UiActionContext is issued at native dispatch and contains source/reason, root/attachment generation, target/current input stamp and entry point. It is not serializable or constructible as WRITE authority. Existing services recheck admission. Pending carries the actual operation, not a synthetic success token. ActionAvailability is AVAILABLE or blocked with stable useful reason, independently from eventual invocation result.

`FieldState<V>` retains current typed optional observation, exact raw text/input parse state, draft/source/input stamps, issues, dirty state, and read/write/submit availability. `ValueEdit<V>` represents SetTyped, SetRaw, declared Remove/Default/Inherited operations or an existing compound DraftEdit; it does not contain Save. Conversion to the actual DraftEdit rechecks exact ValueType/target. Local presentation state uses Local, document state uses FieldBinding; a setter cannot be cast between them.

`UiInputBinding` adapts the existing mounted raw-input buffer/caret/selection and input epoch. It never creates another accepted draft. A native refresh receives an acknowledgement/source reason so it can preserve active rejected text. `Bindings.field(editor, path)` captures a fresh qualified target at invocation, while retaining the actual field identity for display. `Bindings.local(localCell)` supplies controlled local state; construction chooses that mode permanently.

## Fluent declarations and modifiers

```java
interface ViewSpec {
  ViewSpec key(String stableLocalKey);
  ViewSpec padding(FormTokens.Space role);
  ViewSpec frame(SizeOffer offer);
  ViewSpec theme(ThemePatch patch);
  ViewSpec semantics(SemanticDescription authored);
  ViewSpec clip(ClipPolicy policy);
}

interface Views {
  static <P> ViewSpec component(ComponentKey key, UiComponent<P> factory, P props);
  static ViewSpec column(String key, List<ViewSpec> children);
  static ViewSpec row(String key, List<ViewSpec> children, RowPolicy policy);
  static ViewSpec section(String key, Component title, List<ViewSpec> children);
  static ViewSpec text(String key, Read<Component> text);
  static ViewSpec button(String key, Component label, UiCommand<Void> command);
  static ViewSpec show(String key, Read<Boolean> test,
                       Branch yes, Branch no);
  static <I, K> ViewSpec forEach(String key, Read<List<I>> items,
                                Function<I, K> stableKey, RowFactory<I, K> row);
  static ViewSpec loading(String key, Read<AsyncState<?>> state,
                          LoadingParts parts, PreviousPolicy previous);
  static ViewSpec errored(String key, Branch content, FailureParts fallback);
}
```

This interface notation describes factory methods, not literal Java static interface bodies. `Branch` receives its owned UiScope and constructs detached content lazily. RowFactory receives a stable row key, a Read of the current row value, a Read of current logical index, and a row scope. It must not close over the initial row as current forever. `Repeat` supplies immutable positional indices only for decorative bounded slots; Switch takes keyed ordered cases. Native Components remain defensively copied.

`SizeOffer` declares minimum/preferred/maximum with finite validation and optional flex weights; it is not a domain restriction. `RowPolicy` declares min/max gap, alignment/baseline, grow/shrink and wrap/stack fallback. Padding/frame/clip have ordered wrapper semantics. `SemanticDescription` supplies authored name/relationships/exposure, while actual selected/disabled/value/actions remain control-derived and cannot be overridden by styling.

## Intrinsic components and parts

```java
Field<T,V> field(FieldBinding<T,V> binding, FieldParts parts);
ChoiceList<K> list(SelectionBinding<K> state, ItemSource<K> items,
                   ListParts<K> parts, ListOffer offer);
Disclosure disclosure(Binding<Boolean> expanded, DisclosureParts parts);
Completion completion(UiInputBinding raw, SuggestionSource suggestions,
                      CompletionPolicy policy, CompletionParts parts);
Dialog dialog(DialogBinding openAndReturn, DialogParts parts);
ViewSpec navigationHeader(ExistingNavigation navigation, HeaderParts parts);
```

These generic part contracts specify the actual component, not an inheritance hierarchy that every caller must implement. A standard variant fills appropriate parts. Part builders require one Root, label/actual control relationship and the required feedback/action slots; duplicate or foreign part ownership refuses. A visual `ControlSurface` replacement gets measured bounds/style/state and default native input/semantics wiring, not another effect callback. Registering decorative observers cannot suppress actual owner checks.

SelectionBinding holds separate active/inspected and accepted reads plus explicit inspect/accept commands. A single-value radio can deliberately use acceptance-on-arrow policy; a profile list uses explicit acceptance. Controlled state disappearance is unavailable. ItemSource declares complete finite local items or bounded paged observation with source stamps, stable keys, label, optional summary/disabled reason, and known/unknown total; it owns no data resolver.

The list's acceptance target is explicit: Profiles pins local inspection/audition on click/Enter and exposes separate Use-for-role commands; an embedded Appearance list binds click/Enter to that exact field's guarded edit. Current staged values for all roles remain separate observations. The shared list cannot infer that accepting an inspected row implies persistence or even a document edit.

## Reference picker and embedded form declaration

```java
record PickerRole<T>(String id, FieldPath field,
                     Component title, Component acceptLabel) {}

record PickerPresentation(Component title, Component purpose,
                          int preferredVisibleRows, SearchPolicy search,
                          ListOffer list, ManualIdPolicy manual) {}

interface ReferencePicker<T,V> {
  Read<PickerState<V>> state();
  UiCommand<String> search();
  UiCommand<String> inspect();
  UiCommand<PickerRole<T>> acceptInspected();
  ViewSpec inline(PickerRole<T> role, PickerPresentation presentation);
  ViewSpec task(List<PickerRole<T>> roles, PickerPresentation presentation);
}
```

Construct the picker once per admitted task/view from the actual ClientEditor, exact source ReferenceActions and limits. Inline role and task roles must all match that source/type. PickerState fields and async protocols are defined in [references](references-previews.md). Source factory is still scoped and lazy. Neither inline nor task rendering opens another owner. `acceptInspected` delegates to the actual guarded current field edit.

Evolve `ClientReferenceTask` with a typed picker presentation and preview contribution over this controller; evolve ClientFormOptions with an explicit per-reference-field picker contribution using the same exact definition. Existing simple titled references keep a default shared picker. Ordinary reference-screen entry becomes a host of that same component when a standalone route is needed, not a second implementation. Existing constructors adapt to defaults, with deprecations documented in [migration](migration.md).

## Preview basis, subject and provider surface

```java
sealed interface CapturedGraphicInput<T> {
  Candidate<T> candidate();
  GraphicBasisStamp basis();
}

record CapturedDraft<T>(DraftPreviewInput<T> original)
    implements CapturedGraphicInput<T> { /* candidate + explicit DRAFT basis */ }

record CapturedAudition<T>(DraftPreviewInput<T> base,
    FieldPath target, String role, String referenceKey,
    ReferenceStamp referenceSource, TransformIdentity transform,
    Candidate<T> candidate, ContentDigest candidateDigest,
    GraphicBasisStamp basis) implements CapturedGraphicInput<T> {}

interface PreviewCapture<T> {
  OperationHandle<CapturedGraphicInput<T>> capture(
      PreviewBasis<T> requested, PreviewInputStamp expected);
}

record GraphicPreparationRequest<T,S>(CapturedGraphicInput<T> input,
    S subject, GraphicPreparationStamp stamp, GraphicLimits limits) {}

record PreviewSpec<T,S>(Component title, ClientGraphicProvider<T,S> provider,
    SubjectForm<S> subjects, PreviewCapture<T> capture,
    AutomaticPolicy automatic, GraphicLimits aggregateLimits,
    int maximumViews) {}
```

GraphicBasisStamp is a tagged DRAFT/AUDITION identity carrying base PreviewInputStamp/provenance; audition additionally binds target/source/key/role/transform version/digest. GraphicPreparationStamp binds provider/view/request/context/resources and that whole basis. Constructors refuse mismatched fields/stamps or an audition labelled DRAFT. A content digest is identity, not authority. Loaded requests remain their distinct existing sum member/service.

PreviewCapture is a read-only existing owner operation, with the full capture protocol and source rechecks specified in [references and previews](references-previews.md). It exposes no commit proposal, arbitrary patch callback, second actor or writer. Only a declared target/encoder transformation is allowed. The evolved provider receives GraphicPreparationRequest on the existing work stages/ledger. A compatibility adapter can call an old provider only for CapturedDraft; audition without provider capability yields typed UNSUPPORTED.

Provider registration explicitly declares DRAFT_ONLY or DRAFT_AND_AUDITION and supplies one compatible preparation function. The host checks that capability before capture/compilation. A temporary legacy registration adapter is DRAFT_ONLY and translates the original request without changing its stamp; it never dispatches an audition to a method whose input contract is an original DraftPreviewInput. Source/binary changes and eventual adapter retirement remain explicit migration work, not reflective overload guessing.

SubjectForm binds an exact ValueType<S>, frozen Local<S>, and explicit typed lenses/control declarations. A Lens supplies pure get/replace plus validation through that same type; it does not infer properties by name or access a document. Select/checkbox controls compose that same subject state. Camera has its own retained read/action binding and is absent from preparation keys. The initial camera/fit, framing policy and capability facts remain provider-defined under the shared retention rules.

AutomaticPolicy declares allowed triggers,200/120ms search/preparation policies where relevant, previous-image policy, view-visibility policy, explicit activation state and resource-reload behavior. Scheduling delegates to the existing host/lane; it does not instantiate another executor. Public request/cancel/reset commands expose real readiness. Prepared output retains the same GraphicLedger/native-use/releaseCompletion accounting.

## Errors and observation surface

All component admission/mount results are `Mounted(handle)` or `Refused(Report)`; runtime projection failure yields a scoped failure observation/fallback. Structural constructor errors can be raised locally and normalized at that boundary. Expected invalid user input never becomes an exception-only error channel. Existing stable owner/preview diagnostics remain unchanged where their meaning is unchanged.

The inspector's versioned `UiSnapshot` contains redacted node/relationship/layout/focus/dependency/action metadata and actual operation/basis/resource identities, bounded by UiLimits. It contains no callable closures or authorization tokens. `query` is read-only; optional debug `invoke` must go through an actual UiActionContext and current service admission. Snapshots distinguish requested/mounted/drawn/extracted/presented observations and never infer later stages from earlier ones.

## Example façade signatures

| Façade                                                              | Construction/result contract                                                                                                                                                                                   |
| ------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Forms.generated(editor, options)` / `Fields.bound(binding, parts)` | Return ViewSpec over actual generated fields/restricted binding; options/parts are admitted before mount. No owner creation or value defaults.                                                                 |
| `Forms.row(labelRead, rowBindings, options)`                        | Return one keyed field composition; rowBindings resolve current stable draft-row targets at action time.                                                                                                       |
| `Pickers.open(scope, editor, source, referenceLimits, uiLimits)`    | Return ReferencePicker after exact definition/type/source admission, borrowing existing services. Scope owns observation, not the source's foreign lifetime.                                                   |
| `Previews.open(scope, editor, spec)`                                | Return PreviewController with `content`, `request`, `cancel`, camera and cached state; it uses the workflow's one actual aggregate graphics host.                                                              |
| `PreviewSpec.withSubjects` / `withBasis`                            | Return immutable specification with the same provider/limits and an explicit SubjectForm/requested PreviewBasis read. No preparation occurs during construction.                                               |
| `picker.auditionOrDraft(roleRead)`                                  | Return Read of requested PreviewBasis. No resolved inspection means DRAFT; a resolved inspected key plus role means declared AUDITION. Current base capture still occurs later in the existing read operation. |
| `SubjectForms.of(type, local, controls)`                            | Freeze/validate a local subject through the exact supplied ValueType. Select/checkbox lens declarations have stable keys, captions and choices; they can only edit that local subject.                         |
| `Browsers.profileTask(picker, roleLocal, roles, preview, purpose)`  | Return standard shared browser ViewSpec whose parts borrow these bindings. It creates no additional controller or native renderer.                                                                             |
| `Observers.operation(scope, handle, readPolicy)`                    | Return Read of a cached actual OperationSnapshot under an admitted observation capability; this argument is not an arbitrary caller-supplied permission override.                                              |
| `Operations.result(read, projection, actions)`                      | Return shared cached summary/details/actions view; projection runs on observation change, not paint, and actions retain their actual service guards.                                                           |

For F05, a remote FieldBinding retains the matching definition/type identity but observes only policy-filtered server actor state. Its edit/history commands invoke the actual authenticated server session and return that session's identified outcomes. It is not a local DocumentSession holding invented complete T values. Native text buffers may retain unsynchronized proposals, but accepted draft/history live in that actual server actor; server complete validation and READ filtering remain authoritative.

## Full-reference API additions

F01 registers ValueEditorFactory against an exact type/field plus parse/raw/semantic/cleanup contract. F02 registers NativeReference/Binding capabilities with actual owner/context adapters. F03 supplies DerivedField and MergeProposal over captured observations. F04 supplies ExpressionPolicy/ExpressionResult behind a private provider. F05 exposes RemoteDocumentView/TypedProposal/RemoteCapabilities through an authenticated gateway. F06 supplies ShareProjection/RecipientImport; F07 supplies the extension/inspector manifest. Their required fields, algorithms and failure rules are defined in [full system](full-system.md); none is an empty facade whose implementation can ignore those rules.

Compatibility axes are explicit: public source/binary API, stored definition/schema/codec, wire capabilities, and per-context/runtime epochs. UI keys and captions never become stored field IDs. A public extension is qualified against the packaged artifact, including missing capability, failed construction, stale callbacks, retained invalid input and cleanup. No final API availability is claimed until that implementation exists.
