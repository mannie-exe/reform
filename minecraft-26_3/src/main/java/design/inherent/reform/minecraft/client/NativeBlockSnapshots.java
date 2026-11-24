package design.inherent.reform.minecraft.client;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.owner.PolicyRefusal;
import net.minecraft.world.level.block.state.BlockState;

/** Actual full native state codec behind the shared immutable name/property wire. */
final class NativeBlockSnapshots {
  static JsonElement encode(BlockState state) {
    var result = BlockState.FULL_CODEC.encodeStart(JsonOps.INSTANCE, state);
    if (result.error().isPresent()) throw invalid();
    var actual = result.result().orElseThrow().getAsJsonObject();
    var wire = new com.google.gson.JsonObject();
    wire.add("Name", actual.get(net.minecraft.world.level.block.state.StateHolder.ID_TAG));
    if (actual.has(net.minecraft.world.level.block.state.StateHolder.PROPERTIES_TAG))
      wire.add(
          "Properties",
          actual.get(net.minecraft.world.level.block.state.StateHolder.PROPERTIES_TAG));
    return wire;
  }

  static DataResult<BlockState> decode(JsonElement wire) {
    var source = wire.getAsJsonObject();
    var actual = new com.google.gson.JsonObject();
    actual.add(net.minecraft.world.level.block.state.StateHolder.ID_TAG, source.get("Name"));
    if (source.has("Properties"))
      actual.add(
          net.minecraft.world.level.block.state.StateHolder.PROPERTIES_TAG,
          source.get("Properties"));
    return BlockState.FULL_CODEC.parse(JsonOps.INSTANCE, actual);
  }

  private static PolicyRefusal invalid() {
    return new PolicyRefusal("preview.block_input", "The native block state cannot be copied");
  }

  private NativeBlockSnapshots() {}
}
