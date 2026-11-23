# Form composition

Compose settings from their existing definitions and owners. Layout describes relationships and available space; it cannot change a value, admit a source, create a second draft, or authorize an effect. [Client workflows](client-workflows.md) retain the actual document sessions, and [presentation](presentation.md) owns native measurement and navigation.

## Semantic roles and screen regions

`FormTokens` supplies immutable native defaults for spacing, control offers, text measures, and color roles. `Space` distinguishes inline, text, field, content, related-field, section, and screen spacing; equal values do not make those meanings interchangeable. `Control` supplies intrinsic offers for action captions, numeric/text inputs, sliders, steppers, information, and units. `Measure` owns shared navigation/editor/information minima, independent title/status offers, and preferred reading columns. Small entry screens constrain their whole frame to that native-font-measured reading offer, keeping the heading, choices, and Back/Done together. `FormGeometry.centeredWidth` centers an intrinsic offer inside its parent and keeps the full available width on narrow parents. Reading columns guide layout; they never limit authored text. `Color` separates surfaces, lines, focus, primary/supporting text, active text, and errors. Native fonts and GUI scaling remain Minecraft-owned; the information glyph retains its local Unicode font. These are logical GUI pixels and roles, not physical display pixels or domain validation limits.

The separation follows Atlas's reference → semantic → component → instance map and the semantic roles in inherent.design and cfgate. It does not adopt their branding, web dimensions, font stacks, or renderer. Keep graph coordinates, domain previews, row identities, and per-view state local. Do not create a token for every literal or couple roles merely because their current values match. No mutable global theme service or environment provider is added.

`FormGeometry.screenFrame` assigns measured title, context, scrolling content, status, and action regions. Every returned region lies inside its host and ordinary regions do not overlap. Measure text with the actual font before requesting a frame. When chrome cannot coexist with usable content, explicit flags move the header, status, or actions into scrolling content; callers must mount that content rather than ignore it. Long-coordinate containment checks avoid overflow. The native adapter applies this contract to auxiliary forms and retained editors, with explicit parent offsets for mounted views. Compact status text remains a bounded summary; complete diagnostics retain their owning field, body, operation, or information view.

`ClientControls.action(font, label, callback)` supplies an intrinsic native `Button.Builder` with the shared caption padding and control height. Its parent assigns the final bounds. The owner still guards permissions, context, and lifetime; a shared button is not an authorized operation.

`ClientChoiceScreen` provides a measured entry/context chooser without consumer coordinates. Each `Choice` declares a stable ID, label, help, cached blocked-reason supplier, and action. Reasons remain visible near the disabled control and in native tooltip/narration. Selection checks current readiness again. The supplied Back/Done caption returns to the parent, and reflow retains the actual control focus. Choice callbacks still own admission into their destination. Use this for entry choices, not as a second settings actor or business-rule engine.

## Scoped themes

Development 0.4 declarations resolve immutable values before measurement. `ThemeScope` records authored roles separately from component defaults: a parent that explicitly chose the library's numeric default still takes precedence over a variant. `child(ThemePatch)` applies a nested semantic patch; `componentDefaults(ThemePatch)` fills only unspecified roles. Set, inherit, and library-default operations remain distinct, and duplicate operations refuse. Color roles carry explicit ARGB rather than guessed RGB values.

`ClientWorkspacePresentation.withTheme` sets workflow roles, and `withTextStyle` supplies native `Style` fallbacks by text role. A direct `NativeFormScreen.withTheme(ClientTheme)` declares the same binding before mounting. Auxiliary screens inherit the explicitly handed-off scope; independent workspaces resolve their own declarations. Existing component formatting takes precedence, including the information glyph's Unicode font. No native font manager, GUI scale, mutable global theme, or settings file is replaced.

`FormPresentation.withTheme(FormTheme)` adds root, declared group/range, and field scopes without changing a definition or codec. Declare the layout before its themed sections. A section or field can select `ComponentVariant` defaults and then its explicit patch. Absent section/path identities, duplicate scopes, and incompatible reference variants refuse during metadata admission without reading defaults or sources. These declarations currently supply scoped roles; the remaining 0.4 graphic slots and custom-control interaction adapters are separate selected work.

