# Mounted native components

The revised 0.4 development line introduces a shared component tree for framework bodies and caller-owned native screens. Implemented declarations include text, actions, columns, sections, nested components, conditional and keyed case branches, keyed collections, and bounded decorative repetition. They are in `design.inherent.reform.minecraft.client.ui`; [scoped state](scoped-state.md) supplies the neutral data/lifetime layer. Core owner-backed field observations/edit commands and their retained-client input adapter are available. Intrinsic field parts/raw-input actions, loading/error boundaries, picker/completion, broader layout/modifiers, and preview orchestration remain required implementation work. This page does not claim the complete semantic design is available or natively qualified.

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

Native action dispatch refreshes admitted cached observations before invocation. A callback from a replaced attachment or a removed/blocked row cannot become current merely because it still holds a button reference. This freshness check does not replace actual owner authority checks.

### Framework body

`ClientCustomView.component(viewKey, initialState, limits, component)` binds the same component runtime to an existing workflow category. Its props are the actual `ClientViewState<S>`, not a second owner. Borrow its cached data with `scope.observe(stateKey, currentState)`. Deliberate `currentState.replace` still changes only presentation state. The host drains observations and rebuilds outside the action's in-progress edit and outside painting. `currentState.refresh()` advances its cached observation for an explicit refresh of related cached facts.

Metadata admission opens no factory or editor. Native entry constructs the component once; Home/return retains its admitted view state and creates fresh native attachments. Hiding retires the attachment's callbacks. Closing the workflow closes owned component scopes through the actual existing lifetime. The ordinary generated editor, save path, and owner services remain separate and authoritative.

### Caller-owned native screen

`ClientUiMount.mount(viewContext, limits, component, props, bounds, theme)` returns one stable native `Renderable`/input/narration viewport. Add it as a normal child of the caller's screen. Supply `ClientViewContext.borrow(actualHostLifetime)` with real native-thread and lifetime enforcement. No document, workflow, navigation shell, executor, source, or graphics device is created to obtain this host.

Call `mount.update()` on the host's update turn and `mount.reflow(bounds, theme)` on resize/font/layout changes. Reflow preserves the logical tree and relative viewport/focus identity; the surrounding screen remains responsible for restoring its own focused child after its native `init` clears/rebuilds children. `mount.visible(false)` removes its native input/narration/render participation and clears capture; resuming retains local data. `mount.close()` closes its scopes, not the borrowed lifetime. The actual host must close its own context at its real end.

Rendering/extraction uses the prepared native panel only. It does not invoke component factories, text projection functions, source refresh, document actions, or worker entry. Stale, hidden, or retired attachment callbacks refuse; native activation rechecks current command readiness before invoking that same command. Native keyboard, pointer, focus, narration, clipping, scrolling, and geometry reuse the existing game adapters and `FormPanel`.

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
