# Unsaved world recipes

`WorldRecipe.worldRecipe(parent, original, definition, domain, compiledSnapshots)` constructs an editor for an actual native `CreateWorldScreen` and its original `WorldCreationContext`. It is physically client-only. It creates no file, initializes no world and grants no authority over an existing world. The independent flat-world example passes actual native creation/widget/admission/shutdown checks. [Verification](verification.md) distinguishes that bounded fixture from arbitrary generators, remaining native cases and real consumer migration.

## Domain and source ownership

The consumer declares a typed recipe with defaults/constraints and a `WorldRecipe.Domain<T, P>`. Its native `read` captures current authored values from the exact creation context. Its worker `compile` validates/compiles immutable candidate values into bounded runtime inputs. `compiledSnapshots` explicitly owns those inputs; declaring an object immutable requires its complete reachable state to be immutable. Compilation must not read widgets, live worlds or rendering state.

Native `dimensions` receives the original context, actual frozen registries, current `WorldDimensions` and compiled inputs. It returns the owner's complete replacement dimensions, preserving unedited dimensions and generation guards. The factory applies them with native `WorldCreationUiState.updateDimensions`. Optional `admitted` runs after that actual update for owner flags such as accepting an explicit recipe. Domain hooks own generation semantics; re:form supplies no generator, world survey or generic registry rebuild.

The original context is checked by object identity, including after permission callbacks and before effects. Matching seeds, labels, field values or schema versions do not make a replaced context current. Successful admission binds the next observation to the actual replacement returned by the native owner. A callback that throws after entry has an unknown application outcome; the source stops new effects pending a fresh native-context review. No callback failure claims rollback.

`ContextRecipeOwner<T, C, P>` supplies the Minecraft-independent owner implementation for an actual immutable creation context. Its `Domain` captures native identity/values, compiles on the worker and admits on the native executor. Contexts and callbacks are explicit, not discovered through a global current-world variable. The ordinary native factory uses this owner without requiring an addon to provide executors, an editor/session or file infrastructure.

## Editing and truthful receipts

The owner declares `DocumentSession.CommitKind.CONTEXT`. `DocumentActions.admit` and the generated form's **Apply recipe** control invoke that operation. File `save` is refused for a context source; application-only retry from saved bytes is unsupported. `EditorActor.Commit` carries the actual receipt and independent editor acknowledgement, whether the source is a file or context.

Successful context admission reports `persistence=NOT_ATTEMPTED`, no saved-file revision and `application=APPLIED`, with a `NEW_WORLD` requirement. APPLIED means that the unsaved creation recipe changed, not that chunks were generated. The owner explicitly acknowledges its new baseline; later edits remain separate. Editor snapshots/submissions call that binding `sourceRevision`, independent of persistence.

An entered but unconfirmed admission reports its actual application uncertainty and keeps unknown-outcome pending state. It cannot be blindly retried. Confirmed admission followed by permission/context loss retains the known admission fact without leaking a new observation.

Factory defaults, imported profiles, validation, profile saving, in-memory export, resource publication and creation admission remain distinct operations. Loading defaults stages a draft; definition defaults never silently replace the selected generator. Saving a reusable profile requires its separately declared persistence owner. Neither an editor nor its Apply control rewrites an existing world's generation identity.

## Explicit defaults and profile staging

When the view is only a projection, supply an owned defaults loader as the last `worldRecipe` argument. Its worker callback receives the immutable native creation context and returns `ContextRecipeOwner.Defaults` with an exact full-source revision, provenance, editable projection and bounded compiler over the retained full source. The domain supplies the bytes and input limits. It must freeze the admitted source and preserve any unedited dimensions and registry identity during compilation.

**Load defaults** uses the same document operation as the other editor actions. It changes only the draft. Undo, redo, ordinary edits and value-only imports preserve its selected basis. Apply, explicit Validate and Preview compile the projection over that basis using the actual context. A changed context or late draft edit refuses stale loading. Basis-only changes count as draft changes even when the visible values match. Inspection reports the selected source, and editable JSON exports the projection. A full-source export remains a domain operation.

Each recipe owner retains up to eight distinct default revisions and reuses an admitted revision within its session. It refuses further new revisions and releases the retained inputs on successful admission or closure. The raw draft carries a small identity and source description. Session and native-context checks bind that identity to its real owner. A successful admission ends the old basis history and keeps subsequent field edits under the newly admitted recipe. The bounds are an identity count and a domain input contract, with heap and latency depending on the supplied compiler inputs.

`WorldRecipe.importCatalog(sources)` supplies this actual recipe definition's standard bounded value/envelope import formats. `screen(title, catalog)` declares those admitted sources on the same generated form; it checks the exact definition before constructing a view. For example:

```java
var source = defaults.savedValueSource(sourceId, "Saved new-world defaults");
var catalog = recipe.importCatalog(List.of(source));
var form = recipe.screen(title, catalog);
```

The defaults document and creation recipe may have different logical IDs while their explicitly declared import format admits compatible authored values. No conversion is inferred from matching Java class names or filesystem paths. Target constraints still validate the whole imported candidate.

