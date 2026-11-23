package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.ThemePatch;
import design.inherent.reform.presentation.ThemeScope;
import design.inherent.reform.presentation.ThemeValues;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** Resolved immutable native text and geometry binding. No ambient theme or persisted settings. */
public record ClientTheme(ThemeScope scope, Map<TextRole, Style> styles) {
  public enum TextRole {
    TITLE(ThemeValues.Role.TEXT),
    HEADING(ThemeValues.Role.TEXT_SUPPORTING),
    LABEL(ThemeValues.Role.TEXT),
    VALUE(ThemeValues.Role.TEXT),
    SUPPORT(ThemeValues.Role.TEXT_SUPPORTING),
    ACTIVE(ThemeValues.Role.TEXT_ACTIVE),
    ERROR(ThemeValues.Role.TEXT_ERROR),
    LINK(ThemeValues.Role.TEXT_ACTIVE);

    private final ThemeValues.Role color;

    TextRole(ThemeValues.Role color) {
      this.color = color;
    }
  }

  private static final ClientTheme STANDARD =
      new ClientTheme(ThemeScope.root(ThemePatch.empty()), Map.of());

  public ClientTheme {
    Objects.requireNonNull(scope);
    styles = Map.copyOf(styles);
  }

  public static ClientTheme standard() {
    return STANDARD;
  }

  public ThemeValues values() {
    return scope.values();
  }

  public ClientTheme child(ThemePatch patch) {
    var next = scope.child(patch);
    return next == scope ? this : new ClientTheme(next, styles);
  }

  public ClientTheme componentDefaults(ThemePatch defaults) {
    var next = scope.componentDefaults(defaults);
    return next == scope ? this : new ClientTheme(next, styles);
  }

  public ClientTheme withStyle(TextRole role, Style style) {
    var next = new EnumMap<TextRole, Style>(TextRole.class);
    next.putAll(styles);
    next.put(Objects.requireNonNull(role), Objects.requireNonNull(style));
    return new ClientTheme(scope, next);
  }

  /** Existing component formatting, including a local Unicode font, takes precedence. */
  public Component text(TextRole role, Component source) {
    return source
        .copy()
        .withStyle(source.getStyle().applyTo(styles.getOrDefault(role, Style.EMPTY)));
  }

  /** Native input measurement and rendering share the same role style. */
  public Style inputStyle() {
    return styles.getOrDefault(TextRole.VALUE, Style.EMPTY);
  }

  public net.minecraft.util.FormattedCharSequence inputText(
      net.minecraft.util.FormattedCharSequence source) {
    var fallback = inputStyle();
    if (fallback.equals(Style.EMPTY)) return source;
    return sink ->
        source.accept(
            (index, style, codepoint) -> sink.accept(index, style.applyTo(fallback), codepoint));
  }

  public int color(TextRole role) {
    return values().value(role.color);
  }

  public boolean explicitColor(TextRole role) {
    return scope.bound().contains(role.color);
  }

  public void draw(
      FormCanvas canvas, Font font, TextRole role, Component source, int x, int y, boolean shadow) {
    canvas.drawArgb(font, text(role, source).getVisualOrderText(), x, y, color(role), shadow);
  }

  public void wrap(
      FormCanvas canvas, Font font, TextRole role, Component source, int x, int y, int width) {
    var lines = font.split(text(role, source), Math.max(1, width));
    for (int i = 0; i < lines.size(); i++)
      canvas.drawArgb(font, lines.get(i), x, y + i * font.lineHeight, color(role), true);
  }

  public Button button(Component label, Button.OnPress action, int x, int y, int width) {
    return buttonBuilder(label, action)
        .bounds(x, y, width, values().value(ThemeValues.Role.CONTROL_HEIGHT))
        .build();
  }

  public ButtonOffer buttonBuilder(Component label, Button.OnPress action) {
    return new ButtonOffer(this, label, action);
  }

  /** Construction metadata only; the resulting control retains Minecraft's behavior. */
  public static final class ButtonOffer {
    private final ClientTheme theme;
    private final Component label;
    private final Button.OnPress action;
    private int x, y, width = 150, height;
    private Button.CreateNarration narration;
    private net.minecraft.client.gui.components.Tooltip tooltip;

    ButtonOffer(ClientTheme theme, Component label, Button.OnPress action) {
      this.theme = theme;
      this.label = label;
      this.action = action;
      height = theme.values().value(ThemeValues.Role.CONTROL_HEIGHT);
    }

    public ButtonOffer bounds(int x, int y, int width, int height) {
      this.x = x;
      this.y = y;
      this.width = width;
      this.height = Math.max(height, theme.values().value(ThemeValues.Role.CONTROL_HEIGHT));
      return this;
    }

    public ButtonOffer pos(int x, int y) {
      this.x = x;
      this.y = y;
      return this;
    }

    public ButtonOffer size(int width, int height) {
      return bounds(x, y, width, height);
    }

    public ButtonOffer width(int width) {
      this.width = width;
      return this;
    }

    public ButtonOffer createNarration(Button.CreateNarration narration) {
      this.narration = narration;
      return this;
    }

    public ButtonOffer tooltip(net.minecraft.client.gui.components.Tooltip tooltip) {
      this.tooltip = tooltip;
      return this;
    }

    public Button build() {
      var builder =
          Button.builder(theme.text(TextRole.VALUE, label), action).bounds(x, y, width, height);
      if (narration != null) builder.createNarration(narration);
      if (tooltip != null) builder.tooltip(tooltip);
      return ClientControls.theme(builder.build(), theme);
    }
  }
}
