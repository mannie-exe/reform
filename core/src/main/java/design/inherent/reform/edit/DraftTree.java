package design.inherent.reform.edit;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.presentation.NumericDisplay;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonInputException;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.serialization.SourcePath;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** Persistent raw edits. Inspection is explicit and pure; it performs no file/world operation. */
public final class DraftTree {
  private static final Gson JSON = new Gson();
  private static final Pattern JSON_NUMBER =
      Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

  public enum InputState {
    EMPTY,
    INCOMPLETE,
    INVALID,
    UNAVAILABLE,
    VALID
  }

  public record Inspection<T>(
      java.util.Optional<Candidate<T>> candidate,
      ValidationReport report,
      Map<FieldPath, InputState> inputs) {
    public Inspection {
      inputs = Map.copyOf(inputs);
    }
  }

  /** A pure field-codec observation; it does not validate the complete owner or grant editing. */
  public record FieldInspection<V>(
      java.util.Optional<V> value,
      ValidationReport report,
      InputState input,
      int encodedBytes,
      java.util.Optional<Throwable> failure) {}

  public <V> FieldInspection<V> inspectField(
      ValueType<V> type, FieldPath path, JsonLimits jsonLimits) {
    var renderer = new Renderer(jsonLimits);
    try {
      var json = renderer.render(at(path), type, path, SourcePath.ROOT);
      var report = new ValidationReport(renderer.issues, renderer.complete);
      int bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      if (!report.valid())
        return new FieldInspection<>(
            java.util.Optional.empty(),
            report,
            renderer.inputs.getOrDefault(path, InputState.INVALID),
            bytes,
            java.util.Optional.empty());
      var parsed = JsonInput.read(json, jsonLimits).decode(type.codec());
      if (parsed.error().isPresent())
        return new FieldInspection<>(
            java.util.Optional.empty(),
            report.and(
                new ValidationReport(
                    List.of(
                        Issue.error("value.decode", path, parsed.error().orElseThrow().message())),
                    true)),
            InputState.INVALID,
            bytes,
            java.util.Optional.empty());
      return new FieldInspection<>(
          java.util.Optional.of(type.freeze(parsed.result().orElseThrow())),
          report,
          renderer.inputs.getOrDefault(path, InputState.VALID),
          bytes,
          java.util.Optional.empty());
    } catch (JsonInputException invalid) {
      return new FieldInspection<>(
          java.util.Optional.empty(),
          new ValidationReport(
              List.of(Issue.error(invalid.code(), path, invalid.getMessage())), true),
          InputState.INVALID,
          0,
          java.util.Optional.of(invalid));
    } catch (RuntimeException failed) {
      return new FieldInspection<>(
          java.util.Optional.empty(),
          new ValidationReport(
              List.of(
                  Issue.error(
                      "field.observation_unavailable", path, "This field could not be observed.")),
              false),
          InputState.UNAVAILABLE,
          0,
          java.util.Optional.of(failed));
    }
  }

  private final DraftNode root;
  private final DraftLimits limits;
  private final DraftBasis basis;

  public DraftTree(DraftNode root, DraftLimits limits) {
    this(root, limits, null);
  }

  private DraftTree(DraftNode root, DraftLimits limits, DraftBasis basis) {
    this.root = Objects.requireNonNull(root);
    this.limits = Objects.requireNonNull(limits);
    this.basis = basis;
    limits.check(root);
  }

  public DraftNode root() {
    return root;
  }

  public DraftLimits limits() {
    return limits;
  }

  /**
   * Captured provenance only. Any supplied basis still requires admission from its actual owner.
   */
  public Optional<DraftBasis> basis() {
    return Optional.ofNullable(basis);
  }

  public DraftTree withBasis(DraftBasis selected) {
    return new DraftTree(root, limits, Objects.requireNonNull(selected));
  }

  DraftTree within(DraftLimits selected) {
    return new DraftTree(root, selected, basis);
  }

  DraftTree inheritBasis(DraftTree current) {
    return basis == null && current.basis != null ? withBasis(current.basis) : this;
  }

  boolean sameBasis(DraftTree other) {
    return basis == other.basis;
  }

  boolean sameInput(DraftTree other) {
    return sameBasis(other) && root.equals(other.root);
  }

  public static <T> DraftTree from(ValueType<T> type, T value, DraftLimits limits) {
    var encoded = type.codec().encodeStart(JsonOps.INSTANCE, type.freeze(value));
    if (encoded.error().isPresent())
      throw new IllegalArgumentException("Cannot create draft from invalid value");
    return new DraftTree(
        build(type, encoded.result().orElseThrow(), SourcePath.ROOT, Map.of()), limits);
  }

  /** Incomplete/unknown fields are retained. This does not invent a valid saved value. */
  public static DraftTree fromParsed(
      ValueType<?> type, JsonInput.Parsed source, DraftLimits limits) {
    return fromParsed(type, source, SourcePath.ROOT, limits);
  }

  /** Select a declared storage envelope without losing the values' original scalar spelling. */
  public static DraftTree fromParsed(
      ValueType<?> type, JsonInput.Parsed source, SourcePath selection, DraftLimits limits) {
    var selected = source.copyTree();
    for (var segment : selection.segments()) {
      if (segment instanceof SourcePath.Property property
          && selected.isJsonObject()
          && selected.getAsJsonObject().has(property.name()))
        selected = selected.getAsJsonObject().get(property.name());
      else if (segment instanceof SourcePath.Index index
          && selected.isJsonArray()
          && index.index() < selected.getAsJsonArray().size())
        selected = selected.getAsJsonArray().get(index.index());
      else throw new IllegalArgumentException("Selected source value does not exist");
    }
    return new DraftTree(build(type, selected, selection, source.scalarText()), limits);
  }

