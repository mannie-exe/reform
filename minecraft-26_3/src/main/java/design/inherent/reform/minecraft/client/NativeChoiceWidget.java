package design.inherent.reform.minecraft.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.*;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

abstract class NativeChoiceWidget extends AbstractWidget {
  NativeChoiceWidget(Component label) {
    super(0, 0, 180, 80, label);
  }

  @Override
  protected final void extractWidgetRenderState(
      GuiGraphicsExtractor graphics, int x, int y, float delta) {
    drawList(new NativeCanvas(graphics));
  }

  protected abstract void drawList(FormCanvas canvas);

  protected abstract boolean listKey(int key);

  protected abstract boolean listClick(double x, double y, int button);

  protected abstract boolean listDrag(double x, double y, int button);

  protected abstract boolean listRelease();

  protected abstract Component listNarration();

  @Override
  public final boolean keyPressed(KeyEvent event) {
    return listKey(event.key());
  }

  @Override
  public final boolean mouseClicked(MouseButtonEvent event, boolean twice) {
    return listClick(event.x(), event.y(), event.button());
  }

  @Override
  public final boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
    return listDrag(event.x(), event.y(), event.button());
  }

  @Override
  public final boolean mouseReleased(MouseButtonEvent event) {
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
