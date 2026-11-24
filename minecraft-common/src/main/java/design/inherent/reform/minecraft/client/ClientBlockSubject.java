package design.inherent.reform.minecraft.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.GraphicLimits;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import net.minecraft.world.level.block.state.BlockState;

/** Immutable native state identity/properties, without a position, level, or block entity. */
public final class ClientBlockSubject {
  private static final ValueType<ClientBlockSubject> TYPE =
      ValueType.of(
          Codec.PASSTHROUGH.comapFlatMap(
              input -> {
                JsonElement value = input.convert(JsonOps.INSTANCE).getValue();
                if (!value.isJsonObject())
                  return DataResult.error(() -> "A copied block state must be an object");
                var object = value.getAsJsonObject();
                if (!object.has("Name")
                    || !string(object.get("Name"))
                    || object.keySet().stream()
                        .anyMatch(key -> !key.equals("Name") && !key.equals("Properties")))
                  return DataResult.error(
                      () -> "A copied block state needs its native name and properties");
                if (object.has("Properties")) {
                  var properties = object.get("Properties");
                  if (!properties.isJsonObject()
                      || properties.getAsJsonObject().entrySet().stream()
                          .anyMatch(entry -> !string(entry.getValue())))
                    return DataResult.error(
                        () -> "Copied block properties must be native string values");
                }
                return DataResult.success(new ClientBlockSubject(value.toString()));
              },
              value -> new Dynamic<>(JsonOps.INSTANCE, JsonParser.parseString(value.json))),
          new ValueShape.Custom("reform:native_block_snapshot"),
          SnapshotPolicy.immutable());

  private final String json;
  private final long bytes;

  private ClientBlockSubject(String json) {
    this.json = json;
    bytes = json.getBytes(StandardCharsets.UTF_8).length;
  }

  /** Client-stage native codec capture, not arbitrary block-entity/NBT text. */
  public static ClientBlockSubject copyOf(BlockState state, GraphicLimits limits) {
    ClientSettingsRuntime.requireClientThread();
    var encoded = NativeBlockSnapshots.encode(Objects.requireNonNull(state));
    var subject = new ClientBlockSubject(encoded.toString());
    if (subject.bytes > Objects.requireNonNull(limits).inputBytes())
      throw new PolicyRefusal(
          "preview.budget", "The copied block state exceeds its input allowance");
    return subject;
  }

  public static ValueType<ClientBlockSubject> type() {
    return TYPE;
  }

  public long inputBytes() {
    return bytes;
  }

  BlockState decode() {
    ClientSettingsRuntime.requireClientThread();
    var input = JsonParser.parseString(json);
    var decoded = NativeBlockSnapshots.decode(input);
    if (decoded.error().isPresent()) throw invalid();
    var state = decoded.result().orElseThrow();
    var canonical = NativeBlockSnapshots.encode(state);
    // Some native property codecs tolerate unknown/invalid properties by retaining defaults.
    // A copied snapshot must never silently render another state after that recovery.
    if (!canonical.equals(input)) throw invalid();
    return state;
  }

  private static PolicyRefusal invalid() {
    return new PolicyRefusal(
        "preview.block_input",
        "The copied block state does not match a complete current native state");
  }

  private static boolean string(JsonElement value) {
    return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ClientBlockSubject value && json.equals(value.json);
  }

  @Override
  public int hashCode() {
    return json.hashCode();
  }
}
