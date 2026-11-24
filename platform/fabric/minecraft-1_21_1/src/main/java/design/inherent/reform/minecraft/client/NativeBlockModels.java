package design.inherent.reform.minecraft.client;

import design.inherent.reform.owner.PolicyRefusal;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/** Loader-native static layers are captured once, with the same isolated display policy. */
final class NativeBlockModels {
  static void capture(
      BakedModel model,
      BlockState state,
      NativeLoadedBlockAdapter.Quads output,
      int maximumLayers) {
    if (maximumLayers < 1)
      throw new PolicyRefusal(
          "preview.budget", "The native block needs a material-layer allowance");
    var display = net.minecraft.client.renderer.ItemBlockRenderTypes.getRenderType(state, false);
    for (int side = 0; side <= 6; side++)
      output.add(
          display,
          model.getQuads(
              state, side == 6 ? null : Direction.values()[side], RandomSource.create(42)));
  }

  private NativeBlockModels() {}
}
