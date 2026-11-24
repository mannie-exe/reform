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
                return DataResult.success(new ClientItemSubject(value.toString()));
              },
              value -> new Dynamic<>(JsonOps.INSTANCE, JsonParser.parseString(value.json))),
          new ValueShape.Custom("reform:native_item_snapshot"),
          SnapshotPolicy.immutable());

  private final String json;
  private final long bytes;

  private ClientItemSubject(String json) {
    this.json = json;
    bytes = json.getBytes(StandardCharsets.UTF_8).length;
  }

  /**
   * Explicit client-stage capture; arbitrary NBT text and inventory capabilities are not inputs.
   */
  public static ClientItemSubject copyOf(ItemStack stack, GraphicLimits limits) {
    ClientSettingsRuntime.requireClientThread();
    var encoded = NativeItemSnapshots.encode(Objects.requireNonNull(stack).copy());
    var result = new ClientItemSubject(encoded.toString());
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

  @Override
  public boolean equals(Object other) {
    return other instanceof ClientItemSubject value && json.equals(value.json);
  }

  @Override
  public int hashCode() {
    return json.hashCode();
  }
}
