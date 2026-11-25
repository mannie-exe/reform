# Scoped presentation state

The revised 0.4 development line adds a bounded presentation-state kernel in `design.inherent.reform.presentation.ui`. It can borrow an existing `OwnerContext` directly or open through `ClientViewContext.openScope(UiLimits)`. Neither entry creates a document, editing actor, workspace, worker pool, file, or graphics device. The caller's actual thread and lifetime checks remain authoritative.

This is the state/lifetime foundation, not the completed semantic component runtime. Declarative mounting, field bindings, intrinsic components, and shared picker integration remain implementation work under the [semantic design](../design/semantic-ui/README.md). Source compilation and headless tests do not establish native input or rendering qualification.

## Framework and selective use

A framework-owned workflow supplies its existing view context. A caller-owned native screen or other host can use `ClientViewContext.borrow(actualHostLifetime)` without creating a workflow. Closing a presentation scope retires only its own state, children, and hooks; it never closes the borrowed context. Closing that actual context retires the scope automatically. A neutral caller can instead use `UiScope.borrow(actualHostLifetime, limits)` without loading any Minecraft class.

The host must supply real executor, thread, and lifetime bindings. An always-true thread guard is not a supported substitute for native ownership. A borrowed context grants no READ/WRITE, persistence, source, or rendering authority. Optional features require explicit services when selected; the state kernel does not discover them.

## Local data and projections

```java
// actualHostLifetime is supplied by the existing host, not created by this component.
var scope = UiScope.borrow(actualHostLifetime, UiLimits.STANDARD);
var queryKey = new ViewStateKey<>(DocumentId.parse("example:query"), ValueTypes.text(256));
var captionKey = new ViewStateKey<>(DocumentId.parse("example:caption"), ValueTypes.text(512));
var query = scope.local(queryKey, "");
var caption = scope.derive(captionKey, List.of(query),
    inputs -> "Search: " + inputs.value(query));
scope.update(() -> {
  query.replace("first");
  query.replace("current");
});
var cached = caption.snapshot(); // Available, or a typed Unavailable report and retained cause.
```

Keys are stable data contracts, distinct from captions, indices, or document fields. Reusing the exact key in the same scope returns the retained cell without initializing it again. Reusing its identity with another key/type, or changing an existing derivation's declared inputs/computation, refuses with `ui.binding_mismatch`. New component lifetimes use fresh child scopes. State that intentionally survives child removal belongs in an ancestor and is borrowed by the child.

`UiRead.snapshot()` returns the cached revision-qualified observation and invokes no projection or source callback. `UiObservation.value()` restores an owned bounded value through the declared `ViewStateKey`; it can fail for unavailable data and is not a paint-time decoding recommendation. Native component integration must cache its presentation projection before rendering. Null/default/false is never substituted for unavailability.

Derivations declare distinct live reads from the same root. They recompute in dependency order after accepted changes, once per batched update, and propagate unavailable inputs without invoking downstream computation. A projection failure retains its actual local cause and report. Reading undeclared inputs or writing through the state/scope APIs in a pure computation refuses. This protects these services, not arbitrary trusted Java code or a foreign library's IO.

`update` batches invalidation, not document transactions. If an event performs an accepted local change and later throws, the accepted change remains and dependents refresh. Local presentation cells never become a second document draft or save path. External domain observations and owner-backed field bindings require their own admitted adapters.

## Limits and retirement

`UiLimits` bounds aggregate live scopes/depth, cells, derivations, dependency edges, close hooks, and encoded cached-state bytes for one root. Each `ViewStateKey` also retains its existing data/codec envelope. `usage()` reports these counts, with a final aggregate snapshot available after retirement. These are presentation-data accounting limits, not native/GPU allocations, arbitrary callback heap bounds, or a restriction on the legal document/catalogue domain.

Refusing a local replacement preserves its earlier value. A derivation that cannot fit its result reports `ui.budget_exceeded`; a later valid input can recover. Cross-root, closed, and duplicate dependency inputs refuse before admission. Structural/fatal construction failure removes only that contribution's admission.

Register `onRetire` before allocating owned work. The root installs one close hook in the actual host; the host's registration order stays intact. Internally, descendants retire first and hooks run in reverse registration order. All hooks are attempted, failure causes are retained, and old handles cannot write after retirement. Unregistering a hook does not execute it. Repeated close is inert. Existing graphics ledgers and release-completion services remain responsible for native resources; ending a state scope never certifies their release or refunds uncertain resources.

The kernel tests exercise batched dependency propagation/recovery, invalid/oversized input preservation, undeclared reads/pure writes, thread/lifetime checks, cross-root refusal, child replacement, failed construction, cleanup ordering/failure, and actual host closure during computation. The common client-context tests run in all four native target test classpaths. Actual mounted-component/native qualification remains separate.
