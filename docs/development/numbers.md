# Numeric presentation

`ValueTypes.numericDisplay(original, display)` decorates a declared numeric scalar with `NumericDisplay` metadata. It retains the original codec, snapshot policy, custom decode diagnostics and equivalence. Storage stays canonical; this declaration neither changes a file schema nor validates an owner value on its own. The existing five-argument `ValueShape.Scalar` constructor still declares no numeric display metadata.

## Stored domains

`NumericDomain` owns lower and upper bounds in stored units, with independent endpoint inclusivity. `closed`, `open`, `openClosed`, and `closedOpen` construct the usual intervals; a null endpoint is unbounded and cannot be exclusive. The six numeric `ValueTypes` factories accept this declaration. Existing minimum/maximum factory overloads and scalar constructors remain closed. `ValueShape.Scalar.domain()` retains the same declaration through display wrappers; its minimum/maximum accessors remain available.

```java
var positive = ValueTypes.decimal(
    NumericDomain.openClosed(BigDecimal.ZERO, BigDecimal.ONE));
var belowOne = ValueTypes.decimal(
    NumericDomain.closedOpen(BigDecimal.ZERO, BigDecimal.ONE));
var control = ValueTypes.numericDisplay(
    positive, NumericDisplay.probability().withSlider());
```

The codec checks authored bounds before native conversion and checks the resulting value again. Zero, including negative zero, is excluded by an open zero boundary, while `1e-300` remains a valid double in the positive example. Floating-point overflow, underflow to zero, or rounding onto an excluded endpoint is refused. Empty mathematical intervals and intervals containing no value of the declared type refuse at declaration. Bounds, type representation, text budgets, and interaction steps are separate constraints. The complete owner validator still runs; native registration predicates are not inferred by probing them.

Information, review, and selected-field command inspection use `NumericComponents.bounds` to show the same declared inequalities. Numeric display fields name the stored unit, so a percent display cannot make a stored 0..1 boundary look like a 0..1 percent policy. Unbounded metadata says that it adds no numeric bound; it does not advertise values beyond the native type or owner validator. Information captures this metadata alongside its actual read-authorized revision, outside rendering, and retains the normal stale-observation and disclosure guards.

Stepping refuses an excluded endpoint or an unrepresentable result without replacing the input. Sliders keep their explicit step grid, anchored at the lower bound. Open endpoints remove those grid points; pointer selection uses the nearest remaining grid point. A single selectable point is allowed; an empty grid, unbounded or identical endpoints, unaligned span/step, fractional integer grid, or grid edge whose native rounding crosses a bound refuses the declaration. Such a refusal affects the chosen slider policy, not the underlying typed domain or exact entry. Grid indices are computed without enumeration. Exact input remains valid between grid points, and observing the thumb never quantizes it.

## Units and controls

```java
var ticks = NumericDisplay.ticks(BigDecimal.valueOf(20));
var duration = ValueTypes.numericDisplay(ValueTypes.integer(0, 12000), ticks);
var probability = ValueTypes.numericDisplay(
    ValueTypes.decimal(0, 1), NumericDisplay.probability());
var alreadyPercent = ValueTypes.numericDisplay(
    ValueTypes.decimal(0, 100), NumericDisplay.percentStored());
```

Units have stable IDs, translation keys/fallbacks and exact positive canonical-per-unit decimal factors. A declaration admits one canonical unit with factor one, at most sixteen unique units, an explicit canonical step and optional nominal tick reference. Existing numeric kind/range and complete owner validation remain authoritative. A unit name or field label never establishes a storage policy.

`toStored` and `fromStored` return explicit conversions. Only `convertedText()` supplies an exact converted representation. On failure, `text()` retains display input; submitting it as canonical can produce a valid but incorrect owner value. Decimal text is the input grammar; expressions and fractions such as `1/3` are not evaluated. Exact arithmetic never silently rounds. A nonterminating decimal reports `UNREPRESENTABLE` and retains the original text. `EMPTY`, `INCOMPLETE`, `INVALID` and `LIMIT` retain that same input too. A frontend must retain the previous selected unit when an otherwise numeric value cannot be converted exactly; it must not relabel the retained number as if conversion succeeded. Incomplete input may remain visible through a unit change without constructing an owner value.

