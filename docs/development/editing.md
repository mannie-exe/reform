# Editing model

The experimental core provides pure edit sessions for current scalar, record, list and custom value types. It performs no persistence, authorization, runtime publication or game operation. [Coverage](coverage.md) owns the remaining Phase 1 scope, including richer value families, actual owners and native presentation.

## Drafts and candidates

`DraftTree` is immutable. `DraftNode` retains raw scalar text, record fields, ordered rows, opaque custom/unknown data, missing values and explicit null separately. Invalid or incomplete text never constructs a partially valid owner object. An empty text value can be valid; an absent required field or null for a non-null type cannot. Wrong saved scalar kinds remain visible rather than being coerced. Unknown fields are retained and block a candidate until explicitly resolved.

`DraftTree.fromParsed` preserves original number spellings through unrelated edits. Editing accepts raw numeric text separately from its JSON representation; admissible non-JSON decimal spellings are normalized only when producing candidate JSON. The raw draft remains intact. Custom types currently use an opaque bounded JSON branch; a custom editor and richer nested diagnostic mapping require their explicit extension work.

Rows have random session UUIDs independent of index or serialized value. Moving a row preserves its ID; removing and replacing it does not reuse the old ID. IDs never enter authored JSON. `SourcePath` addresses encoded property/index locations; a candidate freezes the mapping from those positions to stable editor paths. An asynchronous diagnostic follows its original row after movement, becomes stale after a value change and reports removal rather than redirecting to a new row at the same index.

Inspection is explicit and pure. Leaf checks describe empty, incomplete, invalid, unavailable or valid input; complete document/cross-field validation is still required before a `Candidate<T>` exists. Candidate values and source maps own their state. A candidate is neither permission nor a persistence receipt. Declared validators/snapshot callbacks must be pure, thread-safe when used on a worker, and bounded; this API cannot sandbox consumer code.

`DraftLimits` bounds depth, nodes and retained characters before a tree replaces session state. Defaults allow depth 128, 16,384 nodes and 131,072 characters. History defaults to 128 entries and a logical retention cost of 1,048,576. Cost counts characters, structural nodes and entry overhead conservatively; it is not measured heap bytes or a wire limit. Owners must also choose appropriate `JsonLimits`. Opaque JSON is structurally admitted during inspection rather than masquerading as one validated leaf.

## History and lifecycle

`EditSession<T>` belongs to its creating thread. Its immutable `Snapshot` may be inspected on an admitted worker when the definition callbacks support that contract. There is no global executor, timer, file watcher or screen reference.

Field typing coalesces only within an explicit `EditGroup`. Compound imports use `stage` as one undoable draft operation. Undo/redo, reset to defaults, revert to the observed baseline and reload are distinct. No-op reset preserves redo and unchanged row identities. Moving identical-valued rows can still be an intentional identity change; authored equality alone does not erase that history.

History is bounded and its oldest retained entries may be removed. A normal edit clears redo. Explicit `discardHistory()` clears only the journals, retaining draft, baseline and pending operation; it can permit reconciliation after an over-budget historical branch without discarding current work. Reload replaces the observed baseline, clears history and invalidates old work; it is not an undoable edit. Close invalidates pending callbacks and clears history/operation references. It does not write, cancel an external operation, roll back a backend or promise that a still-retained session object contains no draft data.

## Submission and acknowledgement

`prepareSubmission` validates the entire current draft and freezes one `Submission<T>`. It binds definition, session/context UUIDs, baseline generation, draft version and the opaque observed saved revision. A submission is prepared before external effects; `markInFlight` and backend acknowledgements are explicit. The owner must still recheck authorization, context and persistence preconditions at its actual effect boundary.

Only one submission is pending at a time, while later editing remains available. A prepared operation can be discarded before dispatch. An unknown outcome blocks another submission until actual reconciliation; this model does not blindly repeat a write or infer unchanged disk from an exception. Confirmed unattempted/refused/failed results release the pending slot only when that classification is established by the backend.

Acknowledgement accepts an actual observed value/tree and saved revision. Equal typed saved values can normalize authored spelling and retain submission row identities. This positional alignment applies to the currently supported ordered lists; it is not a matching algorithm for unordered sets or changed collections. Known later field edits remain intact; changed collections stay whole. Both history journals and the resulting draft are prepared and checked before baseline advancement. A failed observation/rebase preserves all of them and retains an observation-unavailable pending state. A backend may already have persisted; this state does not claim otherwise.

If the actual saved value differs and later edits exist, the draft remains intact and an explicit review is required. Without later edits, the observed value becomes current and redo is rebased onto that observation. Reload or close makes old acknowledgements stale; they cannot change the replacement session baseline. Foreign session/context tokens cannot target this session.

## Manual conflict review

`reviewConflict` binds baseline, observed and draft trees to an actual observed revision and current session version. Each changed record field needs an explicit baseline/observed/draft choice. Collections, opaque data and incompatible structures are whole-value choices. Automatic structural merging is not implemented. An acknowledgement-created review uses the submitted tree as its comparison baseline; an ordinary conflict review uses the session's previous observed baseline. Presentation must identify that basis honestly.

Choices are checked as one complete bounded change. Resolution updates the observed baseline/revision, preserves the selected draft and clears obsolete history. Any edit after review invalidates its choices. Resolution neither saves nor guarantees validity: complete validation remains mandatory on the next submission. Missing, invalid or unknown observed data may be reviewed without inventing a valid backend result.

The [independent headless consumer](../../examples/headless/src/main/java/example/ReviewProfile.java) exercises invalid drafts, history, delayed acknowledgement, explicit review and reload using only the published local artifact. It is not a native GUI, file-owner or consumer migration fixture. [Verification](verification.md) records its exact qualification.
