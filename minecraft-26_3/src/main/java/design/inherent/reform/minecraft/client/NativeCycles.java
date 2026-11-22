package design.inherent.reform.minecraft.client;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;

/** Construct the actual native cycle control with an explicit current draft value. */
public final class NativeCycles {
  public static <T> CycleButton.Builder<T> builder(
      Function<T, Component> label, T initial, Collection<T> values) {
    return CycleButton.builder(label, initial).withValues(values);
  }

  public static <T> CycleButton.Builder<T> builder(
      Function<T, Component> label, T initial, T[] values) {
    return builder(label, initial, List.of(values));
  }

  private NativeCycles() {}
}
