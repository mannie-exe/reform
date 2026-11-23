package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.presentation.*;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BiConsumer;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Real owned input/chrome pixels, not a test renderer or a second value model. */
final class PlatformThemeChecks implements AutoCloseable {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private final BiConsumer<String, Runnable> capture;
  private final int[] alphas = {0, 3, 128, 255, 3};
  private final List<int[]> pixels = new ArrayList<>();
  private ClientEditor<?> editor;
  private RawReference reference;
  private EditBox measuredInput;
  private Screen measuredScreen;
  private int stage;
  private boolean awaiting;
  private byte[] source;

  PlatformThemeChecks(BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    this.capture = capture;
  }

  private java.nio.file.Path sourcePath() {
    return client
        .gameDirectory
        .toPath()
        .resolve("config/reform_platform_example/workflow_preferences.json");
  }

  boolean tick() throws Exception {
    if (awaiting) return false;
    if (stage == alphas.length) {
      int faint = 0, half = 0, opaque = 0, raw = 0;
      for (int i = 0; i < pixels.getFirst().length; i++)
        for (int shift : new int[] {16, 8, 0}) {
          int base = pixels.get(0)[i] >>> shift & 255;
          faint = Math.max(faint, Math.abs((pixels.get(1)[i] >>> shift & 255) - base));
          half = Math.max(half, Math.abs((pixels.get(2)[i] >>> shift & 255) - base));
          opaque = Math.max(opaque, Math.abs((pixels.get(3)[i] >>> shift & 255) - base));
          raw = Math.max(raw, Math.abs((pixels.get(4)[i] >>> shift & 255) - base));
        }
      check.accept(
          "explicit alpha3 follows native glyph clipping without opaque normalization: max="
              + faint,
          faint <= 4);
      check.accept(
          "half-opacity glyphs remain visible and distinct from opaque glyphs: max=" + half,
          half > 4 && half < opaque);
      check.accept(
          "opaque native input has visible independent pixel contrast: max=" + opaque, opaque > 40);
      check.accept(
          "raw native alpha3 control discriminates this game's normalization policy: max=" + raw,
          NativeClientProbe.normalizesSmallTextAlpha() ? raw == opaque : raw == faint);
      check.accept(
          "theme-only native input scenes never modify the source",
          Arrays.equals(source, Files.readAllBytes(sourcePath())));
      stage++;
      return true;
    }
    if (stage > alphas.length) return true;
    boolean rawNative = stage == alphas.length - 1;
    if (rawNative && reference == null) {
      reference = new RawReference();
      reference.withTheme(ClientTheme.standard());
      NativeScreens.open(client, reference);
      return false;
    }
    if (!rawNative && editor == null) {
      if (source == null) source = Files.readAllBytes(sourcePath());
      editor =
          PlatformWorkflowExample.settings()
              .form(Component.literal("Native input theme"))
              .open(parent);
      var theme =
          ClientTheme.standard()
              .child(
                  ThemePatch.empty().set(ThemeValues.Role.TEXT, alphas[stage] << 24 | 0x00ffffff))
              .withStyle(
                  ClientTheme.TextRole.VALUE,
                  net.minecraft.network.chat.Style.EMPTY.withBold(true));
      ((NativeFormScreen) editor.screen()).withTheme(theme);
      NativeScreens.open(client, editor.screen());
      return false;
    }
    if (!rawNative && editor.view().availability() != EditorActor.Availability.READY) return false;
    var path = FieldPath.ROOT.field("label");
    if (!rawNative && !editor.form().focusField(path)) return false;
    var input =
        rawNative ? reference.input : (EditBox) editor.form().fieldControl(path).orElseThrow();
    var screen = rawNative ? reference : editor.screen();
    measuredInput = input;
    measuredScreen = screen;
    if (!rawNative)
      check.accept(
          "native input caret measurement uses the same authored bold style: " + alphas[stage],
          input.getScreenX(3)
              == input.getX()
                  + client.font.width(
                      Component.literal(input.getValue().substring(0, 3))
                          .withStyle(net.minecraft.network.chat.Style.EMPTY.withBold(true))));
    if (!rawNative) {
      int prefix =
          client.font.width(
              Component.literal(input.getValue().substring(0, 3))
                  .withStyle(net.minecraft.network.chat.Style.EMPTY.withBold(true)));
      double mouseX = input.getX() + 4 + prefix;
      double mouseY = input.getY() + input.getHeight() / 2.0;
      check.accept(
          "native pointer enters the themed input: " + alphas[stage],
          NativeClientProbe.pointerDown(screen, mouseX, mouseY));
      NativeClientProbe.pointerUp(screen, mouseX, mouseY);
      check.accept(
          "native pointer caret follows actual bold glyph width: " + alphas[stage],
          input.getCursorPosition() == 3);
      check.accept(
          "native left arrow remains consumed in a themed input: " + alphas[stage],
          NativeClientProbe.key(screen, "key.keyboard.left", true));
      NativeClientProbe.key(screen, "key.keyboard.left", false);
      check.accept(
          "native caret navigation retains source text and owner draft: " + alphas[stage],
          input.getCursorPosition() == 2 && input.getValue().equals("Example") && !editor.dirty());
    }
    screen.setFocused(null);
    input.setFocused(false);
    check.accept(
        "theme preserves actual source input and selection: " + alphas[stage],
        input.getValue().equals("Example")
            && input.getCursorPosition() >= 0
            && (rawNative || !editor.dirty()));
    if (rawNative)
      check.accept(
          "raw native text reference has no library control binding",
          ((NativeControlStyle) input).reform$theme() == null
              && !((NativeControlStyle) input).reform$inputArgb());
    String name =
        rawNative
            ? "theme-input-native-alpha-3.png"
            : "theme-input-alpha-" + alphas[stage] + ".png";
    awaiting = true;
    capture.accept(
        name,
        () -> {
          try {
            var image =
                ImageIO.read(client.gameDirectory.toPath().resolve("platform-" + name).toFile());
            double sx = image.getWidth() / (double) measuredScreen.width;
            double sy = image.getHeight() / (double) measuredScreen.height;
            int x = (int) Math.ceil((input.getX() + 4) * sx),
                y = (int) Math.ceil((input.getY() + 4) * sy);
            int right = (int) Math.floor((input.getX() + input.getWidth() - 4) * sx);
            int bottom = (int) Math.floor((input.getY() + input.getHeight() - 4) * sy);
            pixels.add(image.getRGB(x, y, right - x, bottom - y, null, 0, right - x));
            check.accept(
                "native input theme capture is contained and comparable: " + alphas[stage],
                pixels.getLast().length > 0 && pixels.getLast().length == pixels.getFirst().length);
            if (rawNative) {
              check.accept(
                  "raw reference stays unbound through actual native drawing",
                  ((NativeControlStyle) measuredInput).reform$theme() == null);
              reference = null;
            } else {
              editor.close();
              editor = null;
            }
            NativeScreens.open(client, parent);
            stage++;
            awaiting = false;
          } catch (Exception failed) {
            throw new AssertionError(failed);
          }
        });
    return false;
  }

  private final class RawReference extends NativeFormScreen {
    EditBox input;

    RawReference() {
      super(Component.literal("Native alpha reference"));
    }

    @Override
    protected void init() {
      input = NativeClientProbe.rawTextAlphaInput(font, (width - 160) / 2, height / 2);
      addRenderableWidget(input);
    }
  }

  @Override
  public void close() {
    if (editor != null) editor.close();
  }
}
