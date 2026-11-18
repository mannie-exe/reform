# Numeric presentation

`ValueTypes.numericDisplay(original, display)` decorates a declared numeric scalar with `NumericDisplay` metadata. It retains the original codec, snapshot policy, custom decode diagnostics and equivalence. Storage stays canonical; this declaration neither changes a file schema nor validates an owner value on its own. The existing five-argument `ValueShape.Scalar` constructor still declares no numeric display metadata.

```java
var ticks = NumericDisplay.ticks(BigDecimal.valueOf(20));
var duration = ValueTypes.numericDisplay(ValueTypes.integer(0, 12000), ticks);
var probability = ValueTypes.numericDisplay(
    ValueTypes.decimal(0, 1), NumericDisplay.probability());
var alreadyPercent = ValueTypes.numericDisplay(
    ValueTypes.decimal(0, 100), NumericDisplay.percentStored());
```

Units have stable IDs, translation keys/fallbacks and exact positive canonical-per-unit decimal factors. A declaration admits one canonical unit with factor one, at most sixteen unique units, an explicit canonical step and optional nominal tick reference. Existing numeric kind/range and complete owner validation remain authoritative. A unit name or field label never establishes a storage policy.

`toStored` and `fromStored` return explicit conversions. Only `convertedText()` supplies an exact converted representation. On failure, `text()` retains display input; submitting it as canonical can produce a valid but incorrect owner value. A frontend must retain that input as an invalid/unaccepted draft and prevent submission until conversion succeeds. Decimal text is the input grammar; expressions and fractions such as `1/3` are not evaluated. Exact arithmetic never silently rounds. A nonterminating decimal reports `UNREPRESENTABLE` and retains the original text. `EMPTY`, `INCOMPLETE`, `INVALID` and `LIMIT` retain that same input too. A frontend must retain the previous selected unit when an otherwise numeric value cannot be converted exactly; it must not relabel the retained number as if conversion succeeded. Incomplete input may remain visible through a unit change without constructing an owner value.

Conversion to the canonical unit preserves source spelling when the two factors are equal. Other successful conversions may produce compact exponent notation; signed zero is preserved. A presentation-only unit switch must not stage an edit or rewrite the canonical draft. An actual input edit converts to canonical raw text, then uses the same typed edit and complete codec/owner validation as other controls. For example, `0.125` seconds at the declared 20-tick reference converts exactly to `2.5` ticks, which an integer owner still rejects.

Built-in mappings cover nominal ticks/seconds/milliseconds, blocks/chunks, stored 0..1 probability and already stored 0..100 percent. The tick reference is explicit metadata, not a live server-rate query or wall-clock promise under lag. `DecimalText` shares the existing draft's incomplete-number grammar. Its standard conversion budget is 128 characters and absolute scale 4,096; text/scale admission bounds arithmetic and compact output avoids unchecked plain-decimal expansion. Custom limits remain within the bounded editor range. These limits do not replace a document's JSON, field, history or transport admission.

The current slice supplies core metadata and pure conversion, plus an independent packaged headless editing example. Native unit selectors, stepping/sliders, paired-range presentation and related interaction qualification remain Phase 1 work. The existing generated form still edits canonical numeric text; it does not render this metadata as a unit-aware control yet. [Verification](verification.md) owns tested artifacts and material limits.
