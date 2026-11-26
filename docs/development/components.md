# Mounted native components

The revised 0.4 development line introduces a shared component tree for framework bodies and caller-owned native screens. Implemented declarations include text, actions, columns, sections, nested components, conditional and keyed case branches, keyed collections, and bounded decorative repetition. They are in `design.inherent.reform.minecraft.client.ui`; [scoped state](scoped-state.md) supplies the neutral data/lifetime layer. Core owner-backed field observations/edit commands and their retained-client input adapter are available. General field hosting/disclosure parts, loading/error boundaries, picker/completion, broader layout/modifiers, and preview orchestration remain required implementation work. This page does not claim the complete semantic design is available or natively qualified.

## One declaration and two hosts

`UiComponent<P>.build(scope, props)` runs once per admitted component identity. It returns an immutable `ViewSpec`, assembled with `Views`. Plain props are initialization constants or explicitly borrowed bindings; supply immutable values and preserve capability identity. There is no implicit tracking of arbitrary Java record fields or factory rerun on every frame. Changing data belongs in `UiRead`, `UiLocal`, or actual service-backed bindings. `UiText.from(read, projection)` declares the cached input for dynamic text, while `UiText.of(component)` defensively captures fixed native text.

```java
UiComponent<String> body = (scope, purpose) -> {
  var query = scope.local(queryKey, "");
  return Views.section("search", UiText.of(Component.literal(purpose)), List.of(
      Views.text("query", UiText.of(Component.literal("Search")),
          UiText.from(query, Component::literal))));
};
```

Descriptions carry stable sibling keys, not widgets or another document draft. Duplicate sibling keys refuse. Each mounted node owns an independent child scope; native reflow retains that logical identity while replacing attachments. Conditional factories are lazy. Switching a condition admits the new branch before retiring the old branch and its local state. Ancestor state remains retained. Factory failure attempts partial cleanup and does not close the borrowed host. A later pure-projection failure retains earlier content with disabled actions and useful feedback until a valid update recovers. Unavailable inputs, including permission/context loss, clear previously disclosed rows instead; they are not an offer to keep showing stale private values. Recovery uses the same admitted source and component lifetime.

### Keyed and positional flow

`Views.forEach(key, items, itemType, maximumRows, itemKey, row)` borrows a cached immutable list read and the exact element `ValueType`. The explicit finite row allowance is presentation admission, not a restriction on the domain or a silent truncation policy. `itemKey` returns a stable nonempty string of at most 256 characters. The complete key vector and element snapshots are validated before row reconciliation. Duplicate, missing, or over-budget keys retain the previous row offer with feedback and disabled row actions; controls outside that collection remain available for repair.

The row factory receives its own scope, stable key, a current item read, and a current logical-index read. It runs once for that key's admitted lifetime. Move/update retains the scope and its local state while publishing current item/index observations. Removing a key retires its scope; reintroducing it creates a fresh lifetime. Do not capture the initial item as if it were permanently current. Row data lives in an internal child scope, so runtime state keys do not collide with authored row locals.

A failed row factory releases partial state and offers a feedback row without an action in that same slot, without shifting later values. `ClientUiMount.rowFailures()` exposes the mounted collection identity, row key, stable `ui.row_failed` report, and actual cause. A new source observation retries failed rows while preserving healthy siblings. Cleanup failures remain failures; they are not recast as successful fallback retirement. If the admitted source becomes unavailable, copied item/index state and row scopes retire before recovery. No earlier private row is retained as an available offer.

`Views.switchOn(key, cases, fallback, exclusive)` selects cases in authored order using cached boolean reads. Each case has a unique stable key and a lazy branch factory. Ordinary mode selects the first match; exclusive mode refuses ambiguity before changing the current branch. Switching retires old branch state and preserves ancestor state.

`Views.repeat(key, count, from, maximumSlots, row)` is for fixed positional decoration or skeleton slots. Counts must be nonnegative and within the explicit finite allowance; the declared index range must not overflow. Surviving slots retain their scopes, removed slots retire, and an unavailable/invalid count withdraws the slot offer with feedback. Use keyed `forEach`, not positional repetition, for editable persistent rows.