  private static DraftNode build(
      ValueType<?> type, JsonElement value, SourcePath path, Map<SourcePath, String> text) {
    if (type.shape() instanceof ValueShape.Reference<?> reference)
      return build(reference.element(), value, path, text);
    if (type.shape() instanceof ValueShape.Defaulted<?> defaults) {
      return new DraftNode.Defaulted(
          value != null,
          value == null ? defaultDraft(defaults) : build(defaults.element(), value, path, text));
    }
    if (value == null) return new DraftNode.Missing();
    if (type.shape() instanceof ValueShape.Alternative alternative && value.isJsonObject()) {
      var object = value.getAsJsonObject();
      var tag = object.get(alternative.discriminator());
      if (tag != null && tag.isJsonPrimitive() && tag.getAsJsonPrimitive().isString()) {
        var id = tag.getAsString();
        var payload = object.get(alternative.payload());
        var selected = alternative.variant(id);
        var body =
            selected.isPresent()
                ? build(
                    selected.orElseThrow().type(),
                    payload,
                    path.property(alternative.payload()),
                    text)
                : payload == null
                    ? new DraftNode.Missing()
                    : new DraftNode.Opaque(
                        sourceJson(payload, path.property(alternative.payload()), text));
        var unknown = new LinkedHashMap<String, DraftNode>();
        object
            .entrySet()
            .forEach(
                entry -> {
                  if (!entry.getKey().equals(alternative.discriminator())
                      && !entry.getKey().equals(alternative.payload()))
                    unknown.put(
                        entry.getKey(),
                        new DraftNode.Opaque(
                            sourceJson(entry.getValue(), path.property(entry.getKey()), text)));
                });
        return new DraftNode.Alternative(id, Map.of(id, body), unknown);
      }
      return new DraftNode.Opaque(sourceJson(value, path, text));
    }
    if (type.shape() instanceof ValueShape.OptionalValue<?> optional) {
      if (optional.encoding() == ValueShape.OptionalEncoding.NULL_OR_VALUE)
        return value.isJsonNull()
            ? new DraftNode.OptionalValue(false, new DraftNode.Missing())
            : new DraftNode.OptionalValue(true, build(optional.element(), value, path, text));
      if (value.isJsonArray() && value.getAsJsonArray().size() <= 1)
        return value.getAsJsonArray().isEmpty()
            ? new DraftNode.OptionalValue(false, new DraftNode.Missing())
            : new DraftNode.OptionalValue(
                true,
                build(optional.element(), value.getAsJsonArray().get(0), path.index(0), text));
      return new DraftNode.Opaque(sourceJson(value, path, text));
    }
    if (type.shape() instanceof ValueShape.Custom)
      return new DraftNode.Opaque(sourceJson(value, path, text));
    if (value.isJsonNull()) return new DraftNode.NullValue();
    if (type.shape() instanceof ValueShape.Mapping mapping) {
      var rows = new ArrayList<DraftNode.Row>();
      if (mapping.encoding() == ValueShape.MapEncoding.OBJECT && value.isJsonObject()) {
        value
            .getAsJsonObject()
            .entrySet()
            .forEach(
                entry -> {
                  var parts = new LinkedHashMap<String, DraftNode>();
                  parts.put(
                      "key",
                      build(
                          mapping.key(),
                          new JsonPrimitive(entry.getKey()),
                          path.key(entry.getKey()),
                          text));
                  parts.put(
                      "value",
                      build(
                          mapping.value(), entry.getValue(), path.property(entry.getKey()), text));
                  rows.add(DraftNode.Row.create(new DraftNode.ObjectValue(parts)));
                });
        return new DraftNode.Mapping(rows);
      }
      if (mapping.encoding() == ValueShape.MapEncoding.ENTRY_LIST && value.isJsonArray()) {
        var fields =
            List.<FieldKey<?>>of(
                FieldKey.of("key", mapping.key()), FieldKey.of("value", mapping.value()));
        for (int i = 0; i < value.getAsJsonArray().size(); i++) {
          var entry = value.getAsJsonArray().get(i);
          rows.add(
              DraftNode.Row.create(
                  entry.isJsonObject()
                      ? buildObject(fields, entry, path.index(i), text)
                      : new DraftNode.Opaque(sourceJson(entry, path.index(i), text))));
        }
        return new DraftNode.Mapping(rows);
      }
    }
    if (type.shape() instanceof ValueShape.Scalar scalar && value.isJsonPrimitive()) {
      boolean matches =
          switch (scalar.kind()) {
            case TEXT, CHOICE -> value.getAsJsonPrimitive().isString();
            case BOOLEAN -> value.getAsJsonPrimitive().isBoolean();
            default -> value.getAsJsonPrimitive().isNumber();
          };
      if (matches) return new DraftNode.Scalar(text.getOrDefault(path, value.getAsString()));
      return new DraftNode.Opaque(sourceJson(value, path, text));
    }
    if (type.shape() instanceof ValueShape.ObjectValue object && value.isJsonObject()) {
      return buildObject(object.fields(), value, path, text);
    }
    if (type.shape() instanceof ValueShape.Sequence sequence && value.isJsonArray()) {
      var rows = new ArrayList<DraftNode.Row>();
      for (int i = 0; i < value.getAsJsonArray().size(); i++)
        rows.add(
            DraftNode.Row.create(
                build(sequence.element(), value.getAsJsonArray().get(i), path.index(i), text)));
      return new DraftNode.Sequence(rows, sequence.order() == ValueShape.CollectionOrder.UNORDERED);
    }
    return new DraftNode.Opaque(sourceJson(value, path, text));
  }

  private static DraftNode.ObjectValue buildObject(
      List<FieldKey<?>> declared,
      JsonElement value,
      SourcePath path,
      Map<SourcePath, String> text) {
    var fields = new LinkedHashMap<String, DraftNode>();
    for (var field : declared)
      fields.put(
          field.id(),
          build(
              field.type(),
              value.getAsJsonObject().get(field.id()),
              path.property(field.id()),
              text));
    value
        .getAsJsonObject()
        .entrySet()
        .forEach(
            entry -> {
              if (!fields.containsKey(entry.getKey()))
                fields.put(
                    entry.getKey(),
                    new DraftNode.Opaque(
                        sourceJson(entry.getValue(), path.property(entry.getKey()), text)));
            });
    return new DraftNode.ObjectValue(fields);
  }

