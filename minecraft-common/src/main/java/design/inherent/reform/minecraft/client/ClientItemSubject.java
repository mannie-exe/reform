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
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/** Native-codec snapshot of a defensive stack copy; retains no inventory, live stack, or level. */
public final class ClientItemSubject {
  private static final ValueType<ClientItemSubject> TYPE =
      ValueType.of(
          Codec.PASSTHROUGH.comapFlatMap(
              input -> {
                JsonElement value = input.convert(JsonOps.INSTANCE).getValue();
                if (!value.isJsonObject())
                  return DataResult.error(() -> "A native item snapshot must be an object");
                var object = value.getAsJsonObject();
                if (object.size() != 2
                    || !object.has("stack")
                    || !object.get("stack").isJsonObject()
                    || !object.has("registry")
                    || !object.get("registry").isJsonPrimitive()
                    || !object.get("registry").getAsJsonPrimitive().isString())
                  return DataResult.error(
                      () -> "A copied item needs its stack and native registry context");
                var registry =
                    design.inherent.reform.value.ValueTypes.uuid()
                        .codec()
                        .parse(JsonOps.INSTANCE, object.get("registry"));
                return registry.map(
                    id -> new ClientItemSubject(object.get("stack").toString(), id));
              },
              value -> new Dynamic<>(JsonOps.INSTANCE, value.wire())),
          new ValueShape.Custom("reform:native_item_snapshot"),
          SnapshotPolicy.immutable());

  private final String json;
  private final UUID registry;
  private final long bytes;

  private ClientItemSubject(String json, UUID registry) {
    this.json = json;
    this.registry = registry;
    bytes = wire().toString().getBytes(StandardCharsets.UTF_8).length;
  }

  private JsonElement wire() {
    var result = new com.google.gson.JsonObject();
    result.addProperty("registry", registry.toString());
    result.add("stack", JsonParser.parseString(json));
    return result;
  }

  /**
   * Explicit client-stage capture; arbitrary NBT text and inventory capabilities are not inputs.
   */
  public static ClientItemSubject copyOf(ItemStack stack, GraphicLimits limits) {
    ClientSettingsRuntime.requireClientThread();
    var encoded = NativeItemSnapshots.encode(Objects.requireNonNull(stack));
    var result = new ClientItemSubject(encoded.toString(), NativeItemContexts.registry());
    if (result.bytes > Objects.requireNonNull(limits).inputBytes())
      throw new PolicyRefusal(
          "preview.budget", "The copied native item exceeds its input allowance");
    return result;
  }

  public static ValueType<ClientItemSubject> type() {
    return TYPE;
  }

  public long inputBytes() {
    return bytes;
  }

  String json() {
    return json;
  }

  UUID registry() {
    return registry;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ClientItemSubject value
        && json.equals(value.json)
        && registry.equals(value.registry);
  }

  @Override
  public int hashCode() {
    return Objects.hash(json, registry);
  }
}
