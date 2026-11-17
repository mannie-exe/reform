# Core definitions and decoding

The current API is experimental. It supplies typed values, explicit record definitions, strict JSON admission, deep snapshots and pure complete-value validation. [Editing sessions](editing.md), [JSON owners](owners.md) and [presentation](presentation.md) build on these contracts. Native registered backends, full convenience factories and commands remain required. [Coverage](coverage.md) separates the complete requirements from implemented behavior.

## Declare values once

`ValueType<T>` combines a DFU `Codec<T>`, explicit `ValueShape` and `SnapshotPolicy<T>`. Codec implementation details are not inspected to infer an editor. `ValueTypes` currently supplies booleans, bounded bytes/shorts/integers/longs/floats/doubles, bounded text, enums and bounded recursive lists. Float bounds use their declared float spelling rather than a widened double approximation.

`FieldKey<T>` pairs a serialized field ID with its type. `RecordType.builder` binds those keys to typed getters and one constructor. The resulting type supplies ordered field metadata, a strict object codec and a recursive snapshot policy. Serialized field IDs can differ from Java component names and translated labels. Duplicate declared IDs are rejected. Current explicit record fields are required; missing, unknown or invalid fields prevent construction. Optional/default field policies remain required follow-up work.

`RecordTypes.record(MyRecord.class).build()` derives that same `RecordType` for explicitly selected accessible records. It binds the canonical constructor/getters using ordinary method-handle access and supplies common scalar, enum, nested record and typed `List` codecs/snapshots. It scans no classpath and opens no foreign module. Nonpublic owned records can receive the caller's actual `MethodHandles.Lookup`; the library does not use an access override.

The derivation defaults to 32 type-depth levels, 1,024 compiled record fields, 4,096 text characters and 256 collection entries. `limits` declares different bounds. Custom/unsupported Java types use explicit `register(Class, ValueType)` bindings; recursive/wildcard/raw/unknown types are refused instead of guessed. Maps, sets, optionals and tagged alternatives remain additional Phase 1 work.

`field("javaComponent", FieldKey.of("serialized_id", type))` supplies an explicit root-field identity/constraint override. Duplicate or unknown overrides fail. Default component names become serialized IDs and must be treated as persistent names; a Java rename keeps the prior explicit serialized ID. Nested customized records can be built separately and registered. Definitions still declare owner defaults and meaningful cross-field constraints; derivation infers no units, permissions, application effects or semantic bounds from a member name.

`DocumentDefinition<T>` supplies a namespaced `DocumentId`, positive owner-declared schema version, defaults and an optional pure validator. Defaults are copied and validated at registration. A schema version is metadata here; this type does not inject an envelope into an existing file, initialize storage or interpret world-generation identity.

See the independently built [headless consumer](../../examples/headless/src/main/java/example/ReviewProfile.java) for a complete example. It uses the locally staged `design.inherent:reform` publication, rather than source-set/project access. Its optional references are plain retained values; a real resolver is not implemented by this example.

## Exact input and complete validation

The native DFU integer codecs intentionally use Java numeric conversions. Authored settings need stricter admission. `ExactCodecs` rejects fractional or overflowing integers and numeric strings, including compressed JSON coercion. Finite float/double codecs allow normal binary rounding, preserve negative zero and reject overflow or nonzero underflow to zero. Bounded scalar helpers compare authored decimal bounds before rounding. The core tests retain native controls demonstrating truncation, overflow and partial results.

`JsonInput` admits UTF-8 and JSON before DOM/codec conversion. An owner supplies `JsonLimits` for bytes, nesting, nodes, text, numeric representation and scale. There is no implicit transport policy. Duplicate decoded keys, malformed/trailing input, invalid UTF-8 and unpaired surrogates are refused. The recursive reader supports a maximum configured depth of 256; ordinary owners should choose the bound appropriate to their documents.

The parsed input retains its original source string and scalar spellings keyed by structured `SourcePath`. `copyTree()` returns a caller-owned DOM, while the scalar map is immutable. Each decode receives an independent tree. Codec partial results never become successful decoded values. `JsonInputException` supplies a stable admission code and source location; a source location is not an editor row ID. Existing native/custom parsers remain available through their eventual owner adapters rather than being silently replaced.

`DecodedValue<T>` contains a value only after complete successful decoding/validation. `ValidationReport` distinguishes errors from an unavailable required check. `Issue` carries a stable code, severity, structural field/related paths, arguments and readable fallback. Field attribution is declared, never recovered by parsing an English exception. Complete document validators can report cross-field errors before any owner effect.

These checks do not establish read/write permission or qualify publication. Context-dependent checks and application remain owner responsibilities. A successfully decoded value is not a persistence or application receipt.

## Snapshots and identity

Built-in lists and explicit records recursively own their values. Defaults are not shared mutable instances. Snapshot traversal has per-copy depth/node accounting and rejects object cycles while allowing shared acyclic inputs. Its configuration convenience bound is 128 nested values and 65,536 visits; custom contexts can choose admitted limits. These local limits are not claims about remote frames or terrain workloads.

Custom types provide an explicit snapshot and equality policy. `SnapshotPolicy.immutable()` is appropriate only when the entire reachable value is immutable. Collection and record policies delegate semantic equality to their children. Callbacks must be pure and bounded; an interface cannot sandbox arbitrary consumer code.

`FieldPath` distinguishes field, stable row, variant and map-part segments. The editing model implements current record/list paths with session row IDs; variant and map-part traversal remains follow-up work. Filenames, labels, logical IDs, schema versions, saved revisions and owner contexts remain distinct. No environment-variable or ambient JVM-property settings source is involved.