  private static <T> DraftNode defaultDraft(ValueShape.Defaulted<T> defaults) {
    return build(defaults.element(), defaultJson(defaults), SourcePath.ROOT, Map.of());
  }

  private static <T> JsonElement defaultJson(ValueShape.Defaulted<T> defaults) {
    var encoded = defaults.element().codec().encodeStart(JsonOps.INSTANCE, defaults.fallback());
    if (encoded.error().isPresent() || encoded.result().isEmpty())
      throw new IllegalArgumentException("Field default cannot encode completely");
    return encoded.result().orElseThrow();
  }

  private static String sourceJson(
      JsonElement value, SourcePath path, Map<SourcePath, String> text) {
    if (value.isJsonNull()) return "null";
    if (value.isJsonPrimitive())
      return value.getAsJsonPrimitive().isNumber()
          ? text.getOrDefault(path, value.toString())
          : value.toString();
    var parts = new ArrayList<String>();
    if (value.isJsonArray()) {
      for (int i = 0; i < value.getAsJsonArray().size(); i++)
        parts.add(sourceJson(value.getAsJsonArray().get(i), path.index(i), text));
      return "[" + String.join(",", parts) + "]";
    }
    value
        .getAsJsonObject()
        .entrySet()
        .forEach(
            entry ->
                parts.add(
                    JSON.toJson(entry.getKey())
                        + ":"
                        + sourceJson(entry.getValue(), path.property(entry.getKey()), text)));
    return "{" + String.join(",", parts) + "}";
  }

  public DraftNode at(FieldPath path) {
    return at(path, false);
  }

  private DraftNode at(FieldPath path, boolean activeOnly) {
    DraftNode current = root;
    var segments = path.segments();
    for (int i = 0; i < segments.size(); i++) {
      var segment = segments.get(i);
      if (segment == FieldPath.Part.KEY && current instanceof DraftNode.Alternative value) {
        current = value.selector();
      } else if (segment instanceof FieldPath.Variant variant
          && current instanceof DraftNode.Alternative value) {
        if (activeOnly && !variant.id().equals(value.selected()))
          throw new IllegalArgumentException("The retained branch is inactive");
        current = value.branches().get(variant.id());
        if (current == null)
          throw new IllegalArgumentException("Branch no longer exists in this draft");
      } else if (segment == FieldPath.Part.VALUE && current instanceof DraftNode.Defaulted value) {
        if (activeOnly && !value.overridden())
          throw new IllegalArgumentException("The retained override is inactive");
        current = value.value();
      } else if (segment == FieldPath.Part.VALUE
          && current instanceof DraftNode.OptionalValue value) {
        if (activeOnly && !value.present())
          throw new IllegalArgumentException("The retained optional value is inactive");
        current = value.value();
      } else if (segment instanceof FieldPath.Field field
          && current instanceof DraftNode.ObjectValue object) {
        current = object.fields().get(field.id());
        if (current == null)
          throw new IllegalArgumentException("Field no longer exists in this draft");
      } else if (segment instanceof FieldPath.Row row
          && current instanceof DraftNode.Sequence sequence) {
        current = sequence.rows().get(sequence.index(row.id())).value();
      } else if (segment instanceof FieldPath.Row row
          && current instanceof DraftNode.Mapping mapping) {
        current = mapping.rows().get(mapping.index(row.id())).value();
        if (i + 1 < segments.size()) {
          if (!(segments.get(++i) instanceof FieldPath.Part part)
              || !(current instanceof DraftNode.ObjectValue entry))
            throw new IllegalArgumentException("A map row requires a key or value part");
          current = entry.fields().get(part == FieldPath.Part.KEY ? "key" : "value");
          if (current == null)
            throw new IllegalArgumentException("Map part no longer exists in this draft");
        }
      } else throw new IllegalArgumentException("Path does not address this draft structure");
    }
    return current;
  }

  public boolean contains(FieldPath path) {
    try {
      at(path);
      return true;
    } catch (IllegalArgumentException absent) {
      return false;
    }
  }

  /** Structural presence/selection only; this grants neither input validity nor edit authority. */
  public boolean activeAt(FieldPath path) {
    try {
      at(path, true);
      return true;
    } catch (IllegalArgumentException inactive) {
      return false;
    }
  }

  public DraftTree change(FieldPath path, UnaryOperator<DraftNode> operation) {
    return new DraftTree(change(root, path.segments(), 0, operation), limits, basis);
  }

  /** Apply independent review choices with one final budget check. */
  DraftTree replaceAll(Map<FieldPath, DraftNode> values) {
    var changed = root;
    for (var entry : values.entrySet())
      changed = change(changed, entry.getKey().segments(), 0, ignored -> entry.getValue());
    return new DraftTree(changed, limits, basis);
  }

