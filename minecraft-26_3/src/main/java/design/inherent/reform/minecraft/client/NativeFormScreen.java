package design.inherent.reform.minecraft.client;

import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

/** Native extraction boundary; forms use the game's retained GUI pipeline. */
public abstract class NativeFormScreen extends Screen {
  private ClientTheme theme = ClientTheme.standard();
  private boolean themeBound, parentTheme;

  private record PointerDispatch(MouseButtonEvent event, boolean doubleClick) {}

  private PointerDispatch pointer;
  private KeyEvent keyboard;

  protected NativeFormScreen(Component title) {
    super(title);
  }

  protected NativeFormScreen(Component title, Screen parent) {
    super(title);
    if (parent instanceof NativeFormScreen form) {
      theme = form.theme();
      parentTheme = true;
    }
  }

  public final ClientTheme theme() {
    return theme;
  }

  private boolean themeMounted() {
    return ((design.inherent.reform.minecraft.mixin.ScreenMountStateAccessor) this)
            .reform$initialized()
        || NativeScreens.current(net.minecraft.client.Minecraft.getInstance()) == this;
  }

  public final NativeFormScreen withTheme(ClientTheme theme) {
    if (themeMounted()) throw new IllegalStateException("Declare a theme before native mounting");
    this.theme = java.util.Objects.requireNonNull(theme);
    themeBound = true;
    return this;
  }

  final void inheritTheme(ClientTheme theme) {
    if (!themeBound && !themeMounted()) withTheme(theme);
  }

  final void inheritFallbackTheme(ClientTheme theme) {
    if (!themeBound && !parentTheme && !themeMounted()) {
      this.theme = theme;
      parentTheme = true;
    }
  }

  @Override
  public final void resize(int width, int height) {
    beforeResize();
    super.resize(width, height);
  }

  protected void beforeResize() {}

  @Override
  public final void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
    render(new NativeCanvas(graphics), x, y, delta);
  }

  public void render(FormCanvas canvas, int x, int y, float delta) {
    super.extractRenderState(((NativeCanvas) canvas).graphics(), x, y, delta);
  }

  protected void renderBackground(FormCanvas canvas, int x, int y, float delta) {
    super.extractBackground(((NativeCanvas) canvas).graphics(), x, y, delta);
  }

  @Override
  public final boolean keyPressed(KeyEvent event) {
    var previous = keyboard;
    keyboard = event;
    try {
      return keyPressed(event.key(), event.keycode(), event.modifiers());
    } finally {
      keyboard = previous;
    }
  }

  protected final boolean hasControlDown() {
    return keyboard != null && keyboard.hasControlDownWithQuirk();
  }

  protected final boolean hasShiftDown() {
    return keyboard != null && keyboard.hasShiftDown();
  }

  public boolean keyPressed(int key, int nativeCode, int modifiers) {
    return super.keyPressed(new KeyEvent(key, nativeCode, modifiers));
  }

  @Override
  public final boolean keyReleased(KeyEvent event) {
    return keyReleased(event.key(), event.keycode(), event.modifiers());
  }

  public boolean keyReleased(int key, int nativeCode, int modifiers) {
    return super.keyReleased(new KeyEvent(key, nativeCode, modifiers));
  }

  @Override
  public final boolean charTyped(CharacterEvent event) {
    return charTyped(event.codepoint(), 0);
  }

  public boolean charTyped(int codepoint, int modifiers) {
    return Character.isValidCodePoint(codepoint) && super.charTyped(new CharacterEvent(codepoint));
  }

  @Override
  public final boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    return dispatch(event, doubleClick, () -> mouseClicked(event.x(), event.y(), event.button()));
  }

  public boolean mouseClicked(double x, double y, int button) {
    var event = pointer(x, y, button);
    return super.mouseClicked(
        event, pointer != null && pointer.event() == event && pointer.doubleClick());
  }

  @Override
  public final boolean mouseReleased(MouseButtonEvent event) {
    return dispatch(event, false, () -> mouseReleased(event.x(), event.y(), event.button()));
  }

  public boolean mouseReleased(double x, double y, int button) {
    return super.mouseReleased(pointer(x, y, button));
  }

  @Override
  public final boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
    return dispatch(event, false, () -> mouseDragged(event.x(), event.y(), event.button(), dx, dy));
  }

  public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
    return super.mouseDragged(pointer(x, y, button), dx, dy);
  }

  private boolean dispatch(MouseButtonEvent event, boolean doubleClick, BooleanSupplier action) {
    var previous = pointer;
    pointer = new PointerDispatch(event, doubleClick);
    try {
      return action.getAsBoolean();
    } finally {
      pointer = previous;
    }
  }

  private MouseButtonEvent pointer(double x, double y, int button) {
    if (pointer != null) {
      var event = pointer.event();
      if (event.x() == x && event.y() == y && event.button() == button) return event;
    }
    return new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
  }

  @Override
  public void setFocused(GuiEventListener listener) {
    if (listener != null && !children().contains(listener)) return;
    if (getFocused() != listener) super.setFocused(listener);
  }
}
