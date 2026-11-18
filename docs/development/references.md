# Reference sources

`ValueTypes.reference(element, source)` adds selector metadata to an existing text/choice `ValueType`. It preserves that type's codec, snapshot policy, authored spelling and structural draft path. It performs no lookup while decoding or drawing a field. Structurally valid unresolved keys remain editable; the owner decides which reference checks are required for validation, publication or admission. Existing native registry codecs still need their actual registry context.

The current implementation supplies reference data and shared local operations. Native pickers, draft-bound response presentation and searchable multi-selection remain required by [coverage](coverage.md).

## Complete admitted data

`ReferenceResolver<K,V>` returns `ReferenceResolution<V>` and bounded `ReferencePage<K>` suggestions. Resolution distinguishes resolved, unknown reference, malformed syntax, missing dependency and unavailable context. A resolved empty collection is a real value. A ready empty index is different from an unavailable source. Namespaces identify resources, not installed mods; an unknown namespace alone cannot establish a missing dependency.

`ReferenceStamp` binds the actual owner context, logical source, unique snapshot nonce and descriptive revision. Rebuilding the same revision label produces a different nonce. Each page and result retains its stamp. A continuation also binds the exact filter and next candidate offset; an empty filtered page can legitimately have more candidates. `examined` reports candidates examined, without inventing a total match count.

`ReferenceIndex` owns immutable copies of an explicitly admitted complete collection. It requires unique keys and encodings whose equality/hash survive copying and codec roundtrips. Rows sort by their encoded keys, with locale-independent case-folded text filtering. Producer exhaustion refuses the whole index; a truncated source cannot report an unknown key as a complete lookup. Lookup and pages return independent owned values/keys and immutable presentation metadata.

`ReferenceLimits.STANDARD` admits 16,384 entries, 1,048,576 aggregate text characters, pages of 64 choices, 128-character filters and at most 4,096 examined candidates per search. Snapshot depth/visit limits are separate. These are local index/presentation policies, not network, file or universal domain-value limits. Callers select source-specific bounds and explicit snapshot policies. Pure custom providers must bound their own work; an interface cannot sandbox consumer code.

## Actual-owner operations

Construct `ReferenceActions<K,V>` with the real `OwnerContext`, its actual `Access`, logical source, key type, result snapshot policy and limits. Its `resolve` and `search` services use the existing [operation scope](operations.md), require READ and perform lookup on admitted workers. They check fresh authority, cancellation, source identity and context again before delivering an owned result. Choice requests can include the expected snapshot nonce. Providers cannot return foreign stamps, duplicate keys, oversized presentations or continuations inconsistent with examined candidates.

`bind` invalidates the previous source before building a complete replacement. `bindAsync` allows native capture at entry, worker compilation and fresh native publication. Until publication, the source is unavailable. Failed, reentrant or late producers cannot resurrect an old source or overwrite a newer binding. `invalidate` publishes an explicit missing-dependency or unavailable state. Context closure clears the provider and refuses new work; running operations drain through their retained handles.

These services bind source/context freshness. A frontend must also associate responses with its actual field/stable row, raw key, draft version and request identity. Editing, branch/profile switching and disposal must reject obsolete presentation. A response must never edit a replacement field or silently normalize the authored key. The current primitive screen preserves reference text without providing this picker/controller yet.

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
