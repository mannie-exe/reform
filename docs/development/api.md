# Core definitions and decoding

The current API is experimental. It supplies typed values, explicit record definitions, strict JSON admission, deep snapshots and pure complete-value validation. [Editing sessions](editing.md), [JSON owners](owners.md) and [presentation](presentation.md) build on these contracts. [Native registered owners](native-settings.md) and [shared local GUI/command operations](operations.md) use these contracts; full convenience factories and broader command/editor families remain required. [Coverage](coverage.md) separates the complete requirements from implemented behavior.

## Declare values once

`ValueType<T>` combines a DFU `Codec<T>`, explicit `ValueShape` and `SnapshotPolicy<T>`. Codec implementation details are not inspected to infer an editor. `ValueTypes` currently supplies booleans, bounded bytes/shorts/integers/longs/floats/doubles, bounded text, exact complete UUIDs, enums, bounded recursive lists/sets and explicitly encoded maps. Float bounds use their declared float spelling rather than a widened double approximation.

`FieldKey<T>` pairs a serialized field ID with its type. `RecordType.builder` binds those keys to typed getters and one constructor. The resulting type supplies ordered field metadata, a strict object codec and a recursive snapshot policy. Serialized field IDs can differ from Java component names and translated labels. Duplicate declared IDs are rejected. Record fields are required unless their type explicitly declares an omission policy. Unknown and invalid fields prevent construction. Broader profile inheritance remains required.

`RecordTypes.record(MyRecord.class).build()` derives that same `RecordType` for explicitly selected accessible records. It binds the canonical constructor/getters using ordinary method-handle access and supplies common scalar, enum, nested record, typed `List`/`Set` and string-keyed `Map` codecs/snapshots. Derived string maps use object encoding, including any explicitly registered string type. Other map keys require a field override selecting their actual lossless encoding. It scans no classpath and opens no foreign module. Nonpublic owned records can receive the caller's actual `MethodHandles.Lookup`; the library does not use an access override.

The derivation defaults to 32 type-depth levels, 1,024 compiled record fields, 4,096 text characters and 256 collection entries. `limits` declares different bounds. Custom/unsupported Java types use explicit `register(Class, ValueType)` bindings; recursive/wildcard/raw/unknown types are refused instead of guessed. An `Optional<T>` component needs an explicit field override choosing its encoding and omission policy. A tagged family can register its base Java type with an explicitly built `TaggedType`.

`field("javaComponent", FieldKey.of("serialized_id", type))` supplies an explicit root-field identity/constraint override. Duplicate or unknown overrides fail. Default component names become serialized IDs and must be treated as persistent names; a Java rename keeps the prior explicit serialized ID. Nested customized records can be built separately and registered. Definitions still declare owner defaults and meaningful cross-field constraints; derivation infers no units, permissions, application effects or semantic bounds from a member name.

`DocumentDefinition<T>` supplies a namespaced `DocumentId`, positive owner-declared schema version, defaults and an optional pure validator. Defaults are copied and validated at registration. A schema version is metadata here; this type does not inject an envelope into an existing file, initialize storage or interpret world-generation identity.

See the independently built [headless consumer](../../examples/headless/src/main/java/example/ReviewProfile.java) for a complete example. It uses the locally staged `design.inherent:reform` publication, rather than source-set/project access. Its optional references are plain retained values; a real resolver is not implemented by this example.

## Declared field defaults

`ValueTypes.defaultedField(base, fallback)` declares the value of an omitted record field. The fallback is validated, copied and checked for a complete semantic codec roundtrip. Ordinary required fields keep their required behavior. Defaults are explicit declarations; record derivation does not guess them from Java primitive values or a constructor.

`DefaultedType<T>` retains the underlying `T`, codec and snapshot policy. The record encoder validates a value before omitting an equivalent default. Owned JSON drafts can preserve an explicit equal-default override, which is a source change even when the typed value is unchanged. Existing native codecs need declarations matching their actual missing-value policies; the library does not infer those policies by inspecting codec internals.

