# Manual conflict review

`DocumentActions.conflicts()` attaches one `DocumentConflicts<T>` service to the actual editor. Creating it reads no source and changes no draft. `activeConflicts()` is a noncreating lookup. Explicit replacement changes presentation limits and retires the previous service; it preserves the editor. The service borrows the existing context, access, worker pool and actor rather than creating another session or file writer.

## Compare actual input

`preview(entryPoint)` prepares a complete bounded comparison from the actor's qualified observation. A cold workflow can complete its normal first-use load; preview does not continuously reread an already loaded file. Use the shared Reload operation to observe later external changes. A failed read preserves draft work but invalidates earlier source qualification, so its previous review cannot resolve.

An issued `Preview` identifies the exact editor/context/generation/version, observed revision, source description and diagnostics. Its `basis()` distinguishes the earlier observed baseline from a submitted draft during save reconciliation. Baseline input and a saved revision are different identities. Preview IDs are descriptive; only this service's actual issued instance is admitted.

Entries retain baseline, observed and draft input, including invalid text, inactive branches, omitted overrides and whole collections. Missing, null and empty input remain distinct. Control characters are escaped visibly. These descriptions are neither saved JSON nor patches. Collections and alternative shapes are chosen as whole values; the service introduces no row merge or inferred choice.

The standard preview budget admits 256 entries, 16,384 visited input nodes, 131,072 description characters and four retained preparations. Exhaustion refuses the complete preview rather than crediting a partial list. Its separate operation scope admits two definitions, four active operations and eight retained results, with bounded typed local inputs. Consumers may explicitly declare tighter or larger local bounds; these are not transport limits.

## Choose and resolve

`choose(preview, path, Optional<Choice>)` records a local selection or clears it. `choices(preview)` returns an immutable selection snapshot; `latest()` restores a retained current preparation across navigation. No choice is implicit. Every listed path must explicitly choose `BASELINE`, `OBSERVED` or `DRAFT` before resolution. Neither selecting nor resolving requires WRITE, because both concern a local draft. Every admitted action still checks fresh actual READ authority and the current editor/observation.

`resolve(entryPoint, preview, choices)` captures the exact displayed choice map, consumes the preparation and delegates to the actor's existing resolution boundary. It resets obsolete Undo history and typing streams. The result separately carries the resolved draft, owner validation and validation report. Invalid mixed values remain visible for repair. A validation failure after resolution does not mean the draft effect was rolled back. Cancellation after that effect must drain and report the actual result.

Resolution never saves a file, publishes resources or changes a running world. Explicit Save, Apply recipe or another owner operation retains its actual authority and receipt. A later external writer is checked by that owner's conditional Save rather than overwritten by resolution. See [editing](editing.md) for the primitive and [owners](owners.md) for source/application semantics.

`isCurrent(preview)` is a cached native-thread freshness check for presentation only. It performs no source read, validation or permission callback. It cannot grant execution authority; the admitted methods repeat those checks. Stale/foreign/evicted reviews, failed observations, replaced services and closed contexts refuse without choosing defaults or discarding the draft.

## Native screen

The generated settings screen exposes **Review conflict…** only when its actor has a review. Public `ConflictScreen` borrows those same actions and shows the source, comparison basis/revisions, diagnostics, all three inputs and explicit native choice controls. Measured rows, clipping, scrolling, keyboard focus and footer wrapping use the shared [presentation](presentation.md) components.

Back retains partial selections and the parent draft. Read saved/context values invokes the same explicit Reload service and requires fresh choices for the new observation. Resolve returns to the parent with the actual validation outcome; it does not press Save. Pending preparation/read operations can request cancellation. Once resolution enters, the screen waits for its actual outcome rather than presenting Back as rollback. Retired widgets cannot replay into a successor.

## Native commands

Every explicit `SettingsCommands.branch` also exposes this local service:

```text
<root> reload
<root> conflict preview
<root> conflict review <review-uuid> [row]
<root> conflict choose <review-uuid> <row> baseline|observed|draft|clear
<root> conflict resolve <review-uuid>
<root> conflict status <operation-uuid>
<root> conflict cancel <operation-uuid>
<root> save
```

Rows are one-based positions in that immutable issued review, not current collection indices. An individual row review shows its three alternatives; listing and feedback remain bounded and explicitly identify truncation. Native UUID/integer/literal parsing rejects malformed selectors. Copying the review ID grants no authority or access to another editor. Suggestions create no editor, source read or review.

Preview and resolution report actual operation snapshots. Choice commands report local selection status. A successful resolution with invalid owner validation produces a warning while retaining its draft effect; Save remains blocked. Status/cancellation address this review's operation scope, separately from document and import operations. The [shared bridge](operations.md) retains current-recipient and context checks.

Updates to source observation, actor acknowledgement, native focus/layout, Brigadier or owner validation require rechecking stale-read refusal, explicit complete choices, conditional Save, invalid retained resolution, navigation, cancellation/drain and context teardown. [Verification](verification.md) owns measured artifacts and limitations.