  private static DraftNode change(
      DraftNode node,
      List<FieldPath.Segment> path,
      int offset,
      UnaryOperator<DraftNode> operation) {
    if (offset == path.size()) return Objects.requireNonNull(operation.apply(node));
    var segment = path.get(offset);
    if (segment == FieldPath.Part.KEY && node instanceof DraftNode.Alternative value) {
      var selector = change(value.selector(), path, offset + 1, operation);
      if (!(selector instanceof DraftNode.Scalar scalar))
        throw new IllegalArgumentException("Variant selection requires text");
      return value.withSelector(scalar.raw());
    }
    if (segment instanceof FieldPath.Variant variant
        && node instanceof DraftNode.Alternative value) {
      var previous = value.branches().get(variant.id());
      if (previous == null)
        throw new IllegalArgumentException("Branch no longer exists in this draft");
      return value.withBranch(variant.id(), change(previous, path, offset + 1, operation));
    }
    if (segment == FieldPath.Part.VALUE && node instanceof DraftNode.Defaulted value)
      return value.withValue(change(value.value(), path, offset + 1, operation));
    if (segment == FieldPath.Part.VALUE && node instanceof DraftNode.OptionalValue value)
      return value.withValue(change(value.value(), path, offset + 1, operation));
    if (segment instanceof FieldPath.Field field && node instanceof DraftNode.ObjectValue object) {
      var child = object.fields().get(field.id());
      if (child == null) throw new IllegalArgumentException("Field no longer exists in this draft");
      return object.with(field.id(), change(child, path, offset + 1, operation));
    }
    if (segment instanceof FieldPath.Row row && node instanceof DraftNode.Sequence sequence) {
      return sequence.replace(
          row.id(),
          change(
              sequence.rows().get(sequence.index(row.id())).value(), path, offset + 1, operation));
    }
    if (segment instanceof FieldPath.Row row && node instanceof DraftNode.Mapping mapping) {
      var entry = mapping.rows().get(mapping.index(row.id())).value();
      if (offset + 1 == path.size())
        return mapping.replace(row.id(), change(entry, path, offset + 1, operation));
      if (!(path.get(offset + 1) instanceof FieldPath.Part part)
          || !(entry instanceof DraftNode.ObjectValue object))
        throw new IllegalArgumentException("A map row requires a key or value part");
      String name = part == FieldPath.Part.KEY ? "key" : "value";
      var child = object.fields().get(name);
      if (child == null)
        throw new IllegalArgumentException("Map part no longer exists in this draft");
      return mapping.replace(
          row.id(), object.with(name, change(child, path, offset + 2, operation)));
    }
    throw new IllegalArgumentException("Path does not address this draft structure");
  }

  public boolean sameValue(DraftTree other) {
    return sameBasis(other) && DraftNode.sameValue(root, other.root);
  }

  /** Preserve identities only within branches whose authored values are unchanged. */
  DraftTree reuseUnchangedFrom(DraftTree previous) {
    return new DraftTree(reuseUnchanged(root, previous.root, DraftNode::sameValue), limits, basis);
  }

  /** A full draft replacement cannot discard explicitly supplied inactive or mapped input. */
  DraftTree reuseIdenticalInputFrom(DraftTree previous) {
    return new DraftTree(reuseUnchanged(root, previous.root, DraftNode::sameInput), limits, basis);
  }

  private static DraftNode reuseUnchanged(
      DraftNode next,
      DraftNode previous,
      java.util.function.BiPredicate<DraftNode, DraftNode> same) {
    if (same.test(next, previous)) return previous;
    if (next instanceof DraftNode.ObjectValue n
        && previous instanceof DraftNode.ObjectValue p
        && n.fields().keySet().equals(p.fields().keySet())) {
      var fields = new LinkedHashMap<String, DraftNode>();
      n.fields()
          .forEach(
              (key, value) -> fields.put(key, reuseUnchanged(value, p.fields().get(key), same)));
      return new DraftNode.ObjectValue(fields);
    }
    return next;
  }

  /** Only for an already qualified equal-value saved observation, never an identity heuristic. */
  DraftTree alignRows(DraftTree submitted, ValueType<?> type, JsonLimits jsonLimits) {
    return new DraftTree(align(root, submitted.root, type, jsonLimits), limits, basis);
  }

