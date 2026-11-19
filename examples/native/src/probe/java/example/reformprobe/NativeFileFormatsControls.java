package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.neoforge.client.ClientFileSettings;
import design.inherent.reform.neoforge.client.SettingsScreen;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import example.reform.FileFormatsExample;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Public file factories, actual legacy representations and explicit malformed-source recovery. */
final class NativeFileFormatsControls {
  private static final String PROPERTIES =
      "# preserved author comment\r\nsize=6\r\ntitle=before\r\n";
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = client.screen;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final boolean reopen;
  private final JsonArray checks = new JsonArray();
  private SettingsScreen<?> form;
  private FileState noOp, repaired;
  private int stage;
  private boolean complete;

  private record FileState(String text, FileTime modified, String key) {}

  NativeFileFormatsControls(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
  }

  private Path path(String name) {
    return client.gameDirectory.toPath().resolve("config/legacy-tools").resolve(name);
  }

  private FileState file(String name) throws IOException {
    var p = path(name);
    var info = Files.readAttributes(p, BasicFileAttributes.class);
    if (info.fileKey() == null)
      throw new IllegalStateException("Fixture filesystem has no file identity");
    return new FileState(Files.readString(p), info.lastModifiedTime(), info.fileKey().toString());
  }

  private void check(String label, boolean value) {
    if (!value) throw new AssertionError(label);
    checks.add(label);
  }

  private void next(String label) {
    stage++;
    progress.accept(label);
  }

  private boolean ready() {
    return client.screen == form
        && form.editorView().availability() == EditorActor.Availability.READY
        && button(form, "Done").active;
  }

  private void open(ClientFileSettings<?> source, String name) {
    form = (SettingsScreen<?>) source.screen(parent, Component.literal(name));
    client.setScreen(form);
  }