The shared Import view lists registered source metadata without reading it. Explicit Preview captures saved values, checks them against the current draft and presents typed differences. Stage changes one undoable draft and preserves earlier work through Undo/Redo; it performs no file save, domain admission or native dimension update. Apply recipe retains the original context, compilation, generation and authority checks. Saving updated defaults while this recipe is open never installs them implicitly. A later changed or malformed source cannot silently alter an already captured import; refresh explicitly to capture different content.

`SettingsScreen.withImports(catalog)` supplies the same declaration to an advanced generated form before activation. It does not create another owner or replace defaults, constraints, operation state or native source authority. [Local import](imports.md) owns review/staging semantics.

## Explicit recipe previews

The advanced `screen(title, sources, presentation, fieldEditors, imports)` overload composes the same generated controls, presentation rules, catalog, auxiliary editors and optional imports under the recipe's original owner. It retains any declared preview. To bind references to the initial creation context, construct their `ReferenceActions` through `recipe.references(source, keys, values, limits)` and prepare the actual immutable index explicitly. The original context must still be current each time those sources are resolved or disclosed. Closing the recipe closes its reference services.

Before creating the form, opt in with `recipe.withPreview(new WorldRecipe.Preview<>(title, analyze, snapshots, describe))`. The form offers **Preview…** once the current draft is accepted and valid. The operation captures its exact source revision and draft, rechecks complete validation and compiles the same immutable inputs used for admission. The analyzer receives those inputs and an `OperationScope.Execution` on the worker. It declares its own workload bound and uses the supplied progress and cancellation checkpoints. The client admits one heavy preview at a time, leaving another worker available for ordinary settings work.

The owner freezes the result through the supplied snapshot policy and rechecks the actual creation context before returning it. The presentation callback projects the bounded result on the client thread. **Back** returns to the editable draft while the preview runs. Reopening the result retains its original identity and labels older draft results. Starting a new preview is explicit. Cancellation leaves the operation active until the provider settles. Changing the native creation context ends disclosure of the old preview.

An analyzer should work only with its immutable compiled inputs, bounded local data and cooperative checkpoints. It has no native live-world or recipe-application authority. Opening the form, typing and inspecting an existing result do not start a terrain analysis. **Apply recipe** independently validates, compiles and admits the current draft through the native owner.

## Complete recipe persistence and export

Declare a `WorldRecipe.ReviewedAction<P, O>` with `withReviewedAction` before constructing the form. Supply an owned operation ID, action label, `PERSIST` or `EXPORT` effect, native availability check, effect callback and receipt presentation. The action appears in the existing Review view next to the ordinary projection export. Entering the editor or opening Review performs no owner effect.

The operation recompiles the exact reviewed draft and its retained full source, validates it through the recipe owner and checks the actual native context and draft on the client before calling the effect. The callback receives `ReviewedInput<P>` with the draft identity and complete immutable compiled value. Its availability check runs on the client and must be cheap and free of effects. A callback that defers before submitting an effect can call `input.requireCurrent()` on the client while the operation is unsettled. The provider owns its destination authority, exact serialization, write/revision checks and verified receipt. Dispatch expensive serialization and storage work away from the client thread and report the actual outcome if a write has already started.

Up to four actions share a single active slot and eight retained operation receipts per recipe. Returning to Review can reopen a retained result for the same capture. Repeating an effect is explicit. Cancellation is cooperative. The generated form neither modifies the native creation context nor acknowledges its draft when these actions finish. The separate **Apply recipe** control still admits to the current unsaved context. The owner's full serializer has its own bounds, which can exceed the editable JSON projection's limits.

## Native lifetime and update checks

Each helper permits one native screen and owns its shorter-lived context. `DocumentActions.owningContext` transfers that context's disposal to the editor; ordinary document actions keep their context borrowed. Closing/discarding the editor closes its session and context, while borrowed executors remain owned by the physical client lifecycle. The native helper clears its parent reference at closure.

Before opening the form, `returnAfterApply()` preserves a preset editor's direct return to Create World. It returns once native admission is confirmed and the current form has no unpublished edits. Failures and later draft edits stay in the form with their actual outcome.

World recipes borrow the same bounded two-worker/256-task physical-client pool as client preferences. Their active contexts have a separate 128-entry admission bound. Native shutdown closes both preference documents and recipe contexts before stopping the pool, continuing cleanup after individual failures. No configuration document is registered merely to borrow the pool. Core JSON/draft bounds, a 16-operation owner lane and the helper's operation limits remain independent of world-generation budgets.

Recheck `WorldCreationContext`, `DimensionsUpdater`, native UI notification ordering, preset-editor entry, immutable registry access, domain compilation guards and context disposal when updating Minecraft/NeoForge. Qualify actual replacement, unchanged dimensions, stale/same-value foreign contexts, callback failure, later typing, preview cancellation/drain and actual client shutdown. Use `native_form_probe.py --recipe-only` for the pinned native control.
