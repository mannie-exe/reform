# Measured presentation

The current presentation extraction supplies native-free `FormGeometry`/`SectionNavigation` and an internal client-only `FormPanel`/`ControlSizing`. It does not yet supply a generated settings screen, public composition facade or command registration. [Coverage](coverage.md) owns those remaining Phase 1 requirements.

These parts derive from the owned MIT implementation in Skyweave: Compatibility Patches, as identified in `NOTICE`. The extraction changes packages/type names, library translation fallbacks and the location of the native text-reflow helper. Compat still uses its original implementation until the actual consumer migration; that mod and the modpack are not library dependencies.

## Keep one measurement model

`FormGeometry.controls` preserves authored control order, clamps an oversized control to the admitted row width and wraps controls into subsequent lines. Empty control rows have no phantom height. Paired columns require an explicit shared relationship and enough measured width for both minimum sizes; equal values or neighboring fields do not imply a relationship.

`SectionNavigation` binds logical section identities to measured positions. Explicit clamped jumps retain their selected destination until manual movement. Bookmarks preserve section-relative offset and explicit end-of-document position through a reflow. Removed section bookmarks fall back to the start; duplicate and unknown section operations are explicit errors.

The internal native panel measures labels, summaries, widgets and group boundaries using the actual native font and widget dimensions. Rendering, clipping, hit testing, focus scrolling and section anchors use those positions. Native widgets retain their input and narration behavior. Control resizing triggers native text reflow without replacing its raw value/caret/selection. The panel has no document ownership, parser, settings writer or context authority.

## Connect a real form next

The native form must bind these parts to immutable `EditorActor` views and shared actions. Drawing and narration must not invoke model validation or inspect typed candidates per frame. Keep useful input widgets/focus/scroll state across measured reflow and expose invalid/read-only/unavailable reasons accessibly. A new library dependency adds no automatic inventory button, HUD, shortcut or preferences file.

Pure comparison checks cover geometry and navigation. Compilation covers native API linkage at the pinned baseline. Neither proves rendered bounds, caret/selection behavior, actual focus/narration, font/language reload, narrow/high-scale layout or a standalone client/server launch. Those require the generated form and real public native addon, followed by consumer/pack checks. Avoid treating the extraction's earlier consumer history as qualification of the new artifact.