Native action dispatch refreshes admitted cached observations before invocation. An unrelated kernel revision may preserve the exact cached action/disclosure offer when all of its declared inputs remain unchanged and it is still in the active tree. Changed, removed, or blocked offers refuse; a replaced attachment cannot become current merely because it still holds a button reference. Current command readiness is checked again before invocation. This freshness check does not replace actual owner authority checks.

### Framework body

`ClientCustomView.component(viewKey, initialState, limits, component)` binds the same component runtime to an existing workflow category. Its props are the actual `ClientViewState<S>`, not a second owner. Borrow its cached data with `scope.observe(stateKey, currentState)`. Deliberate `currentState.replace` still changes only presentation state. The host drains observations and rebuilds outside the action's in-progress edit and outside painting. `currentState.refresh()` advances its cached observation for an explicit refresh of related cached facts.

Metadata admission opens no factory or editor. Native entry constructs the component once; Home/return retains its admitted view state and creates fresh native attachments. Hiding retires the attachment's callbacks. Closing the workflow closes owned component scopes through the actual existing lifetime. The ordinary generated editor, save path, and owner services remain separate and authoritative.

### Caller-owned native screen

`ClientUiMount.mount(viewContext, limits, component, props, bounds, theme)` returns one stable native `Renderable`/input/narration viewport. Add it as a normal child of the caller's screen. Supply `ClientViewContext.borrow(actualHostLifetime)` with real native-thread and lifetime enforcement. No document, workflow, navigation shell, executor, source, or graphics device is created to obtain this host.

Call `mount.update()` on the host's update turn and `mount.reflow(bounds, theme)` on resize/font/layout changes. Reflow preserves the logical tree and relative viewport/focus identity; the surrounding screen remains responsible for restoring its own focused child after its native `init` clears/rebuilds children. `mount.visible(false)` removes its native input/narration/render participation and clears capture; resuming retains local data. `mount.close()` closes its scopes, not the borrowed lifetime. The actual host must close its own context at its real end.

Rendering/extraction uses the prepared native panel only. It does not invoke component factories, text projection functions, source refresh, document actions, or worker entry. Stale, hidden, or retired attachment callbacks refuse; native activation rechecks current command readiness before invoking that same command. Native keyboard, pointer, focus, narration, clipping, scrolling, and geometry reuse the existing game adapters and `FormPanel`.

## List, query, and attached work

`Views.choiceList(key, ChoiceListParts)` mounts one native focus target, rather than a button for every entry. Its bounded `ChoiceListData` window carries stable keys, titles, supplemental detail, enabled state, and an optional source-supplied total. Duplicate keys, more than 4096 admitted metadata entries, and a total smaller than the window refuse. The finite window allowance does not narrow the source domain. A missing total is unknown, and a current window index is not a global position.

The controlled active key and the accepted key set are independent reads. Arrow, Home/End, and Page Up/Down navigation change only the active key and skip disabled entries. Enter/Space or click invokes the declared acceptance command; the list does not stage a document value itself. The native widget prepares at most its visible rows plus bounded overscan, retains a surviving first-visible key within the same data generation, and resets the viewport when the generation changes. Scrolling and scrollbar dragging do not accept an entry. Full active-row title and supplemental detail remain in narration; clipped list text is not a replacement for the inspection surface.

`Views.queryInput(key, QueryInputParts)` uses the native EditBox for UI-local query text, caret, and selection. Its controlled query read and change command are separate from the Enter submission command. The mount samples the actual buffer before source observation and before hiding; rendering performs no query or source work. Enter flushes the current native buffer through the same live input even when that observation requires reflow. List/source callbacks still reject stale data. An overlong controlled query refuses instead of silently truncating that read. This control is not a document ID input or a second document draft; canonical field input and completion must use the actual `ClientTextInput` path.

