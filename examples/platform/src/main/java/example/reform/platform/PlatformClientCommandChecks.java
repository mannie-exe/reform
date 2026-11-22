package example.reform.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.importing.ImportInput;
import design.inherent.reform.importing.ImportSource;
import design.inherent.reform.minecraft.client.ClientFileSettings;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.minecraft.command.SettingsCommands;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.DocumentConflicts;
import design.inherent.reform.operation.DocumentImports;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.presentation.NumericDisplay;
import design.inherent.reform.value.ValueTypes;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

/** Actual typed ChatScreen commands; native source capture is used only for pure suggestions. */
final class PlatformClientCommandChecks implements PlatformFormProbe.Scene {
  private static final String ROOT = "platformcommands";
  private static final String SOURCE = "reform_platform_example:command_sample";
  private static final String PREVIEW = "import preview " + SOURCE + " reform:import/value_json";
  private static ClientFileSettings<PlatformData.Values> settings;
  private static PlatformClientCommandChecks active;
  private static Function<String, CompletionStage<Suggestions>> suggest;
  private static Runnable releaseSource;
  private static Path path;

  static void register(ClientRegistration registration) {
    settings =
        ClientFileSettings.clientJson(
            registration,
            PlatformData.definition(
                "commands",
                ValueTypes.numericDisplay(
                    ValueTypes.integer(1, 64),
                    NumericDisplay.blocks()
                        .withCaptions(
                            List.of(
                                new NumericDisplay.Caption(
                                    BigDecimal.valueOf(7),
                                    "example.distance.default",
                                    "Default distance"))))));
    path =
        registration
            .configurationDirectory()
            .resolve(registration.modId())
            .resolve("commands.json");
    var catalog =
        settings.importCatalog(
            List.of(
                ImportSource.snapshot(
                    DocumentId.parse(SOURCE),
                    new ImportInput(
                        "Command sample",
                        "{\"distance\":9,\"label\":\"Imported\"}".getBytes(StandardCharsets.UTF_8),
                        1024))));
    registration.registerCommands(
        ROOT,
        new ClientRegistration.CommandTree() {
          @Override
          public <S> LiteralArgumentBuilder<S> bind(
              ClientRegistration.CommandSource<S> nativeSource) {
            var current = new AtomicReference<S>();
            var tracking =
                new ClientRegistration.CommandSource<S>() {
                  @Override
                  public boolean current(S source) {
                    return nativeSource.current(source);
                  }

                  @Override
                  public void requireLocalInvocation(S source) {
                    nativeSource.requireLocalInvocation(source);
                    current.set(source);
                  }
                };
            var branch =
                settings.commandBranch(
                    ROOT,
                    catalog,
                    tracking,
                    reply -> {
                      if (active != null) active.receive(reply);
                    });
            var parser = new CommandDispatcher<S>();
            parser.register(branch);
            suggest =
                text -> {
                  var source = current.get();
                  if (source == null || !nativeSource.current(source))
                    throw new AssertionError("Suggestions have no actual current native source");
                  return parser.getCompletionSuggestions(parser.parse(ROOT + " " + text, source));
                };
            releaseSource = () -> current.set(null);
            return branch;
          }
        });
  }

  static CompletionStage<JsonObject> run(ClientRegistration registration) {
    return PlatformFormProbe.run(
        registration,
        "CLIENT_COMMANDS",
        driver -> {
          active = new PlatformClientCommandChecks();
          return active;
        });
  }

  static CompletionStage<JsonObject> runWorld(ClientRegistration registration) {
    return PlatformFormProbe.run(
        registration,
        "CLIENT_COMMAND_WORLD",
        driver ->
            new PlatformFormProbe.Scene() {
              private final JsonObject report = new JsonObject();
              private CompletableFuture<JsonObject> commands;
              private CompletableFuture<JsonObject> held;
              private int stage;

              @Override
              public String diagnostic() {
                return "client command world stage=" + stage;
              }

              @Override
              public boolean tick() {
                var client = Minecraft.getInstance();
                switch (stage) {
                  case 0 -> {
                    NativeWorldProbe.start();
                    stage++;
                  }
                  case 1 -> {
                    if (client.player == null
                        || client.level == null
                        || NativeScreens.current(client) != null) return false;
                    commands = run(registration).toCompletableFuture();
                    stage++;
                  }
                  case 2 -> {
                    if (!commands.isDone()) return false;
                    report.add("variants", commands.join());
                    held = PlatformHeldClientChecks.run(registration).toCompletableFuture();
                    stage++;
                  }
                  case 3 -> {
                    if (!held.isDone()) return false;
                    report.add("held", held.join());
                    NativeWorldProbe.leave();
                    stage++;
                  }
                  case 4 -> {
                    if (client.player != null
                        || client.level != null
                        || client.getSingleplayerServer() != null
                        || !(NativeScreens.current(client) instanceof TitleScreen)) return false;
                    report.addProperty("status", "PASS");
                    return true;
                  }
                  default -> throw new AssertionError("Unexpected command world stage");
                }
                return false;
              }

              @Override
              public JsonObject report() {
                return report;
              }

              @Override
              public void close() {
                if (active != null) active.close();
                PlatformHeldClientChecks.closeActive();
              }
            });
  }

