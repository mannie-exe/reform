# Reference sources

`ValueTypes.reference(element, source)` adds selector metadata to an existing text/choice `ValueType`. It preserves that type's codec, snapshot policy, authored spelling and structural draft path. It performs no lookup while decoding or drawing a field. Structurally valid unresolved keys remain editable; the owner decides which reference checks are required for validation, publication or admission. Existing native registry codecs still need their actual registry context.

The current implementation supplies reference data, shared local operations, exact draft-bound selection and an experimental native picker. Searchable multi-selection, real consumer bindings and broader native/context qualification remain required by [coverage](coverage.md).

## Complete admitted data

`ReferenceResolver<K,V>` returns `ReferenceResolution<V>` and bounded `ReferencePage<K>` suggestions. Resolution distinguishes resolved, unknown reference, malformed syntax, missing dependency and unavailable context. A resolved empty collection is a real value. A ready empty index is different from an unavailable source. Namespaces identify resources, not installed mods; an unknown namespace alone cannot establish a missing dependency.

`ReferenceStamp` binds the actual owner context, logical source, unique snapshot nonce and descriptive revision. Rebuilding the same revision label produces a different nonce. Each page and result retains its stamp. A continuation also binds the exact filter and next candidate offset; an empty filtered page can legitimately have more candidates. `examined` reports candidates examined, without inventing a total match count.

`ReferenceIndex` owns immutable copies of an explicitly admitted complete collection. It requires unique keys and encodings whose equality/hash survive copying and codec roundtrips. Rows sort by their encoded keys, with locale-independent case-folded text filtering. Producer exhaustion refuses the whole index; a truncated source cannot report an unknown key as a complete lookup. Lookup and pages return independent owned values/keys and immutable presentation metadata.

`ReferenceLimits.STANDARD` admits 16,384 entries, 1,048,576 aggregate text characters, pages of 64 choices, 128-character filters and at most 4,096 examined candidates per search. Snapshot depth/visit limits are separate. These are local index/presentation policies, not network, file or universal domain-value limits. Callers select source-specific bounds and explicit snapshot policies. Pure custom providers must bound their own work; an interface cannot sandbox consumer code.

## Actual-owner operations

Construct `ReferenceActions<K,V>` with the real `OwnerContext`, its actual `Access`, logical source, key type, result snapshot policy and limits. Its `resolve` and `search` services use the existing [operation scope](operations.md), require READ and perform lookup on admitted workers. They check fresh authority, cancellation, source identity and context again before delivering an owned result. Choice requests can include the expected snapshot nonce. Providers cannot return foreign stamps, duplicate keys, oversized presentations or continuations inconsistent with examined candidates.

`bind` invalidates the previous source before building a complete replacement. `bindAsync` allows native capture at entry, worker compilation and fresh native publication. Until publication, the source is unavailable. Failed, reentrant or late producers cannot resurrect an old source or overwrite a newer binding. `invalidate` publishes an explicit missing-dependency or unavailable state. Context closure clears the provider and refuses new work; running operations drain through their retained handles.

`bindCaptured` supplies a convenience boundary for native capture and worker compilation: its capture receives the actual owner and assigned stamp, compilation uses that context's bounded worker lane, and publication retains the same fresh-source checks. It adds no executor or resource enumeration. `observedStamp` and `isOpen` provide cached lifetime observations for presentation; they neither authorize reading nor perform a lookup. `requireStamp` separately performs fresh READ admission on the actual owner thread.

## Draft-bound selection

`ReferenceSelection` binds one `DocumentActions` draft target to its declared `ReferenceActions<String,V>` source. It captures the exact editor/session/generation/version/path, original raw key and actual source/context nonce. A changed draft, removed/replaced/moved row, branch switch, save acknowledgement, reload, source replacement or closed context makes that selection obsolete. Equal descriptive source revisions do not preserve freshness.