Frontends stage display input with `DraftEdit.SetUnitRaw(unitId, text)`. The editor computes the mapping from its actual declared type; the action cannot supply a canonical result or a success flag. `DraftNode.Scalar.unitInput()` retains the input unit, exact entered text and conversion state. Shared inspection recomputes that mapping before the original codec runs. Failed, missing, changed or inconsistent mappings cannot produce a candidate, export or submission, even when retained text happens to be a valid canonical number. Canonical `SetRaw` explicitly clears this private state. It never infers a display unit.

The retained input participates in undo/redo, inactive branches, draft/history budgets and later-edit reconciliation. Exact private representation alone does not make an unchanged canonical draft dirty; a failed mapping does. Actor typing groups distinguish units, so switching from seconds to milliseconds does not coalesce those edits into one undo step. Only canonical numbers enter authored JSON or a saved file; there are no added unit keys or configuration envelopes. Own save acknowledgement can normalize the submitted representation while preserving newer invalid input.

Conversion to the canonical unit preserves source spelling when the two factors are equal. Other successful conversions may produce compact exponent notation; signed zero is preserved. A presentation-only unit switch must not stage an edit or rewrite the canonical draft. An actual input edit converts to canonical raw text, then uses the same typed edit and complete codec/owner validation as other controls. For example, `0.125` seconds at the declared 20-tick reference converts exactly to `2.5` ticks, which an integer owner still rejects.

Built-in mappings cover nominal ticks/seconds/milliseconds, blocks/chunks, stored 0..1 probability and already stored 0..100 percent. The tick reference is explicit metadata, not a live server-rate query or wall-clock promise under lag. `DecimalText` shares the existing draft's incomplete-number grammar. Its standard conversion budget is 128 characters and absolute scale 4,096; text/scale admission bounds arithmetic and compact output avoids unchecked plain-decimal expansion. Custom limits remain within the bounded editor range. These limits do not replace a document's JSON, field, history or transport admission.

`NumericInput` supplies immutable frontend state for a numeric leaf. It retains the exact expected draft separately from displayed unit/text and an optional retry action. `select` changes presentation only; a failed finite conversion returns the previous state with a reason. Incomplete/invalid text can change display units without staging or accepting a value. `edit` produces shared mapped input. `step` adds the declared canonical step, refuses bounds and nonrepresentable output, and never clamps a field. Original validation still runs when that action reaches the editor. These methods invoke no codec, owner or file operation.

`display.withSlider()` explicitly selects linear nearest-step pointer quantization. A declaration requires finite bounded endpoints with a complete step grid; integer sliders additionally require whole canonical steps. The ordinary five-argument display constructor still declares no slider. `NumericInput.slide` accepts only finite positions in 0..1 and stages the nearest declared step; `position` approximates the thumb location without editing the stored value. Exact text and increment/decrement operations remain unrounded. A nonrepresentable selected display unit refuses the pointer change without replacing the draft. Short converted values use ordinary decimal notation; plain-text expansion is checked before allocation, while large exponents retain a bounded, admissible representation.

Synchronization adopts the actual actor draft. Equal numeric normalization preserves exact display spelling, allowing a native field to retain its caret and selection. An otherwise valid saved value without a finite decimal in the selected unit explicitly falls back to the canonical unit. Unknown retained units keep their identity and input for review; a known canonical source can recover them. A failed mapping cannot become an accepted representation merely because its retained text looks like the saved number.