  static boolean stopped() {
    return settings != null && settings.status().lifecycle() == ClientFileSettings.Lifecycle.CLOSED;
  }

  private record Step(Supplier<String> command, Consumer<SettingsCommands.Reply> verify) {}

  private final Minecraft client = Minecraft.getInstance();
  private final List<Step> steps = new ArrayList<>();
  private final JsonObject report = new JsonObject();
  private final JsonArray checks = new JsonArray();
  private final String original;
  private SettingsCommands.Reply reply;
  private CompletableFuture<Suggestions> fields, sources, formats;
  private UUID savedId, importId, conflictId;
  private String conflictedSource;
  private int step, queued, distanceRow, labelRow;
  private boolean entered;

  private PlatformClientCommandChecks() throws Exception {
    if (client.player == null
        || client.getConnection() == null
        || client.getSingleplayerServer() == null)
      throw new AssertionError("Client command scene requires the actual integrated connection");
    original = Files.readString(path);
    report.addProperty("status", "RUNNING");
    report.add("checks", checks);
    add(
        "describe",
        r -> {
          check(
              "typed native description returns declared fields",
              result(r) instanceof DocumentActions.Description d && d.fields().size() == 2);
          fields = suggest.apply("set d").toCompletableFuture();
          sources = suggest.apply("import preview reform_platform_example:c").toCompletableFuture();
          formats =
              suggest.apply("import preview " + SOURCE + " reform:import/s").toCompletableFuture();
        });
    add(
        "get distance",
        r -> {
          check(
              "typed native get observes actual source",
              raw((EditorActor.Inspection<?>) result(r), "distance").equals("7"));
          check(
              "native command reports owner caption without replacing its numeric observation",
              r.message().getString().contains("Default distance (7)"));
          check(
              "native command inspection states declared bounds and stored units",
              r.message().getString().contains("value >= 1 and value <= 64")
                  && r.message().getString().contains("stored unit: blocks"));
        });
    add(
        "set distance -",
        r ->
            check(
                "native command preserves invalid raw input",
                result(r) instanceof EditorActor.View<?> v && !v.validation().valid()));
    add(
        "validate",
        r ->
            check(
                "native command validates retained invalid draft",
                result(r) instanceof EditorActor.Validation v && !v.report().valid()));
    add(
        "save",
        r -> {
          check(
              "invalid native command save refuses",
              r.operation().orElseThrow().state() == OperationSnapshot.State.REFUSED
                  && r.level() == SettingsCommands.Level.ERROR);
          sameBytes(original, "refused command did not write");
        });
    add(
        "set distance 12",
        r ->
            check(
                "valid native command remains staged",
                result(r) instanceof EditorActor.View<?> v && v.dirty()));
    add(
        "validate",
        r ->
            check(
                "native owner accepts valid staged value",
                result(r) instanceof EditorActor.Validation v && v.report().valid()));
    add(
        "compare",
        r ->
            check(
                "native command comparison retains authored change",
                result(r) instanceof DocumentActions.Comparison c
                    && c.authoredChanges()
                    && c.changes().size() == 1));
    add(
        "export",
        r ->
            check(
                "native export contains complete staged value",
                result(r) instanceof DocumentActions.ExportArtifact e
                    && e.text().contains("\"distance\":12")
                    && e.representation().equals("document-value")));
    add(
        "explain",
        r -> {
          var view = (EditorActor.Inspection<?>) result(r);
          check(
              "native command distinguishes source and effective state",
              view.provenance().kind().equals("json")
                  && ((PlatformData.Values) view.effective().orElseThrow().value().orElseThrow())
                          .distance()
                      == 7);
        });
    add(
        "save",
        r -> {
          saved(r, 12);
          savedId = r.operation().orElseThrow().id();
        });
    add(
        () -> "status " + savedId,
        r ->
            check(
                "native status selects retained exact operation",
                r.kind() == SettingsCommands.Kind.STATUS
                    && r.operation().orElseThrow().id().equals(savedId)));
    add(
        () -> "cancel " + savedId,
        r ->
            check(
                "native cancellation cannot undo completed save",
                r.kind() == SettingsCommands.Kind.CANCELLATION
                    && r.operation().orElseThrow().state() == OperationSnapshot.State.COMPLETED));
    add(
        () -> "status " + UUID.randomUUID(),
        r ->
            check(
                "unknown native operation UUID grants no lookup",
                r.kind() == SettingsCommands.Kind.ERROR && r.operation().isEmpty()));
    add("set distance 7", this::result);
    add(
        "save",
        r -> {
          saved(r, 7);
          sameBytes(original, "native command explicitly restores original bytes");
        });
    add(
        "import list",
        r ->
            check(
                "native import lists admitted metadata",
                r.message().getString().contains("Command sample")));
    add(
        PREVIEW,
        r -> {
          var preview = (DocumentImports.Preview) result(r);
          importId = preview.id();
          check(
              "native import previews a typed comparison without save",
              preview.stageable()
                  && preview.comparisonAvailable()
                  && preview.changes().size() == 2);
          sameBytes(original, "native import preview writes no file");
        });
    add(
        () -> "import review " + importId,
        r ->
            check(
                "native import review binds issued selection",
                r.message().getString().contains("Command sample")));
    add(
        () -> "import stage " + importId,
        r -> {
          result(r);
          sameBytes(original, "native import staging leaves saved source unchanged");
        });
    add(
        "get distance",
        r ->
            check(
                "native staged import is an actual draft",
                raw((EditorActor.Inspection<?>) result(r), "distance").equals("9")));
    add(
        () -> "import stage " + importId,
        r ->
            check("consumed native import cannot replay", r.kind() == SettingsCommands.Kind.ERROR));
    add("undo", this::result);
    add(
        "get distance",
        r ->
            check(
                "one native Undo restores pre-import draft",
                raw((EditorActor.Inspection<?>) result(r), "distance").equals("7")));
    add("redo", this::result);
    add(
        "get distance",
        r ->
            check(
                "native Redo restores imported draft",
                raw((EditorActor.Inspection<?>) result(r), "distance").equals("9")));
    add("revert", this::result);
    add(
        "conflict preview",
        r ->
            check(
                "cold native conflict preview invents no source conflict",
                r.operation().orElseThrow().state() == OperationSnapshot.State.REFUSED));
    add(
        "set distance 8",
        r -> {
          result(r);
          try {
            Files.writeString(
                path, "{\"schemaVersion\":1,\"values\":{\"distance\":10,\"label\":\"Remote\"}}");
            conflictedSource = Files.readString(path);
          } catch (java.io.IOException failed) {
            throw new java.io.UncheckedIOException(failed);
          }
        });
    add("reload", this::result);
    add(
        "conflict preview",
        r -> {
          var review = (DocumentConflicts.Preview) result(r);
          conflictId = review.id();
          for (int index = 0; index < review.entries().size(); index++) {
            var row = review.entries().get(index);
            if (row.path().equals(FieldPath.ROOT.field("distance"))) distanceRow = index + 1;
            if (row.path().equals(FieldPath.ROOT.field("label"))) labelRow = index + 1;
          }
          check(
              "native conflict exposes baseline observed and draft rows",
              review.entries().size() == 2 && distanceRow > 0 && labelRow > 0);
        });
    add(
        () -> "conflict review " + conflictId + " " + labelRow,
        r ->
            check(
                "native conflict review exposes observed alternative",
                r.message().getString().contains("Remote")));
    add(
        () -> "conflict resolve " + conflictId,
        r ->
            check(
                "incomplete native conflict resolution refuses",
                r.kind() == SettingsCommands.Kind.ERROR));
    add(() -> "conflict choose " + conflictId + " " + distanceRow + " draft", this::choice);
    add(() -> "conflict choose " + conflictId + " " + labelRow + " observed", this::choice);
    add(
        () -> "conflict resolve " + conflictId,
        r -> {
          check(
              "native conflict resolution validates without saving",
              result(r) instanceof DocumentConflicts.Resolution<?> v && v.report().valid());
          sameBytes(
              conflictedSource, "native conflict resolution leaves observed source untouched");
        });
    add(
        () -> "conflict resolve " + conflictId,
        r ->
            check(
                "settled native conflict review cannot replay",
                r.kind() == SettingsCommands.Kind.ERROR));
    add("save", r -> saved(r, 8));
    add("set distance 7", this::result);
    add("set label platform", this::result);
    add(
        "save",
        r -> {
          saved(r, 7);
          sameBytes(original, "all native command flows restore original source bytes");
        });
  }