`Views.managed(key, lifecycle, content)` declares bounded attached work within that node's owned scope. `UiMountLifecycle.update()` runs on the owner update turn, after native query sampling and before cached source observation, only after actual attachment. Construction and paint never invoke it. `suspend()` runs when an entered subtree is hidden, withdrawn, or retired; retained Disclosure content keeps local state but suspends its work. The component must register final resource ownership with its scope and borrow, rather than close, unrelated document/source services. No timer, executor, actor, or global scheduler is created. Framework bodies drive this turn; a caller-owned host continues to call its mount's `update()`.

The shared inline/task assembly below retains a finite paging window over the bounded source service and can use a canonical field control surface. Source-stamped manual-ID completion, complete return bookmarks, and preview orchestration remain selected work. Existing reference screens have not yet been replaced by this component assembly.

### Source-backed picker assembly

`ReferencePickerView.build(scope, picker, presentation, fixedRole)` assembles the same shared list, query, inspection, source details, and commands for an inline or task placement. Its `Presentation` supplies owner purpose, headings, search label, and exact authored role/action captions. A present fixed role accepts into that one field; an absent role pins inspection only and exposes separate explicit Use-for-role commands. The accepted markers come from actual role observations, not the active row or the pinned inspection. Resolved-empty profiles remain eligible; contribution facts are not gates.

Construct the controller within the actual mount's kernel and borrow a ready existing editor. For a framework task, `ClientCustomView.documentComponent(key, initial, limits, route, definition, component)` declares that association. The callback receives `(scope, editor, state)`, with the exact loaded editor retained for that route. Metadata validation checks the route and document identity without opening factories; admission checks exact definition identity and actual inspection readiness. The route may be a form alias and is not inferred from a document ID. Navigation reuses the existing editor and `ensureLoaded` lane before invoking the component. Discard retires its component scopes; a fresh editor gets fresh component state. Home and reflow preserve the admitted lifetime.

`ClientCustomView.component` stays independent of documents and remains appropriate for UI-only content. Selective library callers supply their own actual host/admission rather than creating a dummy document. `openEditor` alone does not load an actor: callers explicitly managing that boundary use the existing `DocumentActions.ensureLoaded` operation, not another actor or writer. These loading and retirement behaviors require their own qualification; construction or compilation alone does not establish native admission.

For a mounted native field, the overload taking `parts.binding()` and the exact `DocumentDefinition` borrows that same owner after checking definition identity. `parts.frontend()` supplies its lifetime-fenced actual input/ack and edit restrictions. A task can borrow equivalent restrictions from `ClientFieldBindings.field(...).frontend()` for each actual native field. An empty frontend map is only for use without those buffers.

```java
// Inside the actual field component, with owner-declared keys, source, roles, and labels:
var expanded = scope.local(choicesOpenKey, false);
return Views.column("field", List.of(
    parts.standard("canonical"),
    Views.disclosure("choices", UiBindings.local(expanded),
        DisclosureParts.retained(choiceLabel, noHelp, noSummary, content -> {
          var picker = ReferencePicker.open(content, parts.binding(), definition,
              sourceId, cachedSource, roles, Map.of(role.id(), parts.frontend()),
              ReferencePicker.Policy.STANDARD, System::nanoTime);
          return ReferencePickerView.build(content, picker, labels, Optional.of(role));
        }))));
```

This example deliberately leaves the original canonical field family attached. It does not implement the final collapsed manual-ID/control-surface policy. The lazy content creates no controller/source before expansion. The managed assembly owns its visible driver, not the borrowed source or document. Field aliasing, additional editors, fake enum shapes, or another native input are unnecessary.

List paging demand comes from actual navigation/scroll gestures, outside painting. A refresh may preserve a key event when the same generation and exact item remain present; keyboard activation additionally requires the same controlled active key. Removed, changed, disabled, or different-generation items refuse. This permits Enter during harmless page/status updates without allowing stale option callbacks.

The assembled native journey remains under qualification. Full inline/task bookmarks, manual-ID completion, layout/feedback relationships, and preview/camera policies are not established by the assembly API alone. Canonical surface qualification is recorded separately in verification.