Search and inspection return the existing operation handles. The frontend marshals completion onto the controller's creating owner thread and calls `accept` with the actual request object. Only the latest completed search or lookup handle may publish its result; callers cannot substitute a different response. Starting another request invalidates the prior response even when fresh admission refuses that new request. Changed queries replace their old results, and closing the controller cancels only its own outstanding reads. The borrowed source and document remain open. Cached `page`/`resolution` observations perform no IO or permission callback.

`choose` requires a resolved lookup, rechecks source READ/nonce and submits its exact raw key through the document's typed edit service. An empty resolved tag is usable; unknown, malformed, missing-dependency and unavailable results are not selectable. Inspecting an authored key never normalizes or replaces it. Selection stages a draft only: it does not save, publish resources, change runtime values or certify domain validity. Source admission occurs at submission, not as a registry lock held through later saving; owners retain complete current-context validation at their actual application boundary. This local controller requires the actual native owner thread for its source and document. It is not a remote bridge.

The experimental native `SettingsScreen` accepts explicit borrowed source bindings. Missing bindings leave manual text intact with an unavailable Browse state. Its auxiliary picker uses bounded explicit search, source-bound pagination, identifier inspection, preview summaries, Refresh and a separate Use selection action. A changed filter cannot reuse its old continuation. Refresh captures a new field/source target rather than reviving obsolete results. Returning preserves the parent editor; unexpected picker removal closes that editor while leaving source ownership intact. Large previews are visibly abbreviated without changing their data. Native runtime qualification is recorded separately in [verification](verification.md).

The [client JSON factory](client-json.md) can create reference actions under its ready actual owner and pass explicit bindings to a screen. The independent `ReferenceExample` prepares block/tag and explicitly enumerated client resource snapshots through public APIs; it owns when those sources are refreshed. Installing the library does not enumerate resources, register a menu or start source reload work.

## Native snapshots

`NativeReferences.captureRegistry` reads the caller's actual current/frozen registry on its owner thread. It captures registered IDs and optional named-tag membership, then returns a `Capture` containing only strings, collections, limits and the assigned stamp. `Capture.build()` can compile the bounded index on a worker. It retains no registry, holder, level, resource manager or renderer.

```java
var limits = NativeReferences.Limits.STANDARD;
var choices = new ReferenceActions<>(
    context, access, source, ValueTypes.text(limits.maximumKeyLength()),
    NativeReferences.valueType(limits).snapshotPolicy(), limits.index());
choices.bindAsync(reloadRevision, stamp -> {
  var captured = NativeReferences.captureRegistry(
      context, stamp, actualRegistry, true, limits);
  return context.onWorker(captured::build);
});
```

The caller owns actual reload/context lifecycle and closes the actions with its editor/source. Capture occurs explicitly when supplying/replacing data, rather than during typing or selector painting. The native default permits 512-character keys, 4,096 members per tag and 65,536 memberships across the complete source. Oversized sources refuse before publication. Review these defaults against the actual pack/source; raising a bound requires measured admission.

`NativeReferences.type` validates native identifier syntax while preserving the authored spelling. Lookup accepts the default namespace and separately normalizes IDs. Unknown IDs never fall through a defaulted registry's `get` method. Literal/resource results contain one canonical ID; tag results contain the admitted member IDs, including an empty list for an actual empty named tag. Preview data identifies the kind, registry and member count. Results are identifiers, not live native objects or domain acceptance receipts.

`captureOptionalRegistry` avoids invoking the supplied registry provider when its dependency is absent. The caller must also gate construction/linkage of any optional bridge; this helper cannot make an already-linked foreign class safe. `captureResources` receives explicitly admitted resource names. It does not enumerate files/resources, read their content, generate chunks or invoke a template compiler. Resource enumeration and deeper inspection remain explicit owner operations with their own costs.

Updates to native registry/tag APIs, resource enumeration, executors or reload lifecycle require requalification of default namespace normalization, malformed/unknown/missing/empty distinctions, whole-source exhaustion, copy ownership and off-thread compilation. Retain the actual native context instead of substituting a global registry. [Verification](verification.md) records measured fixtures and remaining limits.