  private void add(String command, Consumer<SettingsCommands.Reply> verify) {
    add(() -> command, verify);
  }

  private void add(Supplier<String> command, Consumer<SettingsCommands.Reply> verify) {
    steps.add(new Step(command, verify));
  }

  private void check(String label, boolean valid) {
    if (!valid) throw new AssertionError(label);
    checks.add(label);
  }

  private Object result(SettingsCommands.Reply selected) {
    var operation = selected.operation().orElseThrow();
    if (operation.state() != OperationSnapshot.State.COMPLETED)
      throw new AssertionError(
          "Native command did not complete: " + selected.message().getString());
    return operation.result().orElseThrow();
  }

  private static String raw(EditorActor.Inspection<?> view, String field) {
    return ((DraftNode.Scalar) view.snapshot().draft().at(FieldPath.ROOT.field(field))).raw();
  }

  private void choice(SettingsCommands.Reply selected) {
    check(
        "native conflict choice remains local status",
        selected.kind() == SettingsCommands.Kind.STATUS && selected.operation().isEmpty());
  }

  private void saved(SettingsCommands.Reply selected, int value) {
    var receipt = ((EditorActor.Commit<?>) result(selected)).receipt().orElseThrow();
    check(
        "native save publishes actual value " + value,
        receipt.persistence() == CommitReceipt.Persistence.SAVED
            && receipt.application() == CommitReceipt.Application.APPLIED
            && settings.runtime().orElseThrow().view().value().orElseThrow().distance() == value);
  }