## Cached reads and actual commands

### Actual field bindings

`FieldBinding.bind(scope, actions, path, exactType)` borrows the existing `DocumentActions`, exact definition/type, owner context, and stable field path. It creates no document, editor, source read, or save action. Its `state()` is a bounded cached observation of the real draft revision/target, immutable raw draft node, optional typed field value, parse/validation facts, dirty/active state, and edit/submission feedback. Field-codec observation is separate from complete owner validation; a valid sibling remains readable/editable beside an invalid field. Missing rows, inactive values, absent typed values, and unavailable read authority are distinct.

`ClientFieldBindings.field` adapts the same binding to an existing `ClientEditor`. Its frontend observes the actual mounted input's raw text, logical input epoch, caret, selection, acknowledgement, and pending-work state. It reuses existing `canStage`/`canSubmit` restrictions without treating them as owner authority. It does not mount or attach a widget, replace the input buffer, or erase rejected text. Intrinsic field parts and direct native input actions remain separate work.

`edits()` is an actual `UiCommand<FieldEdit<V>>`. Raw/typed scalar edits and existing structural actions target only this binding's fresh exact draft target. Compound typed replacement is currently refused explicitly; use the owner's declared structural controls. No arbitrary JSON patch or alternate whole-document writer is introduced. Native input autocomplete must use the eventual shared raw-input action surface, rather than bypassing retained text with a direct actor edit.

Invocation rechecks current source identity, actual READ/WRITE authority, field presence/activity, and frontend restrictions. A pending result carries the actual existing owner operation; it is not a fabricated successful edit or save. Effects still use the owner's normal operation admission. Calling an edit inside a pure scope frame refuses before operation submission. Closing a binding releases its observation scope and leaves the owner/editor open.

Nonportable observations use `UiReadKey` with explicit owned snapshot, equality, depth/node, and measured payload contracts. `UiScope.observe`/`derive` admit those reads under the same aggregate budget. `ViewStateKey` retains its separate portable 16 KiB bookmark envelope; accepted field text is not clipped to make it a bookmark. `UiObservation.Available.data()` is therefore an owned `UiSnapshot`, whose `retainedBytes()` is declared payload accounting, not a JVM heap or transport measurement. A correctly qualified snapshot already captured by the exact admitted key can be shared without retaining a second copy. Arbitrary native producer policies remain trusted contracts, not a Java sandbox or permission grant.

`UiScope.observe(key, source)` creates a bounded read-only projection of an existing `UiRead`, not another accepted draft or setter. `refreshObservations()` is explicit, on the host thread, and never a render callback. Source replacement requires a fresh declared binding; a retained key cannot silently change its source/type. Failed/closed sources become unavailable and clear their cached data. Source callbacks are trusted cached-observation contracts, not IO hooks or caller-supplied authorization overrides. Actual owner-backed adapters must retain their own permission/context checks.

`UiCommand<I>` provides cached `UiAvailability`, declared read dependencies for that feedback, and actual invocation. `UiActionResult` distinguishes a local `Applied`, a `Pending` carrying the existing `OperationHandle`, and a durable `Refused` report/cause. None certifies saving or application. `UiBindings.local` adapts a local cell only; it cannot become a document field binding or persistence operation. Other commands must call the actual owner services, which retain their authority and currentness checks. Disabled feedback is not authorization.

All text/readiness projection happens through a declared pure scope frame. Writes through these bindings/scope APIs during projection refuse. Ordinary trusted Java remains outside a sandbox; native extension qualification is still necessary. Runtime admission accounts for mounted node scopes, state/cached observations, declared dependencies, and hooks under `UiLimits`; domain values/catalogues retain their own legal limits.

The shared model tests exercise factory-once behavior, cached projection, actual retained-view observation, conditional disposal, partial construction cleanup, pure-write refusal, and recovery in all four target test environments. The isolated `components` native fixture exercises the public framework and external-host routes. Actual packaged/native results and their limits belong in verification evidence rather than following from this API inventory.

## Actual text input commands

