package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.importing.ImportInput;
import design.inherent.reform.importing.ImportSource;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.minecraft.client.ImportScreen;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.minecraft.client.SettingsScreen;
import design.inherent.reform.owner.EditorActor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

/** Actual native generated import controls; no access to library-private editor state. */
final class PlatformImportChecks implements PlatformFormProbe.Scene {
  static java.util.concurrent.CompletionStage<JsonObject> run(
      ClientRegistration registration, boolean reopen) {
    return PlatformFormProbe.run(
        registration,
        "IMPORT",
        driver -> new PlatformImportChecks(reopen, driver::progress, driver::capture));
  }

  public String diagnostic() {
    return "import stage="
        + stage
        + ", screen="
        + NativeScreens.current(client).getClass().getSimpleName();
  }

  private static final String VALUE =
      "{\"count\":3,\"label\":\"Imported café\",\"roles\":[\"alpha\",\"beta\"],\"note\":[]}";
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final boolean reopen;
  private final JsonObject report = new JsonObject();
  private final JsonArray checks = new JsonArray();
  private final Path directory =
      client.gameDirectory.toPath().toAbsolutePath().resolve("import-fixture");
  private final Path saved =
      client.gameDirectory.toPath().resolve("config/reform_platform_example/imports.json");
  private final CountDownLatch readEntered = new CountDownLatch(1),
      releaseRead = new CountDownLatch(1);
  private final CompletableFuture<Void> files;
  private SettingsScreen<PlatformImportExample.Profile> form;
  private Button retired;
  private String original;
  private int stage;

  PlatformImportChecks(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
    report.addProperty("status", "RUNNING");
    report.addProperty("nativeFileDialogExercised", false);
    report.add("checks", checks);
    files =
        CompletableFuture.runAsync(
            () -> {
              try {
                Files.createDirectories(directory);
                Files.writeString(directory.resolve("Café source.json"), VALUE);
                Files.writeString(directory.resolve("malformed.json"), "{broken");
                Files.writeString(
                    directory.resolve("wrong-version.json"),
                    "{\"schemaVersion\":1,\"values\":" + VALUE + "}");
                Files.writeString(
                    directory.resolve("repairable.json"),
                    "{\"schemaVersion\":2,\"values\":"
                        + VALUE.replace("\"count\":3", "\"count\":99")
                        + "}");
              } catch (java.io.IOException failed) {
                throw new java.io.UncheckedIOException(failed);
              }
            });
  }

  public JsonObject report() {
    return report;
  }

  public void close() {
    releaseRead.countDown();
  }

  private void check(String label, boolean value) {
    if (!value) throw new AssertionError(label);
    checks.add(label);
  }

  private void step(String label) {
    stage++;
    progress.accept(label);
  }

  private void press(Button button) {
    check(
        "import control participates in native focus",
        focus(NativeScreens.current(client), button));
    click(NativeScreens.current(client), button);
  }

  private boolean ready() {
    return NativeScreens.current(client) == form
        && form.editorView().availability() == EditorActor.Availability.READY
        && form.editorView().snapshot().isPresent()
        && button(form, "Done").active;
  }

  private ImportScreen<?> popup() {
    return (ImportScreen<?>) NativeScreens.current(client);
  }

  private String raw(String field) {
    return ((DraftNode.Scalar)
            form.editorView().snapshot().orElseThrow().draft().at(FieldPath.ROOT.field(field)))
        .raw();
  }

  private void file(String name) {
    popup().onFilesDrop(List.of(directory.resolve(name)));
  }

  private void preview() {
    press(button(NativeScreens.current(client), "Preview import"));
  }

  private void open() {
    press(ownerAction(form, "Import…"));
  }

  private boolean previewFinished() {
    return NativeScreens.current(client) instanceof ImportScreen<?>
        && button(NativeScreens.current(client), "Preview import").active;
  }

  private void expected() {
    var expected =
        new PlatformImportExample.Profile(
            4, "Imported café", List.of("alpha", "beta"), Optional.empty());
    check(
        "saved imported profile survives actual owner publication",
        PlatformImportExample.settings()
            .status()
            .effective()
            .orElseThrow()
            .value()
            .orElseThrow()
            .equals(expected));
    check(
        "saved imported draft is clean and valid",
        !form.editorView().dirty() && form.editorView().validation().valid());
  }

