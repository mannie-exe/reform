package example.reform.platform;

import java.util.ArrayList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarrationThunk;
import net.minecraft.client.gui.narration.NarrationTrigger;

/** Observe native widget narration structure; this does not exercise spoken output. */
final class NativeNarrationProbe {
  static String read(AbstractWidget widget) {
    var parts = new ArrayList<String>();
    widget.updateNarration(
        new NarrationElementOutput() {
          @Override
          public void add(NarratedElementType type, NarrationThunk<?> value) {
            value.getText(parts::add);
          }

          @Override
          public NarrationElementOutput nest() {
            return this;
          }

          @Override
          public NarrationTrigger narrationTrigger() {
            return NarrationTrigger.KEYBOARD;
          }
        });
    return String.join(" ", parts);
  }

  private NativeNarrationProbe() {}
}
