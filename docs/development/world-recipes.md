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

## Native lifetime and update checks

Each helper permits one native screen and owns its shorter-lived context. `DocumentActions.owningContext` transfers that context's disposal to the editor; ordinary document actions keep their context borrowed. Closing/discarding the editor closes its session and context, while borrowed executors remain owned by the physical client lifecycle. The native helper clears its parent reference at closure.

World recipes borrow the same bounded two-worker/256-task physical-client pool as client preferences. Their active contexts have a separate 128-entry admission bound. Native shutdown closes both preference documents and recipe contexts before stopping the pool, continuing cleanup after individual failures. No configuration document is registered merely to borrow the pool. Core JSON/draft bounds, a 16-operation owner lane and the helper's operation limits remain independent of world-generation budgets.

Recheck `WorldCreationContext`, `DimensionsUpdater`, native UI notification ordering, preset-editor entry, immutable registry access, domain compilation guards and context disposal when updating Minecraft/NeoForge. Qualify actual replacement, unchanged dimensions, stale/same-value foreign contexts, callback failure, later typing, preview cancellation/drain and actual client shutdown. Use `native_form_probe.py --recipe-only` for the pinned native control.
