package example.reform.platform.mixin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import example.reform.platform.PlatformNativeUnloadObservation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two bounded read-only blocked-readiness samples per native map, using public debug observations.
 */
@Mixin(ChunkMap.class)
abstract class NativeChunkUnloadMixin {
  @Unique private boolean reform$sampled;
  @Unique private long reform$firstNanos;
  @Unique private long reform$attempts;

  @Inject(method = "scheduleUnload", at = @At("HEAD"))
  private void observe(long position, ChunkHolder holder, CallbackInfo callback) {
    try {
      reform$observe(position, holder);
    } catch (RuntimeException | AssertionError failed) {
      reform$sampled = true;
      var error = new JsonObject();
      error.addProperty("observerFailure", failed.toString());
      PlatformNativeUnloadObservation.sample(error);
    }
  }

  @Unique
  private void reform$observe(long position, ChunkHolder holder) {
    if (!PlatformNativeUnloadObservation.armed
        || reform$sampled
        || holder.getGenerationRefCount() == 0) return;
    long attempts = ++reform$attempts;
    long now;
    if (reform$firstNanos == 0) {
      now = System.nanoTime();
      reform$firstNanos = now;
    } else {
      if ((attempts & 1023) != 0) return;
      now = System.nanoTime();
      if (now - reform$firstNanos < 1_000_000_000L) return;
      reform$sampled = true;
    }
    var value = new JsonObject();
    value.addProperty("position", position);
    value.addProperty("observedBlockedAttempts", attempts);
    value.addProperty("nanosSinceFirstBlocked", now - reform$firstNanos);
    value.addProperty("generationRefCount", holder.getGenerationRefCount());
    value.addProperty("saveFutureDone", holder.getSaveSyncFuture().isDone());
    value.addProperty(
        "saveFutureExceptional", holder.getSaveSyncFuture().isCompletedExceptionally());
    value.addProperty("saveFutureCancelled", holder.getSaveSyncFuture().isCancelled());
    value.addProperty("readyForSaving", holder.isReadyForSaving());
    value.addProperty("ticketLevel", holder.getTicketLevel());
    value.addProperty("queueLevel", holder.getQueueLevel());
    value.addProperty("latestStatus", String.valueOf(holder.getLatestStatus()));
    var futures = new JsonArray();
    for (var entry : holder.getAllFutures()) {
      var future = new JsonObject();
      future.addProperty("status", entry.getFirst().toString());
      var nativeFuture = entry.getSecond();
      future.addProperty("present", nativeFuture != null);
      if (nativeFuture != null) {
        future.addProperty("done", nativeFuture.isDone());
        future.addProperty("exceptional", nativeFuture.isCompletedExceptionally());
        future.addProperty("cancelled", nativeFuture.isCancelled());
      }
      futures.add(future);
    }
    value.add("futures", futures);
    var stack = new JsonArray();
    StackWalker.getInstance()
        .forEach(
            frame -> {
              if (stack.size() < 20) stack.add(frame.toString());
            });
    value.add("stack", stack);
    PlatformNativeUnloadObservation.sample(value);
  }
}