Ordinary owned object decoding distinguishes an absent field from explicit null. A null is passed to the declared value decoder and cannot silently select a non-null fallback. Native compressed empty slots retain their omission meaning. The tests separately retain DFU's ordinary JSON lookup behavior, where null can appear missing to a native optional-field codec. Existing native serializers and callbacks keep their owners.

The [public field-default example](../../examples/headless/src/main/java/example/FieldDefaults.java) persists both presence transitions through a real bare JSON file. Defaulted entry-list map keys/values obey those record-field rules too. Omission has no standalone, array-slot or dynamic object-map-value representation. [Editing](editing.md) owns inactive override retention, payload paths and acknowledgement behavior. Native controls and profile inheritance remain required.

## Optional membership

`ValueTypes.optional(element, encoding)` owns an `Optional<T>` recursively. `NULL_OR_VALUE` encodes empty as null and a present value directly. `SINGLETON_LIST` encodes `[]` or `[value]`, preserving a present nested empty value such as `[null]`. The choice is explicit and persistent; changing it is a consumer schema change. The codec never turns malformed or partial child results into empty membership.

Optional membership does not make a record field optional. Combine a nullable optional type with `defaultedField(type, Optional.empty())` for the native absent/direct-value field convention. Pinned native-codec controls cover that convention in ordinary and compressed JsonOps. Plain required nullable fields still distinguish explicit null from absence in ordinary owned JSON.

Compressed record slots cannot distinguish null from omission. A required nullable empty value, or an explicit empty value against a nonempty fallback, therefore refuses compressed encoding instead of silently changing its meaning. Singleton-list encoding preserves those distinctions. Nullable nesting also refuses a present child whose canonical encoding is null; choose singleton lists where the nested empty distinction matters. Custom serializers remain their owners' responsibility.

The [packaged optional example](../../examples/headless/src/main/java/example/OptionalEditing.java) writes native-style fields through a real bare JSON file and retains invalid inactive input separately. [Editing](editing.md) owns raw presence, source locations and history. Built-in optional controls and actual consumer migration remain required.

## Tagged alternatives

`TaggedType.<Mode>builder("kind", "settings").variant("fixed", Fixed.class, fixedType).variant("automatic", Automatic.class, automaticType).build()` declares a finite family. Each stable ID binds one concrete exact Java class and its typed child definition. Duplicate IDs/classes, abstract/interface classes and unknown runtime classes are refused; subclasses and aliases are not inferred. Builder changes do not modify an already built definition.

The owned format is an explicit discriminator/payload envelope, such as `{"kind":"fixed","settings":{"count":4}}`. Field names are declared and distinct. The envelope remains an object under compressed JsonOps; children retain their actual codecs. Both fields are required and unknown fields/variants refuse decoding. Numeric tags are not coerced to strings. Existing flattened native dispatch codecs require their own explicitly matching adapter; this helper does not migrate them.

Payload decoding and snapshots retain the selected child type and exact class. A tagged view does not introduce a second object-identity visit for the same value, and real child cycles remain subject to snapshot accounting. `RecordType.decode` delegates compressed layout to native DFU `MapDecoder` while retaining the caller's structured child paths. Shared comparison descends into a matching variant's fields; a changed variant remains a complete alternative change.

The [packaged tagged example](../../examples/headless/src/main/java/example/TaggedEditing.java) persists the selected variant while retaining invalid inactive work only in its draft. [Editing](editing.md) owns branch identity, raw repair and history. Native branch selectors and real consumer adapters remain required.

## Explicit map encodings

`ValueTypes.objectMap(key, value, maximumSize)` retains actual string-encoded object keys. It neither converts numeric keys to strings nor routes dynamic names through DFU's fixed-field compression. `entryMap` uses an array of `{"key": ..., "value": ...}` records for numeric, composite or other declared keys. Their fields are required unless the declared types provide field defaults. Both formats compose with nested records, lists and maps; switching them changes the consumer's storage schema and is not an automatic migration.

Keys use Java value equality and hash codes, which must survive both the declared codec roundtrip and owned copying. Object-key encoding must also be injective. Duplicate decoded keys, encoding collisions, partial child results and exceeded size bounds refuse a complete map. Values use their declared child snapshot/equality policy. Map equality ignores entry order; the retained iteration order is presentation/serialization order rather than a separate persisted setting. Ordered domain tables need an explicit list type instead.

