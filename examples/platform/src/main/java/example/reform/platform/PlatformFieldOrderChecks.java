package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.presentation.FormCatalog;
import design.inherent.reform.presentation.FormLayout;
import design.inherent.reform.presentation.FormPresentation;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Rendered composition order is independent of the owner's schema, capture and serialized data. */
final class PlatformFieldOrderChecks {
  private static final List<FieldPath> VISUAL =
      List.of(
          FieldPath.ROOT.field("label"),
          FieldPath.ROOT.field("enabled"),
          FieldPath.ROOT.field("distance"));
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private final BiConsumer<String, Runnable> capture;
  private SettingsScreen<?> form;
  private ReviewScreen<?> review;
  private List<FieldPath> schema;
  private byte[] bytes;
  private int stage;

  PlatformFieldOrderChecks(
      BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    this.capture = capture;
  }

  private byte[] source() throws Exception {
    return Files.readAllBytes(
        client
            .gameDirectory
            .toPath()
            .resolve("config/reform_platform_example/workflow_preferences.json"));
  }

  private void press(Screen screen, Button button) {
    check.accept("field-order action belongs to the active native tree", focus(screen, button));
    click(screen, button);
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        bytes = source();
        var presentation =
            FormPresentation.EMPTY
                .withCatalog(
                    new FormCatalog(
                        List.of(
                            new FormCatalog.Section(
                                FieldPath.ROOT, "example.review-root", "Review fields", ""),
                            new FormCatalog.Section(
                                VISUAL.get(0),
                                "example.custom-label",
                                "Display name",
                                "A retained custom purpose."),
                            new FormCatalog.Section(
                                VISUAL.get(1), "example.custom-enabled", "Feature enabled", ""))))
                .withLayout(
                    new FormLayout(
                        List.of(),
                        List.of(
                            new FormLayout.Group(
                                "review-order",
                                "example.review-order",
                                "Authored order",
                                "",
                                VISUAL))));
        form =
            (SettingsScreen<?>)
                PlatformWorkflowExample.settings()
                    .screen(parent, Component.literal("Field order witness"), presentation);
        NativeScreens.open(client, form);
        stage = 1;
      }
      case 1 -> {
        if (form.editorView().availability() != EditorActor.Availability.READY
            || form.fieldControl(VISUAL.getLast()).isEmpty()) return false;
        // Actor readiness can precede the native form's refreshed action admission.
        if (!button(form, "Inspect…").active) return false;
        schema =
            form.editingActions().fields().fields().stream().map(field -> field.path()).toList();
        check.accept(
            "adversarial visual order differs from unchanged declared schema order",
            !schema.equals(VISUAL) && new HashSet<>(schema).equals(new HashSet<>(VISUAL)));
        check.accept(
            "form exports its actual composed field order without schema mutation",
            form.fieldOrder().equals(VISUAL));
        var actual =
            VISUAL.stream()
                .sorted(
                    Comparator.comparingInt(path -> form.fieldControl(path).orElseThrow().getY()))
                .toList();
        check.accept(
            "actual native control geometry follows authored composition", actual.equals(VISUAL));
        geometry(form);
        press(form, button(form, "Inspect…"));
        review = (ReviewScreen<?>) NativeScreens.current(client);
        stage = 2;
      }
      case 2 -> {
        var captured = review.reviewedInput().orElse(null);
        if (captured == null) return false;
        var captions = List.of("Display name", "Feature enabled", "Distance");
        var indexed =
            widgets(review).stream()
                .filter(Button.class::isInstance)
                .map(button -> button.getMessage().getString())
                .filter(captions::contains)
                .toList();
        check.accept(
            "captured field index follows actual body flow instead of schema order",
            indexed.equals(captions));
        check.accept(
            "captured data and declared field order remain unchanged",
            captured.entries().stream().map(entry -> entry.path()).toList().equals(schema)
                && form.editingActions().fields().fields().stream()
                    .map(field -> field.path())
                    .toList()
                    .equals(schema)
                && Arrays.equals(bytes, source())
                && !form.editorView().dirty());
        try {
          review.withFieldOrder(VISUAL);
          throw new AssertionError("Mounted review accepted replacement order");
        } catch (IllegalStateException expected) {
          check.accept("review metadata cannot be replaced after native mounting", true);
        }
        var invalid =
            new ReviewScreen<>(
                Component.literal("Unopened metadata witness"),
                Component.empty(),
                form.editingActions(),
                Optional.empty(),
                () -> true,
                ignored -> {},
                () -> {});
        try {
          invalid.withFieldOrder(List.of(FieldPath.ROOT.field("not-declared")));
          throw new AssertionError("Unknown review field accepted");
        } catch (IllegalArgumentException expected) {
          check.accept("review rejects undeclared field metadata before opening", true);
        }
        geometry(review);
        stage = 9;
        capture.accept(
            "field-order.png",
            () -> {
              press(review, button(review, "Back"));
              stage = 3;
            });
      }
      case 3 -> {
        if (NativeScreens.current(client) != form) return false;
        check.accept(
            "review return retains the same clean actual owner and presentation order",
            form.fieldOrder().equals(VISUAL)
                && !form.editorView().dirty()
                && Arrays.equals(bytes, source()));
        press(form, button(form, "Done"));
        stage = 4;
      }
      case 4 -> {
        check.accept(
            "order inspection closes its original owner without writing or re-encoding",
            NativeScreens.current(client) == parent
                && form.editorView().availability() == EditorActor.Availability.CLOSED
                && Arrays.equals(bytes, source()));
        stage = 5;
        return true;
      }
      case 5 -> {
        return true;
      }
      case 9 -> {}
      default -> throw new AssertionError("Unknown field-order stage " + stage);
    }
    return false;
  }
}