  @SuppressWarnings("unchecked")
  public boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (!files.isDone()) return false;
        files.join();
        form = (SettingsScreen<PlatformImportExample.Profile>) PlatformImportExample.screen(parent);
        NativeScreens.open(client, form);
        step("public generated import form opened");
      }
      case 1 -> {
        if (!ready()) return false;
        if (reopen) {
          expected();
          form.onClose();
          check(
              "second-JVM imported profile closes cleanly",
              NativeScreens.current(client) == parent);
          report.addProperty("status", "PASS");
          return true;
        }
        original = Files.readString(saved);
        replace(
            form,
            (EditBox) form.fieldControl(FieldPath.ROOT.field("label")).orElseThrow(),
            "Earlier work");
        step("prior native draft work authored");
      }
      case 2 -> {
        if (!ready() || !raw("label").equals("Earlier work")) return false;
        open();
        check(
            "generated Import opens native import review",
            NativeScreens.current(client) instanceof ImportScreen<?>);
        file("Café source.json");
        preview();
        step("native dropped value JSON preview requested");
      }
      case 3 -> {
        if (!previewFinished() || popup().reviewedInput().isEmpty()) return false;
        var input = popup().reviewedInput().orElseThrow();
        check(
            "native preview is source/target identified",
            input.target().document().equals(PlatformImportExample.DEFINITION.id())
                && input.source().label().equals("Café source.json")
                && input.sourceSchemaVersion().isEmpty());
        check(
            "native preview keeps actual semantic changes",
            input.stageable()
                && input.comparisonAvailable()
                && input.changes().stream()
                    .anyMatch(
                        change ->
                            change.path().equals(FieldPath.ROOT.field("label"))
                                && change.beforeJson().equals("\"Earlier work\"")));
        check(
            "native preview preserves prior work and saved source",
            raw("label").equals("Earlier work") && Files.readString(saved).equals(original));
        retired = button(NativeScreens.current(client), "Stage draft");
        geometry(NativeScreens.current(client));
        capture.accept(
            "form-import-review.png",
            () -> press(button(NativeScreens.current(client), "Stage draft")));
        step("reviewed native import staged explicitly");
      }
      case 4 -> {
        if (!ready() || !raw("count").equals("3")) return false;
        check(
            "staging does not save or publish",
            Files.readString(saved).equals(original)
                && PlatformImportExample.settings()
                        .status()
                        .effective()
                        .orElseThrow()
                        .value()
                        .orElseThrow()
                        .count()
                    == 1);
        long version = form.editorView().snapshot().orElseThrow().version();
        NativeClientProbe.press(retired);
        check(
            "retired import action cannot replay",
            form.editorView().snapshot().orElseThrow().version() == version);
        press(ownerAction(form, "Undo"));
        step("native whole import undo requested");
      }
      case 5 -> {
        if (!ready() || !raw("count").equals("1")) return false;
        check("one native Undo restores earlier work", raw("label").equals("Earlier work"));
        press(ownerAction(form, "Redo"));
        step("native whole import redo requested");
      }
      case 6 -> {
        if (!ready() || !raw("count").equals("3")) return false;
        check("native Redo restores captured input", raw("label").equals("Imported café"));
        open();
        file("malformed.json");
        preview();
        step("malformed native import requested");
      }
      case 7 -> {
        if (!previewFinished()) return false;
        check(
            "malformed native source cannot stage",
            popup().reviewedInput().isEmpty()
                && !button(NativeScreens.current(client), "Stage draft").active
                && raw("count").equals("3"));
        file("wrong-version.json");
        var mode =
            widgets(NativeScreens.current(client)).stream()
                .filter(CycleButton.class::isInstance)
                .findFirst()
                .orElseThrow();
        check("native format control focuses", focus(NativeScreens.current(client), mode));
        check(
            "native keyboard selects explicit versioned format",
            NativeClientProbe.key(NativeScreens.current(client), "key.keyboard.space", true));
        preview();
        step("wrong native schema refused under explicit format");
      }
      case 8 -> {
        if (!previewFinished()) return false;
        check(
            "wrong native schema does not replace draft",
            popup().reviewedInput().isEmpty() && raw("count").equals("3"));
        file("repairable.json");
        preview();
        step("repairable native schema input requested");
      }
      case 9 -> {
        if (!previewFinished() || popup().reviewedInput().isEmpty()) return false;
        var input = popup().reviewedInput().orElseThrow();
        check(
            "native invalid draft has explicit diagnostics and policy",
            input.stageable()
                && !input.importedValidation().valid()
                && !input.comparisonAvailable()
                && input.sourceSchemaVersion().orElseThrow() == 2);
        client.options.guiScale().set(3);
        NativeClientProbe.resize(client, 480, 360);
        NativeClientProbe.reflow(client);
        step("native import resized for narrow review");
      }
      case 10 -> {
        if (NativeScreens.current(client).width > 340) return false;
        var focused = NativeScreens.current(client).getFocused();
        report.addProperty(
            "narrowFocusedContainer",
            focused == null ? "none" : focused.getClass().getSimpleName());
        check(
            "narrow reflow retains read-only review focus instead of a source control",
            focused
                    instanceof
                    net.minecraft.client.gui.components.events.ContainerEventHandler container
                && container.getFocused() == null);
        geometry(NativeScreens.current(client));
        report.addProperty("narrowWidth", NativeScreens.current(client).width);
        report.addProperty("narrowHeight", NativeScreens.current(client).height);
        capture.accept(
            "form-import-repairable.png",
            () -> press(button(NativeScreens.current(client), "Stage draft")));
        step("repairable import staged from narrow view");
      }
      case 11 -> {
        if (!ready() || !raw("count").equals("99")) return false;
        check(
            "invalid native import remains repairable without write",
            !form.editorView().validation().valid()
                && !button(form, "Save and apply").active
                && Files.readString(saved).equals(original));
        geometry(form);
        check(
            "invalid imported field can be revealed from the retained More pane",
            form.focusField(FieldPath.ROOT.field("count")));
        replace(
            form, (EditBox) form.fieldControl(FieldPath.ROOT.field("count")).orElseThrow(), "4");
        step("invalid imported field repaired through native input");
      }
      case 12 -> {
        if (!ready() || !raw("count").equals("4") || !form.editorView().validation().valid())
          return false;
        check(
            "native repair keeps source unchanged before explicit Save",
            Files.readString(saved).equals(original));
        press(button(form, "Save and apply"));
        step("native imported draft saved explicitly");
      }
      case 13 -> {
        if (!ready() || form.editorView().dirty()) return false;
        expected();
        check(
            "read-only source file remains unchanged",
            Files.readString(directory.resolve("Café source.json")).equals(VALUE));
        open();
        popup()
            .selectSource(
                new ImportSource() {
                  @Override
                  public DocumentId id() {
                    return DocumentId.parse("example:held_import");
                  }

                  @Override
                  public String label() {
                    return "Held source";
                  }

                  @Override
                  public int maximumBytes() {
                    return 8192;
                  }

                  @Override
                  public void requireCurrent() {
                    if (!client.isSameThread())
                      throw new AssertionError("Source authority off native owner");
                  }

                  @Override
                  public Capture capture() {
                    if (!client.isSameThread())
                      throw new AssertionError("Source capture off native owner");
                    return () -> {
                      if (client.isSameThread())
                        throw new AssertionError("Source read blocked the native thread");
                      readEntered.countDown();
                      try {
                        if (!releaseRead.await(30, TimeUnit.SECONDS))
                          throw new java.io.IOException("Fixture read hold timed out");
                      } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException(interrupted);
                      }
                      return new ImportInput(
                          "Held source", VALUE.getBytes(StandardCharsets.UTF_8), 8192);
                    };
                  }
                });
        preview();
        step("native owner remains responsive during held import read");
      }
      case 14 -> {
        if (readEntered.getCount() != 0) return false;
        press(button(NativeScreens.current(client), "Cancel operation"));
        releaseRead.countDown();
        step("native import cancellation requested and reader released");
      }
      case 15 -> {
        if (!previewFinished()) return false;
        check(
            "cancelled native import cannot stage",
            popup().reviewedInput().isEmpty()
                && !button(NativeScreens.current(client), "Stage draft").active);
        press(button(NativeScreens.current(client), "Back"));
        step("native import Back retains the same editor");
      }
      case 16 -> {
        if (!ready()) return false;
        expected();
        press(button(form, "Done"));
        check(
            "saved native import returns to original parent",
            NativeScreens.current(client) == parent);
        report.addProperty("status", "PASS");
        return true;
      }
      default -> throw new AssertionError("Unexpected native import stage " + stage);
    }
    return false;
  }
}
