package design.inherent.reform.minecraft.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;

/** Detached objects are never inserted, ticked, opened, or given a level or item data. */
final class NativeChestSubjects {
  static ChestBlockEntity create(ClientChestSubject subject) {
    if (subject.kind() == ClientChestSubject.Kind.TRAPPED)
      return new TrappedChestBlockEntity(BlockPos.ZERO, Blocks.TRAPPED_CHEST.defaultBlockState()) {
        @Override
        public float getOpenNess(float partialTicks) {
          return subject.lidProgress();
        }
      };
    return new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState()) {
      @Override
      public float getOpenNess(float partialTicks) {
        return subject.lidProgress();
      }
    };
  }

  private NativeChestSubjects() {}
}
