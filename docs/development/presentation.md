# Measured presentation

The current presentation supplies native-free `FormGeometry`/`SectionNavigation`, an internal client-only `FormPanel`/`ControlSizing` and an experimental public `SettingsScreen<T>` over an admitted owner-backed scalar/record actor. Native client qualification uses JSON; native registered settings separately exercise the shared actor headlessly. Public composition/convenience factories, additional editors and complete operation presentation remain required. [Coverage](coverage.md) owns the complete Phase 1 remainder.

These parts derive from the owned MIT implementation in Skyweave: Compatibility Patches, as identified in `NOTICE`. The extraction changes packages/type names, library translation fallbacks and the location of the native text-reflow helper. Compat still uses its original implementation until the actual consumer migration; that mod and the modpack are not library dependencies.

## Keep one measurement model

`FormGeometry.controls` preserves authored control order, clamps an oversized control to the admitted row width and wraps controls into subsequent lines. Empty control rows have no phantom height. Paired columns require an explicit shared relationship and enough measured width for both minimum sizes; equal values or neighboring fields do not imply a relationship.

`SectionNavigation` binds logical section identities to measured positions. Explicit clamped jumps retain their selected destination until manual movement. Bookmarks preserve section-relative offset and explicit end-of-document position through a reflow. Removed section bookmarks fall back to the start; duplicate and unknown section operations are explicit errors.

The internal native panel measures labels, summaries, widgets and group boundaries using the actual native font and widget dimensions. Rendering, clipping, hit testing, focus scrolling and section anchors use those positions. Native widgets retain their input and narration behavior. Control resizing triggers native text reflow without replacing its raw value/caret/selection. The panel has no document ownership, parser, settings writer or context authority.

## Generated form

`SettingsScreen` takes its parent, title/context label, definition and already admitted `DocumentActions`. It has no implicit actor lookup, initialization or file ownership. The caller owns the shared document/context; closing the screen closes its actions and editing actor/session. The [client JSON factory](client-json.md) supplies that common wiring for the [independent native example](../../examples/native/README.md). `editorView()` exposes cached read-only state to composed/native observers without returning an editing or persistence capability.

The form recursively exposes declared records and scalar text/number/choice controls. Unsupported shapes retain their data and show an unavailable editor; they do not become invented defaults. Invalid raw text remains in native inputs with field diagnostics, explicit invalid wording and narration. Drawing and narration read cached actor results rather than invoking complete-candidate validation or snapshot callbacks per frame.

Editing/history/save/reload invoke the same [typed local services](operations.md) as commands. Saving uses the actual persistence/application receipt; editing may continue during its file effect. Revert, Defaults and Reload have their existing distinct actor semantics. Failed unsubmitted input can be retried explicitly. A dirty close offers Discard or Keep editing and explains that discarding a draft cannot undo a save. Full effect-aware review, Save during dirty-close, conflict/application-only recovery surfaces and operation details remain Phase 1 work.

Native widgets retain raw text/caret/selection through reflow. The screen restores its nested field or footer focus after native `rebuildWidgets` clears the old focus tree. Section-relative scroll restoration uses the same measured geometry. A dependency adds no automatic inventory button, HUD, shortcut or preferences file.

## Qualification boundary

Pure comparison checks cover extracted geometry/navigation; compilation covers pinned API linkage. The opt-in native example fixture separately exercises real controls and actual JSON save/reopen behavior. [Verification](verification.md) records its exact artifact, observations and limitations. Font/language/resource-reload, physical keyboard/clipboard/narrator output, recursive/promoted controls, dedicated/integrated/server gateways, real consumer migrations and full-pack/cost qualification remain required. Earlier consumer history does not qualify the extracted artifact.
