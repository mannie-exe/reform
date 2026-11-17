package design.inherent.reform.serialization;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.validation.DecodedValue;
import design.inherent.reform.value.ValueType;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

/** Strict bounded JSON admission before information-losing DOM or codec conversion. */
public final class JsonInput {
  public static final class Parsed {
    private final String source;
    private final JsonElement tree;
    private final Map<SourcePath, String> scalarText;

    private Parsed(String source, JsonElement tree, Map<SourcePath, String> scalarText) {
      this.source = source;
      this.tree = tree;
      this.scalarText = Map.copyOf(scalarText);
    }

    public String source() {
      return source;
    }

    /** Caller-owned DOM copy; neither codecs nor editors can mutate this parsed source. */
    public JsonElement copyTree() {
      return tree.deepCopy();
    }

    /** Original number spelling, or decoded text for other scalar kinds. */
    public Map<SourcePath, String> scalarText() {
      return scalarText;
    }

    public <T> DecodedValue<T> decode(ValueType<T> type) {
      return Objects.requireNonNull(type).decode(JsonOps.INSTANCE, tree.deepCopy(), FieldPath.ROOT);
    }

    /** A decode is complete or failed. Native partial recovery is never promoted to success. */
    public <T> DataResult<T> decode(Codec<T> codec) {
      var result = Objects.requireNonNull(codec).parse(JsonOps.INSTANCE, tree.deepCopy());
      if (result.error().isPresent()) {
        String message = result.error().orElseThrow().message();
        return DataResult.error(() -> message, result.lifecycle());
      }
      return result;
    }
  }

  public static Parsed read(byte[] bytes, JsonLimits limits) throws JsonInputException {
    Objects.requireNonNull(bytes);
    Objects.requireNonNull(limits);
    if (bytes.length > limits.maximumBytes()) {
      throw new JsonInputException("json.bytes", "$", "JSON exceeds its owner's byte limit");
    }
    final String source;
    try {
      source =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException invalid) {
      throw new JsonInputException("json.encoding", "$", "JSON is not valid UTF-8");
    }
    return parse(source, limits);
  }

  public static Parsed read(String source, JsonLimits limits) throws JsonInputException {
    Objects.requireNonNull(source);
    Objects.requireNonNull(limits);
    if (source.length() > limits.maximumBytes()) {
      throw new JsonInputException("json.bytes", "$", "JSON exceeds its owner's byte limit");
    }
    try {
      var bytes =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(java.nio.CharBuffer.wrap(source));
      if (bytes.remaining() > limits.maximumBytes()) {
        throw new JsonInputException("json.bytes", "$", "JSON exceeds its owner's byte limit");
      }
    } catch (CharacterCodingException invalid) {
      throw new JsonInputException("json.encoding", "$", "JSON contains malformed Unicode");
    }
    return parse(source, limits);
  }

  private static Parsed parse(String source, JsonLimits limits) throws JsonInputException {
    try (var reader = new JsonReader(new StringReader(source))) {
      reader.setLenient(false);
      var parser = new Parser(reader, limits);
      JsonElement tree = parser.read(0, SourcePath.ROOT);
      try {
        if (reader.peek() != JsonToken.END_DOCUMENT) {
          throw parser.fail("json.trailing", "Expected one complete JSON document");
        }
      } catch (JsonInputException invalid) {
        throw invalid;
      } catch (IOException invalid) {
        throw parser.fail("json.trailing", "Expected one complete JSON document");
      }
      return new Parsed(source, tree, parser.scalarText);
    } catch (JsonInputException invalid) {
      throw invalid;
    } catch (IOException | IllegalStateException | NumberFormatException invalid) {
      throw new JsonInputException("json.syntax", "$", "Malformed JSON document");
    }
  }

  private static final class Parser {
    private final JsonReader reader;
    private final JsonLimits limits;
    private int nodes;
    private final Map<SourcePath, String> scalarText = new HashMap<>();

    private Parser(JsonReader reader, JsonLimits limits) {
      this.reader = reader;
      this.limits = limits;
    }

    private JsonElement read(int depth, SourcePath path) throws IOException {
      if (depth > limits.maximumDepth())
        throw fail("json.depth", "JSON nesting exceeds its owner's limit");
      if (++nodes > limits.maximumNodes())
        throw fail("json.nodes", "JSON node count exceeds its owner's limit");
      return switch (reader.peek()) {
        case BEGIN_OBJECT -> object(depth, path);
        case BEGIN_ARRAY -> array(depth, path);
        case STRING -> {
          String value = text(reader.nextString());
          scalarText.put(path, value);
          yield new JsonPrimitive(value);
        }
        case NUMBER -> number(path);
        case BOOLEAN -> {
          boolean value = reader.nextBoolean();
          scalarText.put(path, String.valueOf(value));
          yield new JsonPrimitive(value);
        }
        case NULL -> {
          reader.nextNull();
          scalarText.put(path, "null");
          yield JsonNull.INSTANCE;
        }
        default -> throw fail("json.syntax", "Expected a JSON value");
      };
    }

    private JsonObject object(int depth, SourcePath path) throws IOException {
      reader.beginObject();
      var object = new JsonObject();
      var keys = new HashSet<String>();
      while (reader.hasNext()) {
        String name = text(reader.nextName());
        if (!keys.add(name)) throw fail("json.duplicate_key", "Duplicate JSON object key");
        object.add(name, read(depth + 1, path.property(name)));
      }
      reader.endObject();
      return object;
    }

    private JsonArray array(int depth, SourcePath path) throws IOException {
      reader.beginArray();
      var array = new JsonArray();
      while (reader.hasNext()) array.add(read(depth + 1, path.index(array.size())));
      reader.endArray();
      return array;
    }

    private JsonElement number(SourcePath path) throws IOException {
      String token = reader.nextString();
      if (token.length() > limits.maximumNumberCharacters()) {
        throw fail("json.number_length", "JSON number exceeds its owner's representation limit");
      }
      final BigDecimal value;
      try {
        value = new BigDecimal(token);
      } catch (NumberFormatException invalid) {
        throw fail("json.number_scale", "JSON number exponent is outside the supported range");
      }
      if (Math.abs((long) value.scale()) > limits.maximumAbsoluteScale()) {
        throw fail("json.number_scale", "JSON number exceeds its owner's scale limit");
      }
      scalarText.put(path, token);
      // BigDecimal does not retain negative zero; source tokens remain separately owned.
      if (value.signum() == 0 && token.startsWith("-")) return new JsonPrimitive(-0.0d);
      return new JsonPrimitive(value);
    }

    private String text(String text) throws JsonInputException {
      if (text.length() > limits.maximumStringCharacters()) {
        throw fail("json.string_length", "JSON text exceeds its owner's length limit");
      }
      for (int i = 0; i < text.length(); i++) {
        char value = text.charAt(i);
        if (Character.isHighSurrogate(value)) {
          if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) {
            throw fail("json.unicode", "JSON text contains an unpaired surrogate");
          }
        } else if (Character.isLowSurrogate(value)) {
          throw fail("json.unicode", "JSON text contains an unpaired surrogate");
        }
      }
      return text;
    }

    private JsonInputException fail(String code, String message) {
      return new JsonInputException(code, reader.getPath(), message);
    }
  }

  private JsonInput() {}
}
