package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.minecraft.client.ImportScreen;
import design.inherent.reform.minecraft.client.SettingsScreen;
import design.inherent.reform.owner.EditorActor;
import example.reform.ImportExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

/** Opt-in native OS-dialog controls; an external driver must operate and acknowledge actual UI. */
final class NativeChooserControls {
  private static final String VALUE =
      "{\"count\":3,\"label\":\"Chosen café\",\"roles\":[],\"note\":[]}";
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = client.screen;
  private final Path root = client.gameDirectory.toPath().toAbsolutePath();
  private final Path source = root.resolve("chooser-fixture/Chosen café.json");
  private final Consumer<String> progress;
  private final JsonObject report = new JsonObject();
  private final JsonArray checks = new JsonArray(), receipts = new JsonArray();
  private final CompletableFuture<Void> files;
  private SettingsScreen<?> form;
  private ImportScreen<?> abandoned;
  private UUID review;
  private String original;
  private int stage, request;

  NativeChooserControls(Consumer<String> progress) {
    this.progress = progress;
    report.addProperty("status", "RUNNING");
    report.add("checks", checks);
    report.add("externalReceipts", receipts);
    files =
        CompletableFuture.runAsync(
            () -> {
              try {
                Files.createDirectories(source.getParent());
                Files.writeString(source, VALUE);
              } catch (java.io.IOException failed) {
                throw new java.io.UncheckedIOException(failed);
              }
            },
            Util.ioPool());
  }

  JsonObject report() {
    return report;
  }

  private void check(String name, boolean value) {
    if (!value) throw new AssertionError(name);
    checks.add(name);
  }

  private void step(String name) {
    stage++;
    progress.accept(name);
  }

  private void press(Button button) {
    check("chooser widget uses actual native focus", focus(client.screen, button));
    click(client.screen, button);
  }

  private ImportScreen<?> popup() {
    return (ImportScreen<?>) client.screen;
  }

  private boolean ready() {
    return client.screen == form
        && form.editorView().availability() == EditorActor.Availability.READY
        && form.editorView().snapshot().isPresent()
        && button(form, "Done").active;
  }

  private void open() {
    press(ownerAction(form, "Import…"));
  }

  private void signal(String action) throws java.io.IOException {
    request++;
    var value = new JsonObject();
    value.addProperty("request", request);
    value.addProperty("pid", ProcessHandle.current().pid());
    value.addProperty("action", action);
    value.addProperty("selectedPath", source.toString());
    Files.writeString(root.resolve("native-chooser-request.json"), value.toString());
  }

  private boolean acknowledged(String action) throws java.io.IOException {
    var path = root.resolve("native-chooser-ack.json");
    if (!Files.isRegularFile(path) || Files.size(path) > 4096) return false;
    var value = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    if (value.get("request").getAsInt() != request) return false;
    check(
        "external actual dialog action " + action,
        value.get("action").getAsString().equals(action)
            && value.has("process")
            && value.has("window"));
    if (receipts.asList().stream()
        .noneMatch(item -> item.getAsJsonObject().get("request").getAsInt() == request))
      receipts.add(value);
    return true;
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (!files.isDone()) return false;
        files.join();
        form = (SettingsScreen<?>) ImportExample.screen(parent);
        client.setScreen(form);
        step("native chooser parent opened");
      }
      case 1 -> {
        if (!ready()) return false;
        original = Files.readString(root.resolve("config/reform_native_example/imports.json"));
        open();
        popup().onFilesDrop(List.of(source));
        press(button(popup(), "Preview import"));
        step("existing review prepared before native dialog");
      }
      case 2 -> {
        if (popup().reviewedInput().isEmpty() || !button(popup(), "Preview import").active)
          return false;
        review = popup().reviewedInput().orElseThrow().id();
        press(button(popup(), "Choose file…"));
        signal("cancel");
        step("native chooser cancel requested");
      }
      case 3 -> {
        if (!button(popup(), "Choose file…").active) return false;
        if (!acknowledged("cancel")) return false;
        check(
            "native Cancel preserves prior exact review",
            popup().reviewedInput().orElseThrow().id().equals(review));
        press(button(popup(), "Choose file…"));
        signal("select");
        step("native Unicode file selection requested");
      }
      case 4 -> {
        if (!button(popup(), "Choose file…").active) return false;
        if (!acknowledged("select")) return false;
        check(
            "chosen native file requires explicit preview",
            popup().reviewedInput().isEmpty() && button(popup(), "Preview import").active);
        press(button(popup(), "Preview import"));
        step("native chosen file preview requested");
      }
      case 5 -> {
        if (popup().reviewedInput().isEmpty() || !button(popup(), "Preview import").active)
          return false;
        var chosen = popup().reviewedInput().orElseThrow();
        check(
            "OS chosen Unicode path reaches bounded source",
            chosen.source().label().equals("Chosen café.json")
                && chosen.stageable()
                && chosen.changes().stream()
                    .anyMatch(change -> change.afterJson().contains("Chosen café")));
        popup().onClose();
        step("native chosen review returned without staging");
      }
      case 6 -> {
        if (!ready()) return false;
        check(
            "native selection has no draft or saved effect",
            !form.editorView().dirty()
                && ((DraftNode.Scalar)
                        form.editorView()
                            .snapshot()
                            .orElseThrow()
                            .draft()
                            .at(FieldPath.ROOT.field("label")))
                    .raw()
                    .equals("Original")
                && Files.readString(root.resolve("config/reform_native_example/imports.json"))
                    .equals(original));
        open();
        abandoned = popup();
        press(button(popup(), "Choose file…"));
        signal("visible");
        step("native entered dialog awaiting screen abandonment");
      }
      case 7 -> {
        if (!acknowledged("visible")) return false;
        abandoned.onClose();
        check("entered native chooser permits leaving import screen", client.screen == form);
        open();
        press(button(popup(), "Choose file…"));
        check(
            "outstanding native dialog does not start a second request",
            !button(popup(), "Preview import").active && button(popup(), "Choose file…").active);
        signal("cancel-abandoned");
        step("abandoned entered native dialog cancellation requested");
      }
      case 8 -> {
        if (!acknowledged("cancel-abandoned")) return false;
        check(
            "late abandoned native result cannot populate replacement screen",
            popup().reviewedInput().isEmpty() && !button(popup(), "Preview import").active);
        press(button(popup(), "Choose file…"));
        if (button(popup(), "Choose file…").active)
          return false; // Earlier native dialog still drains.
        signal("cancel-final");
        step("native dialog slot reused after abandoned completion");
      }
      case 9 -> {
        if (!button(popup(), "Choose file…").active) return false;
        if (!acknowledged("cancel-final")) return false;
        check(
            "replacement native dialog Cancel leaves source unselected",
            !button(popup(), "Preview import").active);
        popup().onClose();
        form.onClose();
        check("native chooser returns through same parent", client.screen == parent);
        check("native chooser source remains read-only", Files.readString(source).equals(VALUE));
        report.addProperty("nativeFileDialogExercised", true);
        report.addProperty("status", "PASS");
        return true;
      }
      default -> throw new AssertionError("Unknown native chooser stage " + stage);
    }
    return false;
  }
}
