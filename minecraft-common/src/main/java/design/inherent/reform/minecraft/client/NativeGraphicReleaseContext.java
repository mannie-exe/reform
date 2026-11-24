package design.inherent.reform.minecraft.client;

/** Actual native teardown fact; a destroyed renderer admits accounting, never another GPU call. */
public record NativeGraphicReleaseContext(boolean rendererDestroyed) {}
