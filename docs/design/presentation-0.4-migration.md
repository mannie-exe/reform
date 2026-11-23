# Presentation 0.4: migration and acceptance

This document accompanies the proposed [presentation and navigation contract](presentation-0.4.md) and [construction/lifecycle API](presentation-0.4-api.md). It specifies later implementation and consumer migration; it does not activate either. Current product source, versions, examples, and qualified behavior remain 0.3. No prototype, candidate, screenshot, illustration, native run, consumer pin, or release is produced in the selected documentation stage.

## Public API responsibilities

The following is a type index, not Java code available to compile. The [construction contract](presentation-0.4-api.md#construction-and-admission) defines inputs, admission, and results. Prefer evolving existing types where their ownership remains correct; avoid creating a second form framework under these names.

```text
core.presentation
  ThemeValues                         immutable complete semantic values
  ThemePatch                          typed partial overrides and explicit reset
  ThemeScope.resolve(parent)          one immutable resolved presentation
  DestinationId                       stable namespaced presentation identity
  DestinationGraph                    rooted bounded hierarchy, alias validation

minecraft.client
  ClientWorkspacePresentation        identity, header, navigation, Home, theme
  WorkspaceIdentity                   title, optional subtitle/version/logo
  HeaderComposition                   required identity/location and optional art
  NavigationComposition               shared trail/search/filter/tree placement
  HomePage                            default or explicitly composed landing body
  ClientDestination                   typed owner/field/view/result/recipe target
  ClientNavigation.inspect/open       cached capability and actual guarded reveal
  NavigationBookmark                 retained location, focus, filter, and viewport
  ClientControlAdapter                native interaction contract for a real replacement
  ClientGraphic / ClientGraphicView   subject description and mounted viewport
  ClientGraphicProvider<S>            capability, explicit preparation, disposal
  DraftPreviewInput<T>                actual read-authorized complete candidate
  PreparedGraphic                    generation-stamped owned lease
  RecipeLinkRequest                   view-only request with explicit capability

game-version client seams
  NativeGraphicAdapter<S>            typed native preparation/submission/retirement
```

Native identifier spelling remains in the actual game adapter: 1.21.1 uses its `ResourceLocation` APIs and 26.3 its `Identifier`/extraction APIs. Do not expose a raw native rendering context as a portable common contract. A neutral destination does not carry a registry, level, or third-party recipe object.

## Migration map

| Existing contract                                                                                                       | Decision                           | Migration                                                                                                                                                 |
| ----------------------------------------------------------------------------------------------------------------------- | ---------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Definitions, codecs, owner sessions, files/native serializers, effects, operations, batches, and source/revision checks | Keep                               | No data/schema/path/default/permission/timing changes from presentation                                                                                   |
| `ClientFileSettings`, registered native sources, recipes, and server bindings                                           | Keep                               | Bind the new presentation through the same admitted form/workflow factory                                                                                 |
| `ClientEditor`, `SettingsForm`, `DocumentActions`, submission guard, and retained invalid input                         | Keep                               | All custom views share these actual actors and submit through their existing services                                                                     |
| `FormCatalog`, ranges, numeric display/domain/captions, dependencies, reference bindings                                | Keep                               | Preserve purpose/supplements, exact values, missing states, and full owner validation                                                                     |
| `FormLayout` rows/groups and `ClientWorkflowLayout` sections/placements/reviews                                         | Keep semantics; extend composition | Add typed destination hierarchy and shell declarations without changing owner grouping or save scopes                                                     |
| `FormTokens` static native defaults                                                                                     | Evolve                             | Make current values the default immutable theme; internal measurement/drawing resolves the same scoped values. Do not keep a parallel hardcoded renderer. |
| `ReferenceTitles` and shared `ReferenceSelection`                                                                       | Keep                               | Rich list/detail views consume the same copied title snapshot, source stamps, selection admission, and manual-ID repair                                   |
| `NavigationHistory` and field/section bookmarks                                                                         | Reuse                              | Retain bounded visit history; add typed destinations and native-view state without creating another edit history                                          |
| `FormCanvas`, game-specific `NativeCanvas`, mounted panel/viewport                                                      | Extend internally                  | Add prepared graphic submission and scoped presentation while preserving version-specific drawing/extraction                                              |
| Existing centralized information, availability, review, conflict, and operation views                                   | Keep effects and facts             | Use the shared theme/navigation host and actual origin; do not copy their business rules into consumer pages                                              |
| Ad hoc consumer screen coordinates or custom renderer used only for layout                                              | Replace where present              | Use shared shell/entry/slot primitives. Genuine domain providers remain consumer-owned.                                                                   |
| Centered-title-only workspace as implicit default                                                                       | Replace default composition        | Use A without a user-facing legacy/new UI mode. Equivalent custom composition can use shared slots, not old renderer code.                                |

There is one supported rendering and navigation path after migration. Clean break does not mean deleting correct owner services or renaming every type. A replacement is justified only where the old contract conflicts with the new one. List actual source breaks in the eventual release notes; do not infer them solely from these proposed names or promise ABI compatibility before implementation.

Consumers needing the new API require a stable minimum of 0.4.0 and an independently declared compatible upper bound. Exact development coordinates, if later selected, are separate admitted exceptions; they cannot be the stable range floor. Loader metadata, runtime checks, build pins, examples, and public resolution must agree. This document changes none of those inputs now.

Migration must update construction, loader metadata, runtime admission, build pins, and release examples together. Class linkage alone does not prove owner behavior. Introduce the common shell without replacing the file/native/server/recipe bindings; then migrate presentation declarations and actual navigation paths. Remove a superseded path only after its replacement preserves that path's owner and interaction contracts. This includes read-only draft capture: consumers must not reach into a private actor or misuse `canSubmit()` to reproduce it.

Retire every newly opened consumer view, preview lease, and callback if its migration fails. Preserve previous saves and drafts. Do not install an automatic fallback that loads public 0.3 after new API linkage fails. Consumers retaining an older released dependency stay on that version deliberately; the new implementation cannot claim their production migration from an independent fixture.

## Consumer declarations

### Default library use

A consumer supplies its actual forms/layout and identity, then accepts `ClientWorkspacePresentation` defaults: compact A header, hierarchy-derived location, native rows/groups, default Home, and no preview preparation. A single-document form gets a coherent minimal location without a fabricated owner. A workflow lacking artwork remains complete. No new runtime settings file or environment/JVM override is created.

An explicitly selected initial route is allowed. It does not erase Home from the hierarchy or bypass its owner's admission. Opening the workflow still does not eagerly read every source.

### Lootr: Styled

The prospective declaration adds a profile/details body and graphic provider to the existing workflow. Models and Artwork remain independent field bindings. The catalogue, title cache, compiler, resource publication, application results, and exports retain their current owner.

```text
workflow identity: Lootr: Styled
header: default A, stable for Home / Appearance / Profiles
Home body: default landing or owner-authored splash-like introduction
Appearance: existing retained fields and operations
Profiles: catalogue-backed search + highlighted details
  choose for Models  -> existing model reference selection
  choose for Artwork -> existing artwork reference selection
  prepare preview    -> explicit isolated owner preparation
  preview origin     -> Loaded or exact draft/source/resource generation
```

This is a declaration contract, not an authored illustration or a candidate binding. Native isolated profile preparation is an engineering requirement of the later preview implementation; ordinary loaded resources cannot be relabelled as a draft preview. An unavailable draft provider must leave current selection/manual repair and explicit save/reload behavior usable. No profile configuration format, export meaning, or default resource stack changes are selected.

### Skyweave Compat

The prospective baseline is the existing composed catalog and native owners with the A shell, Home, hierarchy, and shared destinations. Preserve local-files versus running-server context admission, startup switch source identity, terrain guards, native serialization, same-actor aliases, field facts, and current operation services. A body link to a server policy does not authorize it or change the document acted on by Save.

No illustrated Wayfinder entry, glass example, terrain diagram, or encounter preview is required initially. Those remain optional future consumers of the graphics/link primitives. Do not take or compose screenshots or add domain examples merely to populate the specification. A minimal generic owner-backed field/operation journey is enough to specify the shared contract; actual future bindings still need native qualification.

### Skyweave and other consumers

Skyweave's existing public 0.1 production baseline and separately deferred actual-menu/recipe adoption remain outside this work. Its recipe/source admission and immutable domain compilation cannot be inferred from Compat or Lootr qualification. No sibling checkout/pin changes or agent activation follow from this document. Other consumers use the same public presentation contract; no dependent mod becomes a required library dependency.

## Later implementation order

1. Add immutable semantic theme resolution, scoped measurement, slot invariants, and the default A host. Preserve the existing body and owner state rather than building a new editor.
2. Add the rooted destination graph, Home body, breadcrumb overflow, typed links, asynchronous navigation admission, and return-state retention. Keep the header stable through ordinary navigation.
3. Add local resource/icon/item preparation and version-specific submission/disposal. Add supported block/block-entity and curated entity providers under explicit capability contracts. No generic scene engine.
4. Design and implement the isolated native resource boundary needed by the selected Lootr preview, with the Lootr owner. Distinguish baseline library preparation from domain compilation and resource publication.
5. Add optional view-only JEI/EMI adapters on actually available targets, and limited native-book context handling. Keep baseline absence usable.
6. Migrate owner-selected consumers, retire superseded presentation paths, and qualify packaged inputs. Do not wait for unrelated Skyweave world-generation work to release independently qualified library work.

No step is activated by this documentation-stage completion. A later implementation selection must explicitly name the consumer scope and qualification expectations. Public delivery and personal rollout retain their own authorization.

## Acceptance matrix for later implementation

The matrix below defines observable checks. They have not been executed against 0.4. Source inspection and documentation checks cannot turn them green.

| ID        | Case                                                               | Required observation                                                                                                                                 |
| --------- | ------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| SHELL-1   | Default A, long identity/path, wide/narrow/high scale              | Usable identity/location/navigation/body/actions; no overlapping text, clipped focus, or lost information                                            |
| SHELL-2   | Ordinary Home/category/document/field navigation                   | Same declared header composition, identity/artwork policy, owner actors, and source bytes; no page-driven hero/header replacement                    |
| HOME-1    | Default Home with unopened/unavailable owners                      | Useful destinations without reading unentered files, granting authority, or preparing domain previews                                                |
| THEME-1   | Parent/child/variant/instance overrides and resets                 | Deterministic resolution; same values measured and drawn; explicit default versus inherited reset                                                    |
| THEME-2   | Language/font/resource reload and contrast/motion variants         | Native inputs/caret/focus retained; Unicode information and state meaning remain readable without color/artwork                                      |
| CONTROL-1 | Custom wrapper/adapter, disabled state, nested input               | One semantic edit/action; no double activation, admission bypass, or loss of invalid text/narration                                                  |
| NAV-1     | Duplicate/cyclic graph, aliases, removed collection row            | Reject invalid declarations before owner entry; one canonical owner; missing row never becomes another row by index                                  |
| NAV-2     | Back/Forward/Up, filter-hidden field, overflow menu                | Distinct history/ancestry; preserved query/filter/bookmark; every ancestor accessible by keyboard and narration                                      |
| NAV-3     | Two async selections, absent owner, stale permission/context       | Latest destination mounts; old results do not steal focus; drafts remain; cached visibility never replaces actual admission                          |
| LIFE-1    | Auxiliary/recipe return, disconnect, shutdown, forced close        | Correct borrowed lifetime and return state; all resources released; no owner resurrection or rollback claim                                          |
| GRAPHIC-1 | Missing local asset, no-world subject, unsupported provider        | Useful typed unavailable/error state and retained text/control; no silent fallback claiming equivalent rendering                                     |
| GRAPHIC-2 | Still/explicitly rotated item/block/curated entity                 | No inventory/world/entity gameplay changes; native clipping, focus controls, and adjacent widgets remain correct                                     |
| GRAPHIC-3 | Rapid draft changes, cancel, resource reload, late prepare         | Current stamps only; obsolete work drains/disposes; no per-frame parsing, discovery, decoding, or entity allocation                                  |
| GRAPHIC-4 | 1.21.1 immediate drawing and 26.3 extraction failure paths         | Native renderer state restored; extracted resources survive actual consumption and retire once; dedicated server resolves no client/provider classes |
| LOOTR-1   | Model/artwork selection, missing/manual IDs, prepare/reload/export | Same actor/admission/file contract; truthful Draft/Loaded generation; no hidden persistence/global reload for preview                                |
| LINK-1    | Provider absent/not ready, no recipe, dual viewers, return         | One admitted view-only action, deterministic choice, useful outcome, retained origin; no craft/transfer/unlock                                       |
| OWNER-1   | Invalid draft, defaults/undo, source conflict, pending effect      | Existing guarded save/review/default/reload/close semantics and complete document scope preserved                                                    |
| COST-1    | Default/extreme input, repeated routes/reloads/close               | Measured preparation/paint/input and retained-resource costs; finite budgets and no unexplained growth                                               |

The following cases close the construction and lifecycle gaps in the initial specification:

| ID        | Case                                                                                 | Required observation                                                                                                                                   |
| --------- | ------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| DECL-1    | Invalid graph/theme/provider declarations and absent optional owner                  | Invalid declarations refuse before editor/resource entry; valid unavailable owners keep useful destinations and reasons; no leaked partial view        |
| SHELL-3   | Short/long/static/dynamic location changes at one viewport/font/theme                | Header height and identity/artwork bounds remain fixed through ordinary navigation; overflow preserves full location and keyboard access               |
| THEME-3   | Consumer semantic overrides plus standard/custom component variants                  | Variant defaults do not reset inherited semantic roles; deliberate instance patches remain local; duplicate patch operations refuse                    |
| PREVIEW-1 | Unaccepted native text plus valid sibling edit                                       | Preview capture refuses without dropping either input, saving, reloading, or presenting the actor's older state as current                             |
| PREVIEW-2 | Read-only permission and required save-review owner                                  | Valid complete capture uses READ and exact draft/source checks; missing write authority or a save-review requirement alone does not prevent inspection |
| PREVIEW-3 | Source/draft/native-input edit during capture and resource reload during preparation | Structured stale/refusal result using the input epoch even for rejected text; no old lease mounts; draft/loaded provenance remains truthful            |
| GRAPHIC-5 | Close/reload immediately after extraction or native submission                       | No new uses after close; resources remain owned through consumption, retire once, and leave borrowed game resources open                               |
| GRAPHIC-6 | Temporarily ignored cancellation, rapid requests, budget refusal, cleanup exception  | Active slot/resources remain accounted until terminal; bounded superseding work; finite budget; sibling cleanup completes with retained diagnostic     |
| MIGRATE-1 | New factory/admission wiring and deliberately older consumer baseline                | One renderer and owner path for migrated bindings; unsupported dependency refuses; unmigrated consumers identified explicitly                          |

These are design obligations, not tests run in this documentation stage. Read-only capture must be exercised separately from write submission. Native retirement needs the actual adapter lifecycle; a timer or assumed frame delay is insufficient evidence. GRAPHIC-5/6 must include permission loss, a closed editor executor, and actual renderer shutdown, proving that owned cleanup can finish without restoring editing authority or dropping outstanding native uses.

Qualify the baseline separately on Fabric/NeoForge for 1.21.1/Java 21 and 26.3/Java 25. Resolve optional providers per actual target; a provider's absent path is always required, and its present path only counts for tested versions. Use independent packaged consumers and actual entry points. Representative rendering is not universal modded-renderer support. Layout geometry is necessary but does not establish aesthetic acceptance.

Screenshots, mockups, illustrative examples, and desktop work are excluded from the selected reification stage. Later implementation should agree the smallest useful native visual review with the owner; do not generate a gallery or require authored illustrations by default. Preserve exact artifacts and causal negative cases when that stage is selected.

## Documentation-stage completion

The specification settles default A, stable ordinary-navigation headers, separate splash-like Home, shared customization/navigation/graphics responsibilities, truthful Lootr isolation, optional-provider behavior, migration ownership, and future acceptance. Reference/source observations establish feasibility and identify native seams; they do not establish product correctness or resource budgets.

The design is ready for a separately selected implementation of the specified contracts; no unresolved owner UX choice blocks that start. Implementation must settle source-compatible overload spelling and actual native hooks, measure resource limits, qualify renderer/provider behavior, and migrate selected consumers. These are required engineering gates, not permission to substitute a smaller preview or infer consumer adoption. Material's JavaScript-only states page remains an uninspected reference; no design claim depends on having read it.

Before claiming Lootr draft-preview delivery, prove isolated native model/texture preparation and retirement on the selected targets. An unsupported provider keeps the form usable, but does not satisfy a selected preview requirement. If actual native constraints require dropping or changing that capability, return the scope decision to the owner. Final owner visual approval belongs to later authorized native review; no gallery or illustrative assets are implied now.

Phase 2's expressions, settings inheritance/derived-value engine, automated merge, remote administration/sharing, ingredient/color value editors, and binding alternatives remain deferred. Theme inheritance and recipe ingredient requests here are presentation/link contracts, not activation of those broader settings families.
