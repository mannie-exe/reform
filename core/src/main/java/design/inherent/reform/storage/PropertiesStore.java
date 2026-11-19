package design.inherent.reform.storage;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonInputException;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * UTF-8 Reader properties with strict duplicate keys and explicitly declared flat scalar fields.
 */
public final class PropertiesStore<T> extends TextStore<T> {
  public record Limits(
      int maximumProperties,
      int maximumPhysicalLines,
      int maximumKeyCharacters,
      int maximumValueCharacters) {
    public static final Limits STANDARD = new Limits(1024, 16384, 4096, 32768);

    public Limits {
      if (maximumProperties < 1
          || maximumPhysicalLines < 1
          || maximumKeyCharacters < 1
          || maximumValueCharacters < 1)
        throw new IllegalArgumentException("Properties admission bounds must be positive");
    }
  }

  private record Chunk(String text, boolean continuedAtEnd) {}

  private static final Gson JSON = new Gson();
  private final Map<String, ValueShape.ScalarKind> fields;
  private final Limits limits;

  public PropertiesStore(
      DocumentDefinition<T> definition,
      OwnedFile file,
      JsonLimits valueLimits,
      DraftLimits draftLimits) {
    this(definition, file, valueLimits, draftLimits, Limits.STANDARD);
  }

  public PropertiesStore(
      DocumentDefinition<T> definition,
      OwnedFile file,
      JsonLimits valueLimits,
      DraftLimits draftLimits,
      Limits limits) {
    super(
        definition,
        file,
        valueLimits,
        draftLimits,
        "properties",
        "Owned declared properties source");
    this.limits = Objects.requireNonNull(limits);
    if (!(definition.type().shape() instanceof ValueShape.ObjectValue object)
        || object.fields().size() > limits.maximumProperties())
      throw new IllegalArgumentException("Properties require an admitted flat record definition");
    var declared = new LinkedHashMap<String, ValueShape.ScalarKind>();
    for (var field : object.fields()) declared.put(field.id(), scalar(field.type()));
    fields = java.util.Collections.unmodifiableMap(declared);
  }

  private ValueShape.ScalarKind scalar(ValueType<?> type) {
    for (int depth = 0; depth < 128; depth++) {
      var shape = type.shape();
      if (shape instanceof ValueShape.Defaulted<?> value) type = value.element();
      else if (shape instanceof ValueShape.Reference<?> value) type = value.element();
      else if (shape instanceof ValueShape.Scalar value) return value.kind();
      else break;
    }
    throw new IllegalArgumentException("Properties fields require scalar/reference/default types");
  }

