package design.inherent.reform.minecraft.client;

/**
 * Register before allocating owned handles; release follows actual uses, even on prepare failure.
 */
@FunctionalInterface
public interface NativeGraphicResource {
  void release(NativeGraphicReleaseContext context);
}