  private static DraftNode align(
      DraftNode actual, DraftNode submitted, ValueType<?> type, JsonLimits limits) {
    if (type.shape() instanceof ValueShape.Alternative alternative
        && actual instanceof DraftNode.Alternative a
        && submitted instanceof DraftNode.Alternative s
        && a.selected().equals(s.selected())) {
      var branch = alternative.variant(a.selected()).orElseThrow();
      var branches = new LinkedHashMap<>(s.branches());
      branches.put(
          a.selected(), align(a.selectedValue(), s.selectedValue(), branch.type(), limits));
      return new DraftNode.Alternative(a.selected(), branches, a.unknownFields());
    }
    if (type.shape() instanceof ValueShape.OptionalValue<?> optional
        && actual instanceof DraftNode.OptionalValue a
        && submitted instanceof DraftNode.OptionalValue s)
      return new DraftNode.OptionalValue(
          a.present(),
          a.present() ? align(a.value(), s.value(), optional.element(), limits) : s.value());
    if (type.shape() instanceof ValueShape.Defaulted<?> defaults
        && actual instanceof DraftNode.Defaulted a
        && submitted instanceof DraftNode.Defaulted s) {
      var value =
          new DraftNode.Defaulted(
              a.overridden(),
              a.overridden()
                  ? s.overridden()
                          || sameTypedDraft(a.value(), s.value(), defaults.element(), limits)
                      ? align(a.value(), s.value(), defaults.element(), limits)
                      : a.value()
                  : s.value(),
              s.retainedOverrides());
      return a.overridden() && !s.overridden() ? value.retainOverride(s.value()) : value;
    }
    if (type.shape() instanceof ValueShape.ObjectValue object
        && actual instanceof DraftNode.ObjectValue a
        && submitted instanceof DraftNode.ObjectValue s
        && a.fields().keySet().equals(s.fields().keySet())) {
      var fields = new LinkedHashMap<String, DraftNode>();
      for (var field : object.fields())
        fields.put(
            field.id(),
            align(a.fields().get(field.id()), s.fields().get(field.id()), field.type(), limits));
      return new DraftNode.ObjectValue(fields);
    }
    if (type.shape() instanceof ValueShape.Sequence sequence
        && actual instanceof DraftNode.Sequence a
        && submitted instanceof DraftNode.Sequence s
        && a.rows().size() == s.rows().size()) {
      var rows = new ArrayList<DraftNode.Row>();
      if (sequence.order() == ValueShape.CollectionOrder.UNORDERED) {
        var submittedValues = new java.util.HashMap<Object, DraftNode.Row>();
        for (var row : s.rows())
          if (submittedValues.putIfAbsent(
                  decodedIdentity(row.value(), sequence.element(), limits), row)
              != null) throw new IllegalArgumentException("Duplicate submitted set member");
        for (var row : a.rows()) {
          var matched =
              submittedValues.remove(decodedIdentity(row.value(), sequence.element(), limits));
          if (matched == null)
            throw new IllegalArgumentException("Observed set member differs from submission");
          rows.add(
              new DraftNode.Row(
                  matched.id(), align(row.value(), matched.value(), sequence.element(), limits)));
        }
        return new DraftNode.Sequence(rows, true);
      }
      for (int i = 0; i < a.rows().size(); i++)
        rows.add(
            new DraftNode.Row(
                s.rows().get(i).id(),
                align(
                    a.rows().get(i).value(), s.rows().get(i).value(), sequence.element(), limits)));
      return new DraftNode.Sequence(rows);
    }
    if (type.shape() instanceof ValueShape.Mapping mapping
        && actual instanceof DraftNode.Mapping a
        && submitted instanceof DraftNode.Mapping s
        && a.rows().size() == s.rows().size()) {
      var submittedKeys = new java.util.HashMap<Object, DraftNode.Row>();
      for (var row : s.rows()) {
        var entry = (DraftNode.ObjectValue) row.value();
        var key = decodedIdentity(entry.fields().get("key"), mapping.key(), limits);
        if (submittedKeys.putIfAbsent(key, row) != null)
          throw new IllegalArgumentException("Duplicate submitted key");
      }
      var rows = new ArrayList<DraftNode.Row>();
      for (var row : a.rows()) {
        var entry = (DraftNode.ObjectValue) row.value();
        var key = decodedIdentity(entry.fields().get("key"), mapping.key(), limits);
        var matched = submittedKeys.remove(key);
        if (matched == null)
          throw new IllegalArgumentException("Observed map key differs from submission");
        var previous = (DraftNode.ObjectValue) matched.value();
        var parts = new LinkedHashMap<String, DraftNode>();
        parts.put(
            "key",
            align(entry.fields().get("key"), previous.fields().get("key"), mapping.key(), limits));
        parts.put(
            "value",
            align(
                entry.fields().get("value"),
                previous.fields().get("value"),
                mapping.value(),
                limits));
        rows.add(new DraftNode.Row(matched.id(), new DraftNode.ObjectValue(parts)));
      }
      return new DraftNode.Mapping(rows);
    }
    return actual;
  }

  private static <T> boolean sameTypedDraft(
      DraftNode first, DraftNode second, ValueType<T> type, JsonLimits limits) {
    try {
      return type.equivalent(
          decodedIdentity(first, type, limits), decodedIdentity(second, type, limits));
    } catch (RuntimeException invalidInactive) {
      return false;
    }
  }

  private static <T> T decodedIdentity(DraftNode node, ValueType<T> type, JsonLimits limits) {
    var renderer = new Renderer(limits);
    var json =
        type.shape() instanceof ValueShape.Defaulted<?> defaults
                && node instanceof DraftNode.Defaulted value
                && !value.overridden()
            ? defaultJson(defaults).toString()
            : renderer.render(node, type, FieldPath.ROOT, SourcePath.ROOT);
    if (!new ValidationReport(renderer.issues, renderer.complete).valid())
      throw new IllegalArgumentException("Collection identity is not complete");
    try {
      return JsonInput.read(json, limits).decode(type).value().orElseThrow();
    } catch (JsonInputException invalid) {
      throw new IllegalArgumentException("Collection identity is not admissible", invalid);
    }
  }

  /** Rebase known edits in this session; changed collections stay whole. */
  DraftTree retainLaterEdits(DraftTree submitted, DraftTree actual) {
    return new DraftTree(retain(submitted.root, root, actual.root), limits, actual.basis);
  }

  private static DraftNode retain(DraftNode submitted, DraftNode current, DraftNode actual) {
    if (current.equals(submitted)) return actual;
    if (submitted instanceof DraftNode.Alternative s
        && current instanceof DraftNode.Alternative c
        && actual instanceof DraftNode.Alternative a) {
      var branches = new LinkedHashMap<>(c.branches());
      c.branches()
          .forEach(
              (id, branch) -> {
                if (s.branches().containsKey(id) && a.branches().containsKey(id))
                  branches.put(id, retain(s.branches().get(id), branch, a.branches().get(id)));
              });
      return new DraftNode.Alternative(
          c.selected().equals(s.selected()) ? a.selected() : c.selected(),
          branches,
          c.unknownFields().equals(s.unknownFields()) ? a.unknownFields() : c.unknownFields());
    }
    if (submitted instanceof DraftNode.ObjectValue s
        && current instanceof DraftNode.ObjectValue c
        && actual instanceof DraftNode.ObjectValue a
        && s.fields().keySet().equals(c.fields().keySet())
        && s.fields().keySet().equals(a.fields().keySet())) {
      var fields = new LinkedHashMap<String, DraftNode>();
      c.fields()
          .forEach(
              (key, value) ->
                  fields.put(key, retain(s.fields().get(key), value, a.fields().get(key))));
      return new DraftNode.ObjectValue(fields);
    }
    return current;
  }

  public <T> Inspection<T> inspect(DocumentDefinition<T> definition, JsonLimits jsonLimits) {
    return inspect(definition, jsonLimits, ignored -> {});
  }