The generated native form renders declared numeric metadata as an exact text input, separate decrement/increment buttons and a display-unit selector. The tooltip names canonical storage, step and any nominal tick reference. Unit switches wait for pending input admission; typing can continue across the form's own save acknowledgement. Buttons retain the current editor/widget token and ignore retired callbacks. Complete numeric entry can repair a field even when another field causes a cross-field error. Layout uses the same measured panel and wraps these controls at narrow sizes.

An opted-in native slider shows the actual displayed value and unit. Pointer changes update local display immediately and retain only the latest deferred value while input admission is pending. A completed admission may flush that latest value once; refusal retains it for explicit retry rather than starting an automatic retry loop. Retired widgets and replaced sources cannot flush into a new editor.

## Special-value captions

`NumericDisplay.withCaptions` adds up to 32 owner-declared `Caption` values, each with an exact stored number, translation key, and fallback. Keys and fallbacks admit at most 256 characters. Semantic duplicates such as `0` and `0.00`, invalid labels, and values outside the scalar's stored type/domain refuse before opening a control. A distinct decimal that merely rounds to a stored value cannot declare that value's caption. Existing display constructors declare no captions; zero never acquires an implicit meaning.

```java
var ticks = NumericDisplay.ticks(BigDecimal.valueOf(20)).withCaptions(List.of(
    new NumericDisplay.Caption(BigDecimal.ZERO, "my_mod.duration.off", "Off")));
var duration = ValueTypes.numericDisplay(ValueTypes.integer(0, 12000), ticks);
```

`NumericPresentation.caption` derives annotations from an actual declared type and captured numeric input, not a rendered report string. It compares exact semantic numbers independently of spelling or selected display units. Invalid or stale unit mappings, omitted defaults, absent optional values, unavailable observations, and neighboring values that round similarly do not receive a caption. `NumericInput` caches its annotation with the immutable input state.

Native numeric summaries, slider readouts, field information, review values, and selected-field command inspection share `NumericComponents.value`. Exact entry remains numeric, and captions never enter its parser. `FieldInformation.Value.numericCaption` and `DocumentReview.Entry.numericValues` accompany the original raw text; capture budgets include the annotations. Existing constructors retain empty annotations. Exports, typed JSON comparisons, source bytes, and raw technical observations remain numeric or retain their original raw descriptions. Consumers should reuse these declarations and formatter rather than implementing a second sentinel detector.

## Related fields

`FormPresentation.Range` groups two distinct sibling numeric fields by their actual structured paths. The owner supplies a stable group ID, localized caption and help. Pass the presentation to `SettingsScreen` or the client factory's `screen`/`registerMenu` overload. Registration checks declared shapes without invoking codecs, snapshots or value constructors. Duplicate groups/endpoints, unknown fields and unsupported shapes refuse before opening an editor. `DraftTypes.declaredAt` inspects metadata only; it grants no active-row membership or editing authority.

The form renders the pair in measured columns when both controls fit and stacks them otherwise. Unit choices and input/history stay independent. Presentation adds no range value, field rename or implicit validator: the owner's complete validator still enforces its actual invariant and should route an issue to one path with the other in `related`. Both fields remain repairable; editing or stepping one never swaps or clamps the other. Initialization retry preserves the caller's exact presentation and borrowed source bindings.

The independent [headless numeric example](../../examples/headless/src/main/java/example/NumericDomains.java) exercises typed-record and object-map domains, exact sub-step edits, and captions through the packaged API. [The shared native example](../../examples/platform/src/main/java/example/reform/platform/PlatformNumericExample.java) supplies open bounds, units, related fields, and owner-declared zero meanings without another editor or storage implementation. [Verification](verification.md) owns tested artifacts and material limits.

## Dependency updates

Codec or game-library updates must retain both runtime suites' endpoint, signed-zero, native-extrema, underflow, and wrapper-propagation controls. Native input or rendering changes require the affected numeric, information/review, and client-command fixtures, including compact layouts and saved reopen. Consumer declarations must keep actual ranges, units, increments, captions, and complete validation aligned; an opaque native predicate is not a source of inferred bounds or permission to replace its serializer.