`ClientTextInput.bind(scope, editor, path)` borrows the revealed field's existing native text buffer and acknowledgment pipeline. It creates no widget, alternate draft, writer, or save action. Its `state()` is a bounded, cached `UiRead` of actual raw text, caret/anchor, native character allowance, and edit feedback. Poll the parent scope outside paint. A hidden, replaced, unreadable, or closed input is unavailable; closing the binding retires only its observation and commands.

`replacements()` accepts a `Replacement` with an opaque stamp from that same binding, a `[start,end)` range, replacement text, and the resulting caret/anchor. Offsets use Java UTF-16 units. The command rechecks the actual owner, current input identity, complete draft revision, native input epoch, raw text, and selection before changing anything. Stale or cross-binding stamps, invalid ranges/selections, surrogate-pair splits, and over-limit results refuse before native mutation or operation submission. Character limits never silently truncate completion text. The stamp is neither portable bookmark data nor a permission grant.

An admitted text change goes through the same quantity mapping, retained input, version acknowledgment, and actual owner edit as native typing. `Pending` carries that actual operation handle; an admitted invalid value remains repairable in the original buffer and follows the original validation/submission guards. A selection-only change returns `Applied` without an actor edit. This is the input-command foundation for completion and intrinsic field parts, not an implementation of a completion catalogue, popup, inline picker, rejected-input/IME support, or the complete revised form API.

### ID suggestion port under development

The unpublished dev.119 source adds `picker.suggestions(policy)` as a separate read-only query port on that same controller and admitted source. Construction opens no source. `request(prefix)` replaces only suggestion demand; host `update()` observes the declared debounce and enters existing source operations outside paint. A replacement waits for entered work to drain before the latest demand enters. Browse query/caret/inspection and actual draft values remain separate.

The port prefers the source's explicitly supported key-prefix query. Text-only sources use a bounded empty-query listing and exact key-prefix filtering. Page/choice limits produce honest incomplete suggestions, without truncating the field's raw input or claiming that an unmatched ID is invalid. `complete` describes exhaustion of that suggestion query/listing, never reference validity. No inspection lookup or domain compilation runs for partial text. A source change, observed permission loss, or payload refusal withdraws old choices; restored permission cannot revive an old option without a new explicit demand.

`requireField(binding)` checks the exact original actor and declared role before an input can use this port. `move` changes only the local active suggestion. `activeOption()` reads it authoritatively for consecutive native events; `requireChoice(option)` rechecks the exact source stamp, prefix generation, and current key. Neither method writes an input. Native acceptance must still capture and use the original `ClientTextInput.replacements()` command, with its own raw/selection/draft/epoch guards. The port alone does not establish native popup, focus, dismissal, or input acceptance; those are the separate host offer below.

### Native completion offer under qualification

The unpublished dev.120 field surface accepts an optional `CompletionParts(picker.suggestions(policy), label, visibleRows)`. The offer belongs to the same field family and appears only with that surface's original revealed native input. An orphan, duplicate, or foreign offer refuses before native attachment. The declaration does not admit its source while the input is collapsed.

The native host prepares a bounded popup outside the scrolling body's clip, using the original input's measured position. `ClientTextInput.State.focused()` reports actual focus; the popup uses a local active descendant while the original EditBox retains native focus. Choosing a suggestion invokes that input's stamped replacement command. Source keys remain exact UTF-16 values, distinct from bounded native row identities. Popup snapshots, input captures, and row metadata share the admitted presentation budget. Rendering does not query or resolve the source.

This offer remains under qualification. Dev.120 has an actual NeoForge 1.21.1 keyboard/input pair; dev.121 adds actual Escape/reopen, blur, pointer acceptance, and root narration on that target. Completion-popup resize, source-withdrawal events, standalone-host interaction, and other native targets remain separate checks. The complete picker, preview, and consumer handoff is still open.

## Composed form fields