  private void edit(String field, String value) {
    var path = FieldPath.ROOT.field(field);
    check("public generated field can receive focus", form.focusField(path));
    replace(form, (EditBox) form.fieldControl(path).orElseThrow(), value);
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (List.of(
                FileFormatsExample.properties(),
                FileFormatsExample.bare(),
                FileFormatsExample.custom())
            .stream()
            .anyMatch(value -> value.status().lifecycle() != ClientFileSettings.Lifecycle.READY))
          return false;
        check(
            "file factories preserve already validated definition defaults",
            FileFormatsExample.DEFAULT_CALLS.get() == FileFormatsExample.VALIDATED_DEFAULTS);
        check(
            "existing names gain no new JSON envelope file",
            !Files.exists(
                client
                    .gameDirectory
                    .toPath()
                    .resolve("config/reform_native_example/legacy_choices.json")));
        if (reopen) {
          check(
              "fresh client publishes all actual saved formats",
              FileFormatsExample.properties()
                      .runtime()
                      .orElseThrow()
                      .view()
                      .value()
                      .orElseThrow()
                      .equals(new FileFormatsExample.Choices(true, 9, "before"))
                  && FileFormatsExample.bare()
                          .runtime()
                          .orElseThrow()
                          .view()
                          .value()
                          .orElseThrow()
                          .count()
                      == 10
                  && FileFormatsExample.custom()
                          .runtime()
                          .orElseThrow()
                          .view()
                          .value()
                          .orElseThrow()
                          .count()
                      == 11
                  && FileFormatsExample.broken().status().lifecycle()
                      == ClientFileSettings.Lifecycle.READY
                  && FileFormatsExample.broken()
                          .runtime()
                          .orElseThrow()
                          .view()
                          .value()
                          .orElseThrow()
                          .size()
                      == 5);
          check(
              "reopen preserves authored properties and custom syntax",
              file("choices.properties").text().equals(PROPERTIES.replace("size=6", "size=9"))
                  && file("counter.txt").text().equals("count=11\n")
                  && !JsonParser.parseString(file("bare.json").text())
                      .getAsJsonObject()
                      .has("schemaVersion"));
          complete = true;
          return true;
        }
        if (FileFormatsExample.broken().status().lifecycle()
            != ClientFileSettings.Lifecycle.UNAVAILABLE) return false;
        check(
            "malformed source stays unavailable without substitute defaults",
            FileFormatsExample.broken().runtime().orElseThrow().view().value().isEmpty()
                && file("broken.properties").text().equals("enabled=TRUE\nsize=4\ntitle=before\n"));
        check(
            "valid source initialization preserves authored formatting and omission",
            file("choices.properties").text().equals(PROPERTIES)
                && FileFormatsExample.properties()
                    .runtime()
                    .orElseThrow()
                    .view()
                    .value()
                    .orElseThrow()
                    .enabled());
        check(
            "actual file values differ from factory defaults",
            FileFormatsExample.properties()
                        .runtime()
                        .orElseThrow()
                        .view()
                        .value()
                        .orElseThrow()
                        .size()
                    == 6
                && FileFormatsExample.bare()
                        .runtime()
                        .orElseThrow()
                        .view()
                        .value()
                        .orElseThrow()
                        .count()
                    == 6
                && FileFormatsExample.custom()
                        .runtime()
                        .orElseThrow()
                        .view()
                        .value()
                        .orElseThrow()
                        .count()
                    == 6);
        open(FileFormatsExample.properties(), "Existing properties");
        next("existing properties native view opened");
      }
      case 1 -> {
        if (!ready()) return false;
        edit("size", "9");
        next("property edit staged");
      }
      case 2 -> {
        if (!ready() || !form.editorView().dirty()) return false;
        click(form, button(form, "Save and apply"));
        next("native properties commit requested");
      }
      case 3 -> {
        if (!ready() || form.editorView().dirty()) return false;
        check(
            "property commit retains CRLF comments and omitted default",
            file("choices.properties").text().equals(PROPERTIES.replace("size=6", "size=9"))
                && FileFormatsExample.properties()
                        .runtime()
                        .orElseThrow()
                        .view()
                        .value()
                        .orElseThrow()
                        .size()
                    == 9);
        noOp = file("choices.properties");
        click(form, button(form, "Save and apply"));
        next("actual unchanged property commit requested");
      }
      case 4 -> {
        if (!ready()
            || form.editorView().lastReceipt().orElseThrow().persistence()
                != CommitReceipt.Persistence.UNCHANGED) return false;
        check(
            "unchanged property save never replaces the source",
            noOp.equals(file("choices.properties")));
        geometry(form);
        capture.accept("form-files-properties.png", () -> next("native properties frame captured"));
      }
      case 5 -> {
        click(form, button(form, "Done"));
        check(
            "closing property editor preserves shared owner",
            client.screen == parent
                && FileFormatsExample.properties().status().lifecycle()
                    == ClientFileSettings.Lifecycle.READY);
        open(FileFormatsExample.bare(), "Existing bare JSON");
        next("bare JSON native view opened");
      }
      case 6 -> {
        if (!ready()) return false;
        edit("count", "10");
        next("bare JSON value staged");
      }
      case 7 -> {
        if (!ready() || !form.editorView().dirty()) return false;
        click(form, button(form, "Save and apply"));
        next("bare JSON commit requested");
      }
      case 8 -> {
        if (!ready() || form.editorView().dirty()) return false;
        var saved = JsonParser.parseString(file("bare.json").text()).getAsJsonObject();
        check(
            "existing JSON remains bare after actual commit",
            saved.size() == 1
                && saved.get("count").getAsInt() == 10
                && FileFormatsExample.bare()
                        .runtime()
                        .orElseThrow()
                        .view()
                        .value()
                        .orElseThrow()
                        .count()
                    == 10);
        click(form, button(form, "Done"));
        open(FileFormatsExample.custom(), "Explicit counter format");
        next("custom source native view opened");
      }
      case 9 -> {
        if (!ready()) return false;
        edit("count", "11");
        next("custom-format draft staged");
      }
      case 10 -> {
        if (!ready() || !form.editorView().dirty()) return false;
        click(form, button(form, "Save and apply"));
        next("custom source commit requested");
      }
      case 11 -> {
        if (!ready() || form.editorView().dirty()) return false;
        check(
            "custom source owns actual representation and publication",
            file("counter.txt").text().equals("count=11\n")
                && FileFormatsExample.custom()
                        .runtime()
                        .orElseThrow()
                        .view()
                        .value()
                        .orElseThrow()
                        .count()
                    == 11);
        click(form, button(form, "Done"));
        client.setScreen(
            FileFormatsExample.broken().screen(parent, Component.literal("Malformed properties")));
        check(
            "unavailable entry supplies an explicit native retry",
            button(client.screen, "Retry").active);
        Files.writeString(path("broken.properties"), "size=5\ntitle=repaired\n");
        repaired = file("broken.properties");
        click(client.screen, button(client.screen, "Retry"));
        next("repaired actual source retry requested");
      }
      case 12 -> {
        if (!(client.screen instanceof SettingsScreen<?> current)
            || FileFormatsExample.broken().status().lifecycle()
                != ClientFileSettings.Lifecycle.READY) return false;
        form = current;
        if (!ready()) return false;
        check(
            "explicit retry publishes repaired source without replacing it",
            repaired.equals(file("broken.properties"))
                && FileFormatsExample.broken()
                        .runtime()
                        .orElseThrow()
                        .view()
                        .value()
                        .orElseThrow()
                        .size()
                    == 5);
        click(form, button(form, "Done"));
        check(
            "all file formats remain at their actual paths",
            Files.exists(path("choices.properties"))
                && Files.exists(path("bare.json"))
                && Files.exists(path("counter.txt"))
                && Files.exists(path("broken.properties")));
        complete = true;
        return true;
      }
      default -> throw new AssertionError("Unknown file-factory stage " + stage);
    }
    return false;
  }

  JsonObject report() {
    var result = new JsonObject();
    result.addProperty("status", complete ? "PASS" : "PENDING");
    result.addProperty("stage", stage);
    result.addProperty(
        "factoriesClosed",
        List.of(
                FileFormatsExample.properties(),
                FileFormatsExample.bare(),
                FileFormatsExample.custom(),
                FileFormatsExample.broken())
            .stream()
            .allMatch(value -> value.status().lifecycle() == ClientFileSettings.Lifecycle.CLOSED));
    result.add("checks", checks);
    return result;
  }
}