  /**
   * Admit the bounded rendered representation before decoding/copying a complete value. This
   * callback grants no validation or authority; a refusal propagates without entering the decoder.
   */
  public <T> Inspection<T> inspect(
      DocumentDefinition<T> definition,
      JsonLimits jsonLimits,
      java.util.function.Consumer<String> admitRepresentation) {
    Objects.requireNonNull(admitRepresentation);
    var renderer = new Renderer(jsonLimits);
    final String json;
    try {
      json = renderer.render(root, definition.type(), FieldPath.ROOT, SourcePath.ROOT);
    } catch (RuntimeException failure) {
      return new Inspection<>(
          java.util.Optional.empty(),
          new ValidationReport(
              List.of(
                  Issue.error(
                      "validation.unavailable",
                      FieldPath.ROOT,
                      "Draft validation could not finish")),
              false),
          renderer.inputs);
    }
    var report = new ValidationReport(renderer.issues, renderer.complete);
    if (!report.valid())
      return new Inspection<>(java.util.Optional.empty(), report, renderer.inputs);
    admitRepresentation.accept(json);
    try {
      var decoded = definition.decode(JsonInput.read(json, jsonLimits));
      var merged = report.and(decoded.report());
      return new Inspection<>(
          decoded
              .value()
              .map(value -> new Candidate<>(definition, value, this, json, renderer.addresses)),
          merged,
          renderer.inputs);
    } catch (JsonInputException invalid) {
      return new Inspection<>(
          java.util.Optional.empty(),
          report.and(
              new ValidationReport(
                  List.of(Issue.error(invalid.code(), FieldPath.ROOT, invalid.getMessage())),
                  true)),
          renderer.inputs);
    } catch (RuntimeException failure) {
      return new Inspection<>(
          java.util.Optional.empty(),
          report.and(
              new ValidationReport(
                  List.of(
                      Issue.error(
                          "validation.unavailable",
                          FieldPath.ROOT,
                          "Complete candidate validation could not finish")),
                  false)),
          renderer.inputs);
    }
  }

  private static final class Renderer {
    private final JsonLimits limits;
    private final List<Issue> issues = new ArrayList<>();
    private final Map<FieldPath, InputState> inputs = new LinkedHashMap<>();
    private final Map<SourcePath, FieldPath> addresses = new LinkedHashMap<>();
    private boolean complete = true;

    private Renderer(JsonLimits limits) {
      this.limits = limits;
    }

    private String render(DraftNode node, ValueType<?> type, FieldPath path, SourcePath source) {
      if (type.shape() instanceof ValueShape.Reference<?> reference)
        return render(node, reference.element(), path, source);
      addresses.put(source, path);
      if (node instanceof DraftNode.Missing)
        return fail(path, InputState.EMPTY, "input.required", "Enter a required value");
      if (type.shape() instanceof ValueShape.Alternative alternative
          && node instanceof DraftNode.Alternative value) {
        var keyPath = path.append(FieldPath.Part.KEY);
        addresses.put(source.property(alternative.discriminator()), keyPath);
        for (String unknown : value.unknownFields().keySet())
          fail(
              unknown.isBlank() ? path : path.field(unknown),
              InputState.INVALID,
              "input.unknown_field",
              "Remove or resolve the unknown field");
        var branch = alternative.variant(value.selected());
        if (branch.isEmpty())
          return fail(keyPath, InputState.INVALID, "input.variant", "Select a declared variant");
        inputs.put(keyPath, InputState.VALID);
        return "{"
            + JSON.toJson(alternative.discriminator())
            + ":"
            + JSON.toJson(value.selected())
            + ","
            + JSON.toJson(alternative.payload())
            + ":"
            + render(
                value.selectedValue(),
                branch.orElseThrow().type(),
                path.variant(value.selected()),
                source.property(alternative.payload()))
            + "}";
      }
      if (type.shape() instanceof ValueShape.OptionalValue<?> optional
          && node instanceof DraftNode.OptionalValue value) {
        if (!value.present()) {
          inputs.put(path, InputState.VALID);
          return optional.encoding() == ValueShape.OptionalEncoding.SINGLETON_LIST ? "[]" : "null";
        }
        var childSource =
            optional.encoding() == ValueShape.OptionalEncoding.SINGLETON_LIST
                ? source.index(0)
                : source;
        int before = issues.size();
        var child =
            render(
                value.value(), optional.element(), path.append(FieldPath.Part.VALUE), childSource);
        if (optional.encoding() == ValueShape.OptionalEncoding.SINGLETON_LIST)
          return "[" + child + "]";
        if (issues.size() == before) {
          try {
            if (JsonInput.read(child, limits).copyTree().isJsonNull())
              return fail(
                  path,
                  InputState.INVALID,
                  "input.optional_ambiguous",
                  "A present optional cannot use its empty encoding");
          } catch (JsonInputException invalid) {
            return fail(path, InputState.INVALID, invalid.code(), invalid.getMessage());
          }
        }
        return child;
      }
      if (node instanceof DraftNode.NullValue && !(type.shape() instanceof ValueShape.Custom))
        return fail(
            path, InputState.INVALID, "input.null", "Explicit null is not admitted for this value");
      if (type.shape() instanceof ValueShape.Scalar scalar
          && node instanceof DraftNode.Scalar value) return scalar(value, scalar, type, path);
      if (type.shape() instanceof ValueShape.Defaulted<?> defaults
          && node instanceof DraftNode.Defaulted value) {
        if (!value.overridden())
          return fail(
              path,
              InputState.INVALID,
              "input.omitted_value",
              "An omitted default is only valid as a record field");
        return render(value.value(), defaults.element(), path.append(FieldPath.Part.VALUE), source);
      }
      if (type.shape() instanceof ValueShape.Mapping mapping
          && node instanceof DraftNode.Mapping value) return mapping(value, mapping, path, source);
      if (type.shape() instanceof ValueShape.ObjectValue object
          && node instanceof DraftNode.ObjectValue value) {
        var parts = new ArrayList<String>();
        var known = new java.util.HashSet<String>();
        for (var field : object.fields()) {
          known.add(field.id());
          var child = value.fields().getOrDefault(field.id(), new DraftNode.Missing());
          if (omittedField(field.type(), child)) continue;
          parts.add(
              JSON.toJson(field.id())
                  + ":"
                  + render(
                      child, field.type(), path.field(field.id()), source.property(field.id())));
        }
        for (String key : value.fields().keySet()) {
          if (!known.contains(key))
            fail(
                key.isBlank() ? path : path.field(key),
                InputState.INVALID,
                "input.unknown_field",
                "Remove or resolve the unknown field");
        }
        return "{" + String.join(",", parts) + "}";
      }
      if (type.shape() instanceof ValueShape.Sequence sequence
          && node instanceof DraftNode.Sequence value) {
        if ((sequence.order() == ValueShape.CollectionOrder.UNORDERED) != value.unordered())
          return fail(
              path,
              InputState.INVALID,
              "input.structure",
              "Collection ordering differs from its declared type");
        if (value.rows().size() > sequence.maximumSize())
          fail(path, InputState.INVALID, "input.length", "Collection exceeds its declared size");
        var parts = new ArrayList<String>();
        var members = new java.util.HashMap<Object, FieldPath>();
        for (int i = 0; i < value.rows().size(); i++) {
          var row = value.rows().get(i);
          var rowPath = path.row(row.id());
          int before = issues.size();
          var rendered = render(row.value(), sequence.element(), rowPath, source.index(i));
          parts.add(rendered);
          if (sequence.unique() && issues.size() == before) {
            try {
              var decoded =
                  sequence
                      .element()
                      .decode(
                          JsonOps.INSTANCE, JsonInput.read(rendered, limits).copyTree(), rowPath);
              issues.addAll(decoded.report().issues());
              complete &= decoded.report().complete();
              if (decoded.value().isPresent() && decoded.report().valid()) {
                var previous = members.putIfAbsent(decoded.value().orElseThrow(), rowPath);
                if (previous != null) {
                  issues.add(
                      new Issue(
                          "input.duplicate_member",
                          Issue.Severity.ERROR,
                          rowPath,
                          List.of(previous),
                          Map.of(),
                          "Resolve the duplicate collection member"));
                  inputs.put(rowPath, InputState.INVALID);
                }
              }
            } catch (JsonInputException invalid) {
              fail(rowPath, InputState.INVALID, invalid.code(), invalid.getMessage());
            }
          }
        }
        return "[" + String.join(",", parts) + "]";
      }
      if (type.shape() instanceof ValueShape.Custom && node instanceof DraftNode.Opaque opaque) {
        try {
          var parsed = JsonInput.read(opaque.json(), limits);
          var decoded = type.decode(JsonOps.INSTANCE, parsed.copyTree(), path);
          issues.addAll(decoded.report().issues());
          complete &= decoded.report().complete();
          inputs.put(
              path,
              decoded.report().valid()
                  ? InputState.VALID
                  : decoded.report().complete() ? InputState.INVALID : InputState.UNAVAILABLE);
          return opaque.json();
        } catch (JsonInputException invalid) {
          return fail(path, InputState.INVALID, invalid.code(), invalid.getMessage());
        }
      }
      return fail(
          path,
          InputState.INVALID,
          "input.structure",
          "Draft structure differs from its declared value type");
    }

