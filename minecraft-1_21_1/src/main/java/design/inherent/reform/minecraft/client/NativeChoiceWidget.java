package design.inherent.reform.minecraft.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.*;
import net.minecraft.network.chat.Component;

abstract class NativeChoiceWidget extends AbstractWidget {
  NativeChoiceWidget(Component label) {
    super(0, 0, 180, 80, label);
  }

  @Override
  protected final void renderWidget(GuiGraphics graphics, int x, int y, float delta) {
    drawList(new NativeCanvas(graphics));
  }

  protected abstract void drawList(FormCanvas canvas);

  protected abstract boolean listKey(int key);

  protected abstract boolean listClick(double x, double y, int button);

  protected abstract boolean listDrag(double x, double y, int button);

  protected abstract boolean listRelease();

  protected abstract Component listNarration();

  @Override
  public final boolean keyPressed(int key, int nativeCode, int modifiers) {
    return listKey(key);
  }

  @Override
  public final boolean mouseClicked(double x, double y, int button) {
    return listClick(x, y, button);
  }

  @Override
  public final boolean mouseDragged(double x, double y, int button, double dx, double dy) {
    return listDrag(x, y, button);
  }

  @Override
  public final boolean mouseReleased(double x, double y, int button) {
    return listRelease();
  }

  @Override
  protected final void updateWidgetNarration(NarrationElementOutput output) {
    output.add(NarratedElementType.TITLE, listNarration());
    output.add(
        NarratedElementType.USAGE,
        Component.translatableWithFallback(
            "reform.ui.list_usage", "Use arrow keys to browse. Press Enter to choose."));
  }
}
