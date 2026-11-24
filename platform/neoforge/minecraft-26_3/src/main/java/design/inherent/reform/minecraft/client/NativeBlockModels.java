package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/** Actual loader-native isolated model collection and material submission. */
final class NativeBlockModels {
  static void collect(
      BlockStateModel model,
      BlockState state,
      RandomSource random,
      List<BlockStateModelPart> parts) {
    model.collectParts(
        net.minecraft.client.renderer.block.BlockAndTintGetter.EMPTY,
        net.minecraft.core.BlockPos.ZERO,
        state,
        random,
        parts);
  }

  static void submit(
      PoseStack pose,
      SubmitNodeStorage submits,
      List<BlockStateModelPart> parts,
      int[] tints,
      boolean translucent) {
    submits.submitMultiLayerBlockModel(
        pose, parts, translucent, tints, 15728880, OverlayTexture.NO_OVERLAY, 0);
  }

  private NativeBlockModels() {}
}