The [packaged map example](../../examples/headless/src/main/java/example/MapEditing.java) exercises role counts, delayed acknowledgements and explicit composite-key encoding. Raw duplicates and key edits are described in [editing](editing.md); generated native map controls remain required.

## Unordered membership

`ValueTypes.set(element, maximumSize)` declares bounded set membership with JSON array encoding. Its members follow the same Java equality/hash and owned-copy/codec-roundtrip identity contract as map keys. Duplicate decoded members are refused, not silently removed. Iteration order is retained for display/serialization; it does not change set equality. Snapshot copies own composite members recursively.

Uniqueness and ordering are separate metadata. `ValueShape.Sequence` defaults to `CollectionOrder.ORDERED`, including its three-argument constructor for a unique ordered list. Sets explicitly select `UNORDERED`; unordered nonunique bags are not supplied. The [packaged set example](../../examples/headless/src/main/java/example/SetEditing.java) demonstrates duplicate repair and reordered acknowledgement without a resource resolver or game dependency. Native selection controls remain required.

## Exact input and complete validation

The native DFU integer codecs intentionally use Java numeric conversions. Authored settings need stricter admission. `ExactCodecs` rejects fractional or overflowing integers and numeric strings, including compressed JSON coercion. Finite float/double codecs allow normal binary rounding, preserve negative zero and reject overflow or nonzero underflow to zero. Bounded scalar helpers compare authored decimal bounds before rounding. The core tests retain native controls demonstrating truncation, overflow and partial results.

`JsonInput` admits UTF-8 and JSON before DOM/codec conversion. An owner supplies `JsonLimits` for bytes, nesting, nodes, text, numeric representation and scale. There is no implicit transport policy. Duplicate decoded keys, malformed/trailing input, invalid UTF-8 and unpaired surrogates are refused. The recursive reader supports a maximum configured depth of 256; ordinary owners should choose the bound appropriate to their documents.

The parsed input retains its original source string and scalar spellings keyed by structured `SourcePath`. `copyTree()` returns a caller-owned DOM, while the scalar map is immutable. Each decode receives an independent tree. Codec partial results never become successful decoded values. `JsonInputException` supplies a stable admission code and source location; a source location is not an editor row ID. Existing native/custom parsers remain available through their eventual owner adapters rather than being silently replaced.

`DecodedValue<T>` contains a value only after complete successful decoding/validation. `ValidationReport` distinguishes errors from an unavailable required check. `Issue` carries a stable code, severity, structural field/related paths, arguments and readable fallback. Field attribution is declared, never recovered by parsing an English exception. Complete document validators can report cross-field errors before any owner effect.

These checks do not establish read/write permission or qualify publication. Context-dependent checks and application remain owner responsibilities. A successfully decoded value is not a persistence or application receipt.

## Snapshots and identity

Built-in lists, sets, maps and explicit records recursively own their values. Defaults are not shared mutable instances. Snapshot traversal has per-copy depth/node accounting and rejects object cycles while allowing shared acyclic inputs. Its configuration convenience bound is 128 nested values and 65,536 visits; custom contexts can choose admitted limits. These local limits are not claims about remote frames or terrain workloads.

Custom types provide an explicit snapshot and equality policy. `SnapshotPolicy.immutable()` is appropriate only when the entire reachable value is immutable. Lists, map values and record fields delegate semantic equality to their children; map keys and set members retain the Java identity contract above. Callbacks must be pure and bounded; an interface cannot sandbox arbitrary consumer code.

`FieldPath` distinguishes field, stable row, variant and part segments. The editing model implements record/list/set/map/variant paths, defaulted and optional payloads with session row IDs and explicit parts. Reference metadata decorates an existing text/choice type without changing its codec or draft path; [reference sources](references.md) separately own admitted lookup and context freshness. Filenames, labels, logical IDs, schema versions, saved revisions and owner contexts remain distinct. No environment-variable or ambient JVM-property settings source is involved.