  @Override
  protected DraftTree decode(String text) {
    var values = parse(text);
    var projected = new JsonObject();
    for (var entry : values.entrySet()) {
      var kind = fields.get(entry.getKey());
      String raw = entry.getValue();
      if (kind == null
          || kind == ValueShape.ScalarKind.TEXT
          || kind == ValueShape.ScalarKind.CHOICE) {
        projected.addProperty(entry.getKey(), raw);
      } else if (kind == ValueShape.ScalarKind.BOOLEAN) {
        if (!raw.equals("true") && !raw.equals("false"))
          throw invalid(
              "properties.boolean",
              FieldPath.ROOT.field(entry.getKey()),
              "Expected exactly true or false");
        projected.addProperty(entry.getKey(), Boolean.parseBoolean(raw));
      } else {
        try {
          var value = JsonInput.read(raw, jsonLimits).copyTree();
          if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber())
            throw invalid(
                "properties.number",
                FieldPath.ROOT.field(entry.getKey()),
                "Expected a complete finite numeric property");
          projected.add(entry.getKey(), value);
        } catch (JsonInputException bad) {
          throw invalid(
              "properties.number",
              FieldPath.ROOT.field(entry.getKey()),
              "Expected a complete finite numeric property");
        }
      }
    }
    try {
      return DraftTree.fromParsed(
          definition.type(), JsonInput.read(JSON.toJson(projected), jsonLimits), draftLimits);
    } catch (JsonInputException bound) {
      throw unavailable(
          "properties.projection",
          FieldPath.ROOT,
          "Properties values exceed their declared codec projection limits");
    }
  }

  @Override
  protected String encode(Read<T> baseline, Candidate<T> candidate) {
    String before = baseline.source().text().orElse("");
    var desired = new LinkedHashMap<String, String>();
    try {
      var object =
          JsonInput.read(candidate.authoredJson(), jsonLimits).copyTree().getAsJsonObject();
      for (var entry : object.entrySet()) {
        if (!entry.getValue().isJsonPrimitive())
          throw new IllegalArgumentException("Properties require scalar authored values");
        desired.put(entry.getKey(), entry.getValue().getAsString());
      }
    } catch (JsonInputException invalid) {
      throw new IllegalArgumentException("Authored values exceed their projection bounds", invalid);
    }
    var remaining = new LinkedHashMap<>(desired);
    var result = new StringBuilder();
    boolean unfinished = false;
    for (var chunk : chunks(before)) {
      var values = one(chunk.text());
      if (values.isEmpty()) {
        result.append(chunk.text());
        unfinished = chunk.continuedAtEnd();
        continue;
      }
      var entry = values.entrySet().iterator().next();
      if (!remaining.containsKey(entry.getKey())) continue;
      var next = remaining.remove(entry.getKey());
      if (next.equals(entry.getValue())) {
        result.append(chunk.text());
        unfinished = chunk.continuedAtEnd();
      } else {
        int indent = 0;
        while (indent < chunk.text().length() && whitespace(chunk.text().charAt(indent))) indent++;
        result
            .append(chunk.text(), 0, indent)
            .append(line(entry.getKey(), next))
            .append(ending(chunk.text()));
        unfinished = false;
      }
    }
    String newline = preferredNewline(before);
    if (!remaining.isEmpty()) {
      if (!result.isEmpty() && ending(result.toString()).isEmpty()) result.append(newline);
      if (unfinished) result.append(newline);
      for (var entry : remaining.entrySet())
        result.append(line(entry.getKey(), entry.getValue())).append(newline);
    }
    String after = result.toString();
    if (!parse(after).equals(desired))
      throw new IllegalArgumentException(
          "Properties transformation did not preserve intended values");
    return after;
  }

  @Override
  protected void validateEncoded(String text) {
    decode(text);
  }

  private Map<String, String> parse(String text) {
    var result = new LinkedHashMap<String, String>();
    for (var chunk : chunks(text))
      for (var entry : one(chunk.text()).entrySet()) {
        if (entry.getKey().length() > limits.maximumKeyCharacters()
            || entry.getValue().length() > limits.maximumValueCharacters())
          throw unavailable(
              "properties.value_limit", FieldPath.ROOT, "Properties key/value admission exhausted");
        if (result.size() >= limits.maximumProperties())
          throw unavailable(
              "properties.entry_limit", FieldPath.ROOT, "Properties entry admission exhausted");
        if (result.putIfAbsent(entry.getKey(), entry.getValue()) != null)
          throw invalid("properties.duplicate", FieldPath.ROOT, "Duplicate logical property key");
      }
    if (!nativeValues(text).equals(result))
      throw invalid(
          "properties.boundary",
          FieldPath.ROOT,
          "Properties logical lines disagree with the native parser");
    return result;
  }

  private Map<String, String> one(String text) {
    var values = nativeValues(text);
    if (values.size() > 1)
      throw invalid(
          "properties.boundary", FieldPath.ROOT, "Properties logical line could not be identified");
    return values;
  }

  private Map<String, String> nativeValues(String text) {
    var properties = new Properties();
    try {
      properties.load(new StringReader(text));
    } catch (IOException | IllegalArgumentException invalid) {
      throw invalid("properties.syntax", FieldPath.ROOT, "Malformed properties source");
    }
    var values = new LinkedHashMap<String, String>();
    properties.forEach((key, value) -> values.put((String) key, (String) value));
    return values;
  }

  private List<Chunk> chunks(String text) {
    var result = new ArrayList<Chunk>();
    int start = 0, cursor = 0, physical = 0;
    boolean continued = false, comment = false;
    while (cursor < text.length()) {
      if (++physical > limits.maximumPhysicalLines())
        throw unavailable(
            "properties.line_limit",
            FieldPath.ROOT,
            "Properties physical-line admission exhausted");
      int lineStart = cursor;
      while (cursor < text.length() && text.charAt(cursor) != '\r' && text.charAt(cursor) != '\n')
        cursor++;
      int end = cursor;
      if (!continued) {
        int at = lineStart;
        while (at < end && whitespace(text.charAt(at))) at++;
        comment = at < end && (text.charAt(at) == '#' || text.charAt(at) == '!');
      }
      int slashes = 0;
      for (int at = end - 1; at >= lineStart && text.charAt(at) == '\\'; at--) slashes++;
      continued = !comment && slashes % 2 == 1;
      if (cursor < text.length()) {
        char terminator = text.charAt(cursor++);
        if (terminator == '\r' && cursor < text.length() && text.charAt(cursor) == '\n') cursor++;
      }
      if (!continued) {
        result.add(new Chunk(text.substring(start, cursor), false));
        start = cursor;
      }
    }
    if (start < text.length()) result.add(new Chunk(text.substring(start), continued));
    return result;
  }

  private static boolean whitespace(char value) {
    return value == ' ' || value == '\t' || value == '\f';
  }

  private static String ending(String text) {
    if (text.endsWith("\r\n")) return "\r\n";
    if (text.endsWith("\n")) return "\n";
    return text.endsWith("\r") ? "\r" : "";
  }

  private static String preferredNewline(String text) {
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') return "\n";
      if (text.charAt(i) == '\r')
        return i + 1 < text.length() && text.charAt(i + 1) == '\n' ? "\r\n" : "\r";
    }
    return "\n";
  }

  private static String line(String key, String value) {
    var properties = new Properties();
    properties.setProperty(key, value);
    var output = new ByteArrayOutputStream();
    try {
      properties.store(output, null);
    } catch (IOException impossible) {
      throw new IllegalStateException("Memory property serialization failed", impossible);
    }
    String text = output.toString(StandardCharsets.ISO_8859_1);
    int end = 0;
    while (end < text.length() && text.charAt(end) != '\r' && text.charAt(end) != '\n') end++;
    if (end == text.length()) throw new IllegalStateException("Native property header is missing");
    if (text.charAt(end++) == '\r' && end < text.length() && text.charAt(end) == '\n') end++;
    String assignment = text.substring(end);
    return assignment.substring(0, assignment.length() - ending(assignment).length());
  }

  private static InvalidData invalid(String code, FieldPath path, String message) {
    return new InvalidData(new ValidationReport(List.of(Issue.error(code, path, message)), true));
  }

  private static InvalidData unavailable(String code, FieldPath path, String message) {
    return new InvalidData(new ValidationReport(List.of(Issue.error(code, path, message)), false));
  }
}