`FormTheme.Limits` bounds static theme declarations independently of `FormCatalog.Limits`, which bounds a current draft's catalogue projection. The default admits 256 section scopes and 512 field scopes. An owner with a larger declared schema can supply explicit theme limits through the constructor or `withLimits`; all theme builders preserve that admission. This does not enlarge catalogue, draft, layout, or source limits. In particular, declarations for inactive tagged alternatives need not all appear in one current projection. Tightening limits below existing declarations refuses the complete declaration rather than dropping scopes.

`ClientControls.action(font, label, selected, theme)` measures the styled native caption and returns a scoped construction offer. `ClientControls.theme(widget, theme)` binds an existing native control without granting edit or persistence authority. The native adapter stores that immutable binding on the control; unbound game and foreign controls remain unchanged. Owned button/cycle captions and input glyphs retain native submission, caret, selection, and editing behavior. The 1.21.1 adapter handles its special alpha 0–3 normalization only for explicitly bound ARGB text. Native glyph clipping still applies: the supplied 1.21.1 shader discards glyph alpha below 0.1, so an explicit alpha of 3 can be invisible. The adapter prevents opaque RGB normalization; it does not replace that shader policy. Update the mapped native call-site checks when porting these hooks; compilation alone does not qualify rendering, IME, accessibility, or input behavior.

## Automatic layout

A field uses an inline label and control group when their measured content fits. Its description and diagnostics belong below the label. Narrow fields stack the controls below that text, and compound controls flow in their authored order. The panel compares the two measured arrangements and uses the shorter one, preferring inline on a tie. A label-free action group consumes no empty text column. Choice controls use the width of their admitted captions rather than a universal text-input width.

Explicit control clusters keep related controls such as numeric decrease/increase on the same line when the complete cluster fits. `FormGeometry.controls` and `fieldTracks` accept joined-adjacency indices without changing widget order or focus identity. A cluster wider than the entire offer falls back to bounded member flow; impossible integer extents refuse rather than wrapping coordinates. The panel supplies native stepper clusters automatically.

Related columns require an explicit relationship. Two or three fields share a row only when all measured minima fit; otherwise they retain their order vertically. A pair of neighboring fields does not establish a relationship, and similarly named numbers do not establish a minimum/maximum invariant. An exact input remains available when a slider cannot represent its value.

The native panel preserves each widget's intrinsic offer separately from its assigned bounds. Shrinking and expanding the window must not progressively reduce a retained input's preferred size. Reflow keeps the draft, selection, caret, focus, and stable row identity. Content bounds drive measurement, wrapping, painting, hit testing, and focus scrolling together.