    private String mapping(
        DraftNode.Mapping value, ValueShape.Mapping shape, FieldPath path, SourcePath source) {
      if (value.rows().size() > shape.maximumSize())
        fail(path, InputState.INVALID, "input.length", "Map exceeds its declared size");
      var parts = new ArrayList<String>();
      var keys = new java.util.HashMap<Object, FieldPath>();
      boolean object = shape.encoding() == ValueShape.MapEncoding.OBJECT;
      for (int i = 0; i < value.rows().size(); i++) {
        var row = value.rows().get(i);
        var rowPath = path.row(row.id());
        var keyPath = rowPath.append(FieldPath.Part.KEY);
        var valuePath = rowPath.append(FieldPath.Part.VALUE);
        var entrySource = source.index(i);
        if (!(row.value() instanceof DraftNode.ObjectValue entry)) {
          fail(
              rowPath,
              InputState.INVALID,
              "input.map_entry",
              "A map entry requires a key and value");
          continue;
        }
        for (String name : entry.fields().keySet())
          if (!name.equals("key") && !name.equals("value"))
            fail(
                rowPath,
                InputState.INVALID,
                "input.unknown_field",
                "Remove or resolve the unknown map-entry field");
        if (!object) addresses.put(entrySource, rowPath);
        var keySource = entrySource.property("key");
        int before = issues.size();
        var rawKey = entry.fields().getOrDefault("key", new DraftNode.Missing());
        boolean omittedKey = !object && omittedField(shape.key(), rawKey);
        var keyJson =
            omittedKey
                ? defaultJson((ValueShape.Defaulted<?>) shape.key().shape()).toString()
                : render(rawKey, shape.key(), keyPath, keySource);
        String name = null;
        if (issues.size() == before) {
          try {
            var parsed = JsonInput.read(keyJson, limits);
            var encoded = parsed.copyTree();
            if (object
                && (!encoded.isJsonPrimitive() || !encoded.getAsJsonPrimitive().isString())) {
              fail(
                  keyPath,
                  InputState.INVALID,
                  "input.map_key_encoding",
                  "Object map keys must encode as strings");
            } else {
              if (object) name = encoded.getAsString();
              var decoded = shape.key().decode(JsonOps.INSTANCE, encoded, keyPath);
              issues.addAll(decoded.report().issues());
              complete &= decoded.report().complete();
              if (decoded.value().isPresent() && decoded.report().valid()) {
                var previous = keys.putIfAbsent(decoded.value().orElseThrow(), keyPath);
                if (previous != null) {
                  issues.add(
                      new Issue(
                          "input.duplicate_key",
                          Issue.Severity.ERROR,
                          keyPath,
                          List.of(previous),
                          Map.of(),
                          "Resolve the duplicate map key"));
                  inputs.put(keyPath, InputState.INVALID);
                } else inputs.put(keyPath, InputState.VALID);
              } else
                inputs.put(
                    keyPath,
                    decoded.report().complete() ? InputState.INVALID : InputState.UNAVAILABLE);
            }
          } catch (JsonInputException invalid) {
            fail(keyPath, InputState.INVALID, invalid.code(), invalid.getMessage());
          }
        }
        if (object) {
          addresses.remove(keySource);
          if (name != null) addresses.put(source.key(name), keyPath);
        }
        var rawValue = entry.fields().getOrDefault("value", new DraftNode.Missing());
        boolean omittedValue = !object && omittedField(shape.value(), rawValue);
        var encodedValue =
            omittedValue
                ? null
                : render(
                    rawValue,
                    shape.value(),
                    valuePath,
                    object
                        ? source.property(name == null ? "" : name)
                        : entrySource.property("value"));
        if (object) parts.add(JSON.toJson(name == null ? "" : name) + ":" + encodedValue);
        else {
          var fields = new ArrayList<String>();
          if (!omittedKey) fields.add("\"key\":" + keyJson);
          if (!omittedValue) fields.add("\"value\":" + encodedValue);
          parts.add("{" + String.join(",", fields) + "}");
        }
      }
      return object ? "{" + String.join(",", parts) + "}" : "[" + String.join(",", parts) + "]";
    }