`ClientFormOptions.withComponent(path, component)` declares a scalar/reference field composition in the actual generated form. `SettingsScreen.withComponents` supplies the same boundary for a standalone form. The component runs once per admitted field/session lifetime and receives its scope and `ClientFieldParts`. `parts.binding()` is the actual owner/path/type binding; `parts.input()` lazily borrows the same text-input command path when the field uses text rather than a cycle. There is no second native input, actor, draft, or save action.

A field family provides `label`, `description`, `control`, and `feedback` descriptions. `parts.standard(key)` assembles a vertical standard variant. Exactly one label, control, and feedback part is required; description is optional. Missing, duplicated, or foreign parts refuse before native attachment. A composition can add ordinary shared component text/actions/flow around those parts. Native controls keep their original caption semantics, quantities, validation, source actions, and responders; component declarations cannot inject an arbitrary widget into the description.

`parts.controlSurface(key, replacement, nativeExpanded, nativeLabel)` replaces the default control presentation while retaining a field-owned native-control disclosure. It fills the required control role; label and feedback are still required. The opaque surface and original-input slot cannot authorize another field, and an orphan input or duplicate default/surface control refuses. The replacement is a shared `ViewSpec`, not another native widget or effect callback. Reference surfaces reveal the original raw input with its information/dependency actions, using the replacement picker instead of a second full-screen chooser. Other scalar controls retain their ordinary units and auxiliary controls. The original buffer, native responder, input acknowledgment, and owner edits stay authoritative.

The controlled `nativeExpanded` binding uses the ordinary retained disclosure policy. A collapsed native input is unavailable through `ClientTextInput`, even while the field's guarded picker may still stage an explicit choice. Reopening retains the original buffer and control lifetime. Qualification of raw text, caret, focus, pending receipts, and actual closure is separate from the structural surface contract; this is not autocomplete or arbitrary field hosting.

```java
options.withComponent(FieldPath.ROOT.field("enabled"), (scope, parts) ->
    Views.column("enabled", List.of(
        parts.label("label"), parts.description("description"),
        parts.control("control"), parts.feedback("feedback"))));
```

Field components share one form-owned `UiLimits.STANDARD` aggregate root, rather than multiplying the payload allowance for every field. Their local state survives native reflow and temporary hiding while the same owner/session/path remains. Hidden attachments cannot dispatch; removed paths, replaced sessions, and form closure retire their scopes. Cleanup closes the presentation root and leaves domain ownership to the existing editor lifecycle. A native replacement adapter or dedicated editor cannot also declare this component for the same field.

This is the actual-form field-parts host. Arbitrary field mounting in caller-owned component trees, loading/error boundaries, picker/completion, and the remaining revised layout/preview contracts are still in progress. Native and model qualification is recorded separately in verification.

## Disclosure

`Views.disclosure(key, expanded, parts)` uses an actual controlled `UiBinding<Boolean>`. `DisclosureParts` names the label, description, collapsed summary, lazy content factory, and explicit `RETAIN` or `DISPOSE` policy. `DisclosureParts.retained` preserves the admitted content scope and its local state while collapsed; `disposable` retires it and creates a new scope on reopening. Neither policy mounts hidden controls or invokes the content factory before first expansion. Retained observations share the existing aggregate limits and may still refresh on host turns; collapsing is not permission revocation.

The native trigger uses Enter/Space, reports expanded/collapsed state, and includes the collapsed summary in its narration. Collapsing removes descendant input participation, rejects stale callbacks, and redirects a previously focused descendant to the live trigger. The caller-owned host still owns its surrounding screen focus and native resize lifecycle.

Content failures clear projected content and leave the trigger available. `ClientUiMount.disclosureFailures()` reports the boundary identity, structured report, and actual cause. A failed construction is retried only after explicit collapse/reopen; an existing content source can recover through a fresh observation without recreating its scope. Failed cleanup remains a failed retirement. `UiBindings.toggle` invokes the current controlled value's actual command and never writes an optimistic expansion state after a pending or refused result.

An ordinary Disclosure does not replace a field's mandatory label/control/feedback family. Hiding a required field part still fails that field's composition contract. `controlSurface` instead declares control ownership explicitly and keeps the original native input reachable through its own retained disclosure.