These constraints follow the owned [Playground composition contracts](https://github.com/mannie-exe/playground/blob/d7a1c9989b2596f062d5f0314474f011470fe902/docs/ui/CONTRACTS.md#6-placement-and-responsive-composition): parents own placement, controls own intrinsic measurement, and presentation is derived from the actual available space. Playground's renderer, runtime, and 3D scene system are not dependencies. A future preview remains content in a measured viewport; it does not give ordinary settings a third layout axis.

## Author relationships and groups

`FormPresentation.withLayout(FormLayout)` adds immutable local declarations. `FormLayout.Row` declares two or three distinct sibling leaf paths in their displayed order. `FormLayout.Group` declares a titled box, optional localized help, and one to 128 sibling fields or subtrees. Direct scalar and reference controls can share rows, including defaulted booleans. Other defaulted values use structural override editors and belong in groups rather than direct rows. Layout paths retain their real identities; labels do not rename them.

```java
var placement = new FormLayout.Row("placement", List.of(spacing, jitter));
var generation = new FormLayout.Group(
    "distribution", "example.distribution", "Island distribution",
    "These choices affect newly generated terrain.", List.of(spacing, jitter));
var presentation = FormPresentation.EMPTY.withLayout(
    new FormLayout(List.of(placement), List.of(generation)));
```

An explicit numeric `FormPresentation.Range` already supplies its endpoint relationship and group. Do not also declare a `Row` for those endpoints. A surrounding layout group may contain the complete range. A related row or range cannot cross sibling group boundaries. Layout admission checks declared shapes without decoding values, constructing defaults, or reading sources. Owner validation still checks the actual complete candidate.

In 0.4 development, `FormComposition.resolve` produces one immutable flow of actual fields, related rows, and presentation boxes. Both the native body and field-navigation projection consume it. Group members and related fields retain their authored reading order; inactive or absent members are not manufactured. `FormCatalog.snapshot(model, presentation)` uses that same order without changing the record definition or compressed codec. Presentation box identities remain separate from document field paths. The complete destination graph and its group-navigation behavior remain part of the [0.4 implementation contract](../design/presentation-0.4-api.md#typed-navigation-and-return-state).

Each form admits at most 128 rows and 128 groups, with unique layout IDs, one row/group membership per sibling field, and at most six nested authored groups. Structured children can have their own groups. The native panel reserves additional framing for a composed section and range. Keep hierarchy shallow and use a focused subtree or auxiliary editor for complex records; nesting does not make an oversized page easier to use.

Use existing catalog field help or the field's `.help` translation for an inline explanation. Keep it short enough to support the choice. Full purpose, source facts, effective values, and related settings remain available through the contextual information action. Validation and availability feedback remain beside the affected field even when detailed information is closed.

`FormCatalog.Section.withSupplementalHelp(String)` carries additional owner-resolved facts separately from the translated purpose. Use it for actual application timing, inactive-owner context, or other facts that a resource translation must not replace. Each supplemental text is bounded to 4,096 characters and is appended as supplied; the caller owns its localization and snapshot freshness. Ordinary help uses its translation key and fallback, including an empty fallback captured before language resources were loaded. An absent translation with an empty fallback remains empty. Shared native field help, navigation help/search, and guarded field information retain the supplement. The neutral catalog's search remains label-only. Uniform document facts may belong once in the placement context; mixed field effects stay near their controls. A supplement does not change authority, validation, or application timing.

## Compose actual owners

`ClientWorkflowLayout.Placement.inSection(Section)` frames selected paths with a stable content-box ID, native title, and help. `Section.withEyebrow(Component)` supplies a short context label above the box when useful. Navigation categories and content sections are separate declarations. Several placements may expose different fields from one retained document; a content box does not create a new save scope.

```java
var preferences = new ClientWorkflowLayout.Section(
    DocumentId.parse("example:preferences"), Component.literal("Preferences"),
    Component.literal("Local display choices."));
var placed = new ClientWorkflowLayout.Placement(
    DocumentId.parse("example:show_labels"), route,
    Component.literal("Show labels"), Optional.of(showLabels))
    .inSection(preferences);
```

Section placements must remain contiguous within an owner page. Reusing a section ID requires the same title and help. Aliases and ancestor/child selections still mount one actual control once, and retain the same actor, history, and source checks. Different owners retain different source identities even when their field paths match. Preserve meaningful source and application differences in labels and help instead of using a box to imply a transaction.

## Actions and information

Keep Done, Revert, Review changes, and the actual save/apply action outside the scrolling form. **Document actions…** opens an explicit auxiliary view over that same document. It groups history, source/default operations, results, and draft lifetime as flowing native buttons. It neither replaces the form body with an action mode nor repeats a button's title as another row label.

The action view names the actual document and context. Defaults, reload, import, and save affect that document, including fields outside the visible box. Owner-supplied defaults captions and help retain their declared baseline. Returning retains the settings page and draft; selecting an action returns to the actual owner before invoking its current guarded callback. An obsolete menu cannot repeat an operation.

Domain operations retain their explicit context, blocked reason, progress, and result. A visually compact button does not remove confirmation, permission, source-revision, or lifetime checks. Separate [saved and applied outcomes](owners.md), and preserve a pending or unknown result across navigation.

## Check the actual interface

Compare normal, narrow, high-GUI-scale, and long-label layouts through actual native screens. Check field and footer bounds, text/control separation, group containment, action order, keyboard traversal, subject narration, and grow-shrink-grow reflow. Exercise invalid and inactive drafts, aliases, source conflicts, saved reopen, and pending operations using their real owners. A passing geometry unit test or independent example does not qualify a consumer's declarations.

Record the exact product and observer artifacts with screenshots and native checks. Report text scaling and input costs in the scene actually measured; compilation and service timings cannot establish native rendering or full-pack performance.