    private static boolean omittedField(ValueType<?> type, DraftNode node) {
      return type.shape() instanceof ValueShape.Defaulted<?>
          && node instanceof DraftNode.Defaulted value
          && !value.overridden();
    }

    private String scalar(
        DraftNode.Scalar value, ValueShape.Scalar shape, ValueType<?> type, FieldPath path) {
      String raw = value.raw();
      try {
        if (value.unitInput().isPresent()) {
          var input = value.unitInput().orElseThrow();
          if (shape.numericDisplay().isEmpty())
            return fail(
                path,
                InputState.UNAVAILABLE,
                "input.unit_unavailable",
                "The original unit definition is unavailable; retained the input");
          final NumericDisplay.Conversion mapped;
          try {
            mapped = shape.numericDisplay().orElseThrow().toStored(input.text(), input.unit());
          } catch (IllegalArgumentException unavailable) {
            return fail(
                path,
                InputState.UNAVAILABLE,
                "input.unit_unavailable",
                "The original unit is unavailable; retained the input");
          }
          if (mapped.state() != input.state() || !mapped.text().equals(raw))
            return fail(
                path,
                InputState.UNAVAILABLE,
                "input.unit_changed",
                "The unit mapping changed; review the retained input");
          if (mapped.convertedText().isEmpty()) {
            var state =
                switch (mapped.state()) {
                  case EMPTY -> InputState.EMPTY;
                  case INCOMPLETE -> InputState.INCOMPLETE;
                  default -> InputState.INVALID;
                };
            return fail(path, state, "input.unit_conversion", mapped.reason());
          }
        }
        final JsonElement json;
        final String literal;
        if (shape.kind() == ValueShape.ScalarKind.TEXT
            || shape.kind() == ValueShape.ScalarKind.CHOICE) {
          for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isHighSurrogate(c)) {
              if (++i == raw.length())
                return fail(
                    path, InputState.INCOMPLETE, "input.unicode", "Complete the Unicode character");
              if (!Character.isLowSurrogate(raw.charAt(i)))
                return fail(path, InputState.INVALID, "input.unicode", "Invalid Unicode character");
            } else if (Character.isLowSurrogate(c))
              return fail(path, InputState.INVALID, "input.unicode", "Invalid Unicode character");
          }
          json = new JsonPrimitive(raw);
          literal = JSON.toJson(raw);
        } else if (shape.kind() == ValueShape.ScalarKind.BOOLEAN) {
          if (!raw.equals("true") && !raw.equals("false"))
            return fail(
                path,
                raw.isBlank() ? InputState.EMPTY : InputState.INVALID,
                "input.boolean",
                "Expected true or false");
          json = new JsonPrimitive(Boolean.parseBoolean(raw));
          literal = raw;
        } else {
          String text = raw.strip();
          if (text.isEmpty())
            return fail(path, InputState.EMPTY, "input.required", "Enter a number");
          if (text.length() > limits.maximumNumberCharacters())
            return fail(
                path,
                InputState.INVALID,
                "input.number_length",
                "Number exceeds its representation limit");
          if (design.inherent.reform.serialization.DecimalText.incomplete(text))
            return fail(path, InputState.INCOMPLETE, "input.incomplete", "Complete the number");
          var number = new BigDecimal(text);
          if (Math.abs((long) number.scale()) > limits.maximumAbsoluteScale())
            return fail(
                path, InputState.INVALID, "input.number_scale", "Number exceeds its scale limit");
          json =
              number.signum() == 0 && text.startsWith("-")
                  ? new JsonPrimitive(-0.0d)
                  : new JsonPrimitive(number);
          literal =
              JSON_NUMBER.matcher(text).matches()
                  ? text
                  : number.signum() == 0 && text.startsWith("-") ? "-0" : number.toString();
        }
        var decoded = type.decode(JsonOps.INSTANCE, json, path);
        issues.addAll(decoded.report().issues());
        complete &= decoded.report().complete();
        inputs.put(
            path,
            decoded.report().valid()
                ? InputState.VALID
                : decoded.report().complete() ? InputState.INVALID : InputState.UNAVAILABLE);
        return literal;
      } catch (NumberFormatException invalid) {
        return fail(path, InputState.INVALID, "input.number", "Invalid number");
      } catch (RuntimeException failure) {
        complete = false;
        return fail(
            path,
            InputState.UNAVAILABLE,
            "validation.unavailable",
            "The declared value decoder could not finish");
      }
    }

    private String fail(FieldPath path, InputState state, String code, String message) {
      issues.add(Issue.error(code, path, message));
      inputs.put(path, state);
      return "null";
    }
  }
}
