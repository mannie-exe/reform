package design.inherent.reform.minecraft.client;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.owner.PolicyRefusal;
import net.minecraft.world.level.block.state.BlockState;

/** Actual full native state codec behind the shared immutable name/property wire. */
final class NativeBlockSnapshots {
  static JsonElement encode(BlockState state) {
    var result = BlockState.CODEC.encodeStart(JsonOps.INSTANCE, state);
    if (result.error().isPresent()) throw invalid();
    return result.result().orElseThrow();
  }

  static DataResult<BlockState> decode(JsonElement wire) {
    return BlockState.CODEC.parse(JsonOps.INSTANCE, wire);
  }

  private static PolicyRefusal invalid() {
    return new PolicyRefusal("preview.block_input", "The native block state cannot be copied");
  }

  private NativeBlockSnapshots() {}
}
