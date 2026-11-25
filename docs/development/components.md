# Mounted native components

The revised 0.4 development line introduces a shared component tree for framework bodies and caller-owned native screens. The first implemented declarations are text, actions, columns, sections, nested components, and conditional branches. They are in `design.inherent.reform.minecraft.client.ui`; [scoped state](scoped-state.md) supplies the neutral data/lifetime layer. Keyed collections, owner field/input bindings, picker/completion, broader layout/modifiers, and preview orchestration remain required implementation work. This page does not claim the complete semantic design is available or natively qualified.

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

### Framework body

`ClientCustomView.component(viewKey, initialState, limits, component)` binds the same component runtime to an existing workflow category. Its props are the actual `ClientViewState<S>`, not a second owner. Borrow its cached data with `scope.observe(stateKey, currentState)`. Deliberate `currentState.replace` still changes only presentation state. The host drains observations and rebuilds outside the action's in-progress edit and outside painting. `currentState.refresh()` advances its cached observation for an explicit refresh of related cached facts.

Metadata admission opens no factory or editor. Native entry constructs the component once; Home/return retains its admitted view state and creates fresh native attachments. Hiding retires the attachment's callbacks. Closing the workflow closes owned component scopes through the actual existing lifetime. The ordinary generated editor, save path, and owner services remain separate and authoritative.

### Caller-owned native screen

`ClientUiMount.mount(viewContext, limits, component, props, bounds, theme)` returns one stable native `Renderable`/input/narration viewport. Add it as a normal child of the caller's screen. Supply `ClientViewContext.borrow(actualHostLifetime)` with real native-thread and lifetime enforcement. No document, workflow, navigation shell, executor, source, or graphics device is created to obtain this host.

Call `mount.update()` on the host's update turn and `mount.reflow(bounds, theme)` on resize/font/layout changes. Reflow preserves the logical tree and relative viewport/focus identity; the surrounding screen remains responsible for restoring its own focused child after its native `init` clears/rebuilds children. `mount.visible(false)` removes its native input/narration/render participation and clears capture; resuming retains local data. `mount.close()` closes its scopes, not the borrowed lifetime. The actual host must close its own context at its real end.

Rendering/extraction uses the prepared native panel only. It does not invoke component factories, text projection functions, source refresh, document actions, or worker entry. Stale, hidden, or retired attachment callbacks refuse; native activation rechecks current command readiness before invoking that same command. Native keyboard, pointer, focus, narration, clipping, scrolling, and geometry reuse the existing game adapters and `FormPanel`.

## Cached reads and actual commands

`UiScope.observe(key, source)` creates a bounded read-only projection of an existing `UiRead`, not another accepted draft or setter. `refreshObservations()` is explicit, on the host thread, and never a render callback. Source replacement requires a fresh declared binding; a retained key cannot silently change its source/type. Failed/closed sources become unavailable and clear their cached data. Source callbacks are trusted cached-observation contracts, not IO hooks or caller-supplied authorization overrides. Actual owner-backed adapters must retain their own permission/context checks.

`UiCommand<I>` provides cached `UiAvailability`, declared read dependencies for that feedback, and actual invocation. `UiActionResult` distinguishes a local `Applied`, a `Pending` carrying the existing `OperationHandle`, and a durable `Refused` report/cause. None certifies saving or application. `UiBindings.local` adapts a local cell only; it cannot become a document field binding or persistence operation. Other commands must call the actual owner services, which retain their authority and currentness checks. Disabled feedback is not authorization.

All text/readiness projection happens through a declared pure scope frame. Writes through these bindings/scope APIs during projection refuse. Ordinary trusted Java remains outside a sandbox; native extension qualification is still necessary. Runtime admission accounts for mounted node scopes, state/cached observations, declared dependencies, and hooks under `UiLimits`; domain values/catalogues retain their own legal limits.

The shared model tests exercise factory-once behavior, cached projection, actual retained-view observation, conditional disposal, partial construction cleanup, pure-write refusal, and recovery in all four target test environments. The isolated `components` native fixture exercises the public framework and external-host routes. Actual packaged/native results and their limits belong in verification evidence rather than following from this API inventory.
