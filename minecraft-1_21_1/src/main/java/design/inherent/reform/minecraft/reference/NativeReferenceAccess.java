package design.inherent.reform.minecraft.reference;

import java.util.stream.Stream;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/** Native identifier and named-tag shape; captures remain with the admitted owner. */
final class NativeReferenceAccess {
  static String canonical(String value) {
    var id = ResourceLocation.tryParse(value);
    return id == null || id.getPath().isEmpty() ? null : id.toString();
  }

  static String key(ResourceKey<?> key) {
    return key.location().toString();
  }

  static <T> Stream<HolderSet.Named<T>> tags(Registry<T> registry) {
    return registry.getTags().map(com.mojang.datafixers.util.Pair::getSecond);
  }

  private NativeReferenceAccess() {}
}
