package design.inherent.reform.edit;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.FieldPath;
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
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** Persistent raw edits. Inspection is explicit and pure; it performs no file/world operation. */
public final class DraftTree {
  private static final Gson JSON = new Gson();
  private static final Pattern INCOMPLETE_NUMBER =
      Pattern.compile("[+-]?\\.?|[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)[eE][+-]?");
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

  private final DraftNode root;
  private final DraftLimits limits;

  public DraftTree(DraftNode root, DraftLimits limits) {
    this.root = Objects.requireNonNull(root);
    this.limits = Objects.requireNonNull(limits);
    limits.check(root);
  }

  public DraftNode root() {
    return root;
  }

  public DraftLimits limits() {
    return limits;
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
    return new DraftTree(
        build(type, source.copyTree(), SourcePath.ROOT, source.scalarText()), limits);
  }

  private static DraftNode build(
      ValueType<?> type, JsonElement value, SourcePath path, Map<SourcePath, String> text) {
    if (value == null) return new DraftNode.Missing();
    if (type.shape() instanceof ValueShape.Custom)
      return new DraftNode.Opaque(sourceJson(value, path, text));
    if (value.isJsonNull()) return new DraftNode.NullValue();
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
      var fields = new LinkedHashMap<String, DraftNode>();
      for (var field : object.fields())
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
    if (type.shape() instanceof ValueShape.Sequence sequence && value.isJsonArray()) {
      var rows = new ArrayList<DraftNode.Row>();
      for (int i = 0; i < value.getAsJsonArray().size(); i++)
        rows.add(
            DraftNode.Row.create(
                build(sequence.element(), value.getAsJsonArray().get(i), path.index(i), text)));
      return new DraftNode.Sequence(rows);
    }
    return new DraftNode.Opaque(sourceJson(value, path, text));
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
    DraftNode current = root;
    for (var segment : path.segments()) {
      if (segment instanceof FieldPath.Field field
          && current instanceof DraftNode.ObjectValue object) {
        current = object.fields().get(field.id());
        if (current == null)
          throw new IllegalArgumentException("Field no longer exists in this draft");
      } else if (segment instanceof FieldPath.Row row
          && current instanceof DraftNode.Sequence sequence) {
        current = sequence.rows().get(sequence.index(row.id())).value();
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

  public DraftTree change(FieldPath path, UnaryOperator<DraftNode> operation) {
    return new DraftTree(change(root, path.segments(), 0, operation), limits);
  }

  /** Apply independent review choices with one final budget check. */
  DraftTree replaceAll(Map<FieldPath, DraftNode> values) {
    var changed = root;
    for (var entry : values.entrySet())
      changed = change(changed, entry.getKey().segments(), 0, ignored -> entry.getValue());
    return new DraftTree(changed, limits);
  }

  private static DraftNode change(
      DraftNode node,
      List<FieldPath.Segment> path,
      int offset,
      UnaryOperator<DraftNode> operation) {
    if (offset == path.size()) return Objects.requireNonNull(operation.apply(node));
    var segment = path.get(offset);
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
    throw new IllegalArgumentException("Path does not address this draft structure");
  }

  public boolean sameValue(DraftTree other) {
    return DraftNode.sameValue(root, other.root);
  }

  /** Preserve identities only within branches whose authored values are unchanged. */
  DraftTree reuseUnchangedFrom(DraftTree previous) {
    return new DraftTree(reuseUnchanged(root, previous.root), limits);
  }

  private static DraftNode reuseUnchanged(DraftNode next, DraftNode previous) {
    if (DraftNode.sameValue(next, previous)) return previous;
    if (next instanceof DraftNode.ObjectValue n
        && previous instanceof DraftNode.ObjectValue p
        && n.fields().keySet().equals(p.fields().keySet())) {
      var fields = new LinkedHashMap<String, DraftNode>();
      n.fields()
          .forEach((key, value) -> fields.put(key, reuseUnchanged(value, p.fields().get(key))));
      return new DraftNode.ObjectValue(fields);
    }
    return next;
  }

  /** Only for an already qualified equal-value saved observation, never an identity heuristic. */
  DraftTree alignRows(DraftTree submitted) {
    return new DraftTree(align(root, submitted.root), limits);
  }

  private static DraftNode align(DraftNode actual, DraftNode submitted) {
    if (actual instanceof DraftNode.ObjectValue a
        && submitted instanceof DraftNode.ObjectValue s
        && a.fields().keySet().equals(s.fields().keySet())) {
      var fields = new LinkedHashMap<String, DraftNode>();
      a.fields().forEach((key, value) -> fields.put(key, align(value, s.fields().get(key))));
      return new DraftNode.ObjectValue(fields);
    }
    if (actual instanceof DraftNode.Sequence a
        && submitted instanceof DraftNode.Sequence s
        && a.rows().size() == s.rows().size()) {
      var rows = new ArrayList<DraftNode.Row>();
      for (int i = 0; i < a.rows().size(); i++)
        rows.add(
            new DraftNode.Row(
                s.rows().get(i).id(), align(a.rows().get(i).value(), s.rows().get(i).value())));
      return new DraftNode.Sequence(rows);
    }
    return actual;
  }

  /** Rebase known edits in this session; changed collections stay whole. */
  DraftTree retainLaterEdits(DraftTree submitted, DraftTree actual) {
    return new DraftTree(retain(submitted.root, root, actual.root), limits);
  }

  private static DraftNode retain(DraftNode submitted, DraftNode current, DraftNode actual) {
    if (current.equals(submitted)) return actual;
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
      addresses.put(source, path);
      if (node instanceof DraftNode.Missing)
        return fail(path, InputState.EMPTY, "input.required", "Enter a required value");
      if (node instanceof DraftNode.NullValue && !(type.shape() instanceof ValueShape.Custom))
        return fail(
            path, InputState.INVALID, "input.null", "Explicit null is not admitted for this value");
      if (type.shape() instanceof ValueShape.Scalar scalar
          && node instanceof DraftNode.Scalar value) return scalar(value.raw(), scalar, type, path);
      if (type.shape() instanceof ValueShape.ObjectValue object
          && node instanceof DraftNode.ObjectValue value) {
        var parts = new ArrayList<String>();
        var known = new java.util.HashSet<String>();
        for (var field : object.fields()) {
          known.add(field.id());
          var child = value.fields().getOrDefault(field.id(), new DraftNode.Missing());
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
        if (value.rows().size() > sequence.maximumSize())
          fail(path, InputState.INVALID, "input.length", "Collection exceeds its declared size");
        var parts = new ArrayList<String>();
        for (int i = 0; i < value.rows().size(); i++) {
          var row = value.rows().get(i);
          parts.add(render(row.value(), sequence.element(), path.row(row.id()), source.index(i)));
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

    private String scalar(String raw, ValueShape.Scalar shape, ValueType<?> type, FieldPath path) {
      try {
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
          if (INCOMPLETE_NUMBER.matcher(text).matches())
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