  private void sameBytes(String expected, String label) {
    try {
      check(label, Files.readString(path).equals(expected));
    } catch (java.io.IOException failed) {
      throw new java.io.UncheckedIOException(failed);
    }
  }

  private void receive(SettingsCommands.Reply incoming) {
    if (!client.isSameThread())
      throw new AssertionError("Native command feedback escaped client owner");
    if (incoming.message().getString().length() > 8192)
      throw new AssertionError("Native feedback exceeded bound");
    if (incoming.kind() == SettingsCommands.Kind.QUEUED) {
      queued++;
      return;
    }
    if (reply != null) throw new AssertionError("Duplicate terminal native command reply");
    reply = incoming;
  }

  @Override
  public boolean tick() {
    if (step < steps.size()) {
      var selected = steps.get(step);
      if (!entered) {
        entered = true;
        NativeWorldProbe.typed("/" + ROOT + " " + selected.command().get());
        return false;
      }
      if (reply == null) return false;
      selected.verify().accept(reply);
      reply = null;
      entered = false;
      step++;
      return false;
    }
    if (!fields.isDone() || !sources.isDone() || !formats.isDone()) return false;
    check(
        "real native source supplies field suggestions",
        fields.join().getList().stream()
            .map(s -> s.getText())
            .toList()
            .equals(List.of("distance")));
    check(
        "real native source supplies source suggestions",
        sources.join().getList().stream().map(s -> s.getText()).toList().equals(List.of(SOURCE)));
    check(
        "real native source supplies format suggestions",
        formats.join().getList().stream()
            .map(s -> s.getText())
            .toList()
            .equals(List.of("reform:import/schema_json")));
    report.addProperty("commands", steps.size());
    report.addProperty("queuedReplies", queued);
    report.addProperty("status", "PASS");
    return true;
  }

  @Override
  public String diagnostic() {
    return "native client command step=" + step + "/" + steps.size();
  }

  @Override
  public JsonObject report() {
    return report;
  }

  @Override
  public void close() {
    if (releaseSource != null) releaseSource.run();
    active = null;
  }
}
