package design.inherent.reform.serialization;

import com.mojang.serialization.JsonOps;
import java.util.Set;

/** Strict selection of the library's versioned document envelope, without guessing or migration. */
public final class JsonEnvelope {
  public static SourcePath values(JsonInput.Parsed source, int expectedSchemaVersion)
      throws JsonInputException {
    if (expectedSchemaVersion < 1)
      throw new IllegalArgumentException("Expected a positive schema version");
    var root = source.copyTree();
    if (!root.isJsonObject()
        || !root.getAsJsonObject().keySet().equals(Set.of("schemaVersion", "values")))
      throw new JsonInputException("json.envelope", "$", "Expected schemaVersion and values only");
    var version =
        ExactCodecs.INT.parse(JsonOps.INSTANCE, root.getAsJsonObject().get("schemaVersion"));
    if (version.error().isPresent()
        || version.result().filter(value -> value == expectedSchemaVersion).isEmpty())
      throw new JsonInputException(
          "json.schema", "$.schemaVersion", "Unsupported document schema version");
    return SourcePath.ROOT.property("values");
  }

  private JsonEnvelope() {}
}
