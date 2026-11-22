package example.reformprobe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.configuration.ServerSettings;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.SettingsHandle;
import design.inherent.reform.storage.StorageBinding;
import example.reform.NativeExample;
import example.reform.ServerPolicyExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Actual packaged-library server bindings, native source restrictions, save/reopen and closure. */
final class NativeServerPolicyProbe {
  private final JsonObject report = new JsonObject();
  private final ArrayList<String> checks = new ArrayList<>();
  private ServerSettings scope;
  private ServerSettings.Document<?> file, nativeDocument;
  private ServerSettings.Editor<?> editor, competing, limited, nativeEditor;
  private SettingsHandle<?> retiredHandle;
  private OwnerContext retiredContext;
  private boolean passed;
  private Path filePath;
  private byte[] savedBytes;
  private int reloads;

  NativeServerPolicyProbe() {
    NeoForge.EVENT_BUS.addListener(this::started);
    NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::stopped);
  }

  private void check(String label, boolean valid) {
    if (!valid) throw new AssertionError(label);
    checks.add(label);
  }

  private void refused(String label, Runnable action) {
    boolean refused = false;
    try {
      action.run();
    } catch (RuntimeException expected) {
      refused = true;
    }
    check(label, refused);
  }

  private static <T> CompletionStage<T> run(
      OwnerContext context, Supplier<OperationHandle<T>> action) {
    return context.onOwner(action::get).thenCompose(DocumentActions::result);
  }

  private void started(ServerStartedEvent event) {
    var server = event.getServer();
    report.addProperty("status", "FAIL");
    try {
      var artifact = ModList.get().getModFileById("reform").getFile().getFilePath();
      report.addProperty(
          "librarySha256",
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));
      var mode =
          JsonParser.parseString(
                  Files.readString(
                      server.getServerDirectory().resolve("server-policy-probe-mode.json")))
              .getAsJsonObject()
              .get("mode")
              .getAsString();
      boolean reopen = mode.equals("reopen");
      report.addProperty("mode", mode);
      scope = ServerSettings.forServer(server);
      file = NativeExample.serverPolicies().policy(server);
      nativeDocument = NativeExample.serverPolicies().nativePolicy(server);
      filePath =
          server
              .getWorldPath(LevelResource.ROOT)
              .resolve("serverconfig")
              .resolve(ServerPolicyExample.FILE)
              .toAbsolutePath();
      check(
          "the same actual server retains its admitted scope",
          scope == ServerSettings.forServer(server));
      check(
          "native initialization and domain effective state remain separate",
          nativeDocument.initialization().isEmpty() && nativeDocument.runtime().isEmpty());
      var unrelatedKind = ServerSettings.<java.util.Map<String, Object>>nativeKind();
      check(
          "native provider lookup requires its exact typed identity",
          ServerSettings.admittedNative(server, ServerPolicyExample.NATIVE, unrelatedKind)
              .isEmpty());
      check(
          "a file policy is not a registered native provider",
          ServerSettings.admittedNative(server, ServerPolicyExample.DEFINITION.id(), unrelatedKind)
              .isEmpty());
      var failedRoute = DocumentId.parse(NativeExample.ID + ":failed_native_provider");
      var untouched = filePath.resolveSibling("never-created-native-provider.json");
      var originalFailure = new IllegalStateException("native provider construction refused");
      var cleanupFailure = new IllegalStateException("native provider cleanup refused");
      var failedContext = new java.util.concurrent.atomic.AtomicReference<OwnerContext>();
      try {
        scope.nativePolicy(
            design.inherent.reform.neoforge.configuration.NeoForgeServerRegistration.of(
                NativeExample.serverPolicies().container()),
            failedRoute,
            untouched,
            unrelatedKind,
            owner -> {
              failedContext.set(owner);
              owner.onClose(
                  () -> {
                    throw cleanupFailure;
                  });
              throw originalFailure;
            });
        throw new AssertionError("failed native provider was admitted");
      } catch (IllegalStateException expected) {
        check(
            "native provider cleanup retains the original failure and suppressed cause",
            expected == originalFailure
                && Arrays.asList(expected.getSuppressed()).contains(cleanupFailure));
      }
      check(
          "failed native provider closes its context without registration or file creation",
          failedContext.get() != null
              && !failedContext.get().isOpen()
              && scope.document(failedRoute).isEmpty()
              && !Files.exists(untouched));
      check(
          "native SERVER file uses its actual world-specific root",
          NativeExample.serverPolicies()
              .nativeConfig()
              .getFullPath()
              .toAbsolutePath()
              .normalize()
              .equals(filePath.resolveSibling(ServerPolicyExample.NATIVE_FILE).normalize()));
      var context = file.context();
      CompletionStage<Void> work =
          file.initialization()
              .orElseThrow()
              .thenCompose(
                  receipt ->
                      context.onOwner(
                          () -> {
                            check(
                                "admitted lifecycle initializes actual saved policy",
                                receipt.application() == CommitReceipt.Application.APPLIED
                                    && new ServerPolicyExample.Policy(reopen ? 12 : 9)
                                        .equals(NativeExample.serverPolicies().effectivePolicy())
                                    && file.runtime()
                                        .orElseThrow()
                                        .view()
                                        .value()
                                        .orElseThrow()
                                        .equals(new ServerPolicyExample.Policy(reopen ? 12 : 9)));
                            check(
                                "bootstrap uses saved values rather than declared defaults",
                                !file.runtime()
                                    .orElseThrow()
                                    .view()
                                    .value()
                                    .orElseThrow()
                                    .equals(ServerPolicyExample.DEFINITION.defaults()));
                            var source = server.createCommandSourceStack();
                            refused(
                                "lowered command permission cannot open an operator editor",
                                () -> file.editor(source.withPermission(0)));
                            refused(
                                "a foreign output source cannot impersonate native console authority",
                                () -> file.editor(source.withSource(CommandSource.NULL)));
                            var alias =
                                new DocumentDefinition<>(
                                    DocumentId.parse(NativeExample.ID + ":server_alias"),
                                    1,
                                    ServerPolicyExample.DEFINITION.type(),
                                    ServerPolicyExample.DEFINITION::defaults);
                            refused(
                                "different IDs cannot claim the same source",
                                () ->
                                    scope.serverPolicy(
                                        design.inherent.reform.neoforge.configuration
                                            .NeoForgeServerRegistration.of(
                                            NativeExample.serverPolicies().container()),
                                        alias,
                                        StorageBinding.relative(
                                            filePath.getParent(),
                                            filePath.getFileName().toString())));
                            refused(
                                "portable-case file aliases refuse before initialization",
                                () ->
                                    scope.serverPolicy(
                                        design.inherent.reform.neoforge.configuration
                                            .NeoForgeServerRegistration.of(
                                            NativeExample.serverPolicies().container()),
                                        alias,
                                        StorageBinding.relative(
                                            filePath.getParent(),
                                            filePath
                                                .getFileName()
                                                .toString()
                                                .toUpperCase(java.util.Locale.ROOT))));
                            refused(
                                "an owned file cannot replace the admitted native serializer",
                                () ->
                                    scope.serverPolicy(
                                        design.inherent.reform.neoforge.configuration
                                            .NeoForgeServerRegistration.of(
                                            NativeExample.serverPolicies().container()),
                                        alias,
                                        StorageBinding.relative(
                                            filePath.getParent(),
                                            ServerPolicyExample.NATIVE_FILE)));
                            editor = file.editor(source);
                            competing = file.editor(source);
                            limited = file.editor(source.withPermission(2), 2, 4);
                            nativeEditor = nativeDocument.editor(source);
                            reloads = NativeExample.serverPolicies().reloads().saves();
                            return null;
                          }));
      work = work.thenCompose(ignored -> reloadSavedWithoutWriting(server, reopen ? 12 : 9));
      work =
          work.thenCompose(
              ignored ->
                  context.onWorker(
                      () -> {
                        refused(
                            "scope binding requires the actual server thread",
                            () -> ServerSettings.forServer(server));
                        return null;
                      }));
      work =
          work.thenCompose(
                  ignored -> run(context, () -> editor.actions().ensureLoaded(EntryPoint.COMMAND)))
              .thenCompose(
                  view ->
                      context.onOwner(
                          () -> {
                            check(
                                "ordinary server editor loads through shared services",
                                view.validation().valid() && !view.dirty());
                            return null;
                          }));
      work =
          work.thenCompose(
                  ignored ->
                      run(context, () -> competing.actions().ensureLoaded(EntryPoint.COMMAND)))
              .thenApply(ignored -> null);
      work =
          work.thenCompose(
                  ignored ->
                      run(
                          context,
                          () ->
                              editor
                                  .actions()
                                  .edit(EntryPoint.COMMAND, "level", reopen ? "11" : "12")))
              .thenApply(ignored -> null);
      work =
          work.thenCompose(ignored -> run(context, () -> editor.actions().save(EntryPoint.COMMAND)))
              .thenCompose(
                  commit ->
                      context.onOwner(
                          () -> {
                            check(
                                "server file save retains real persistence and publication",
                                commit.receipt().orElseThrow().acknowledgesBaseline()
                                    && commit.receipt().orElseThrow().application()
                                        == CommitReceipt.Application.APPLIED
                                    && file.runtime()
                                        .orElseThrow()
                                        .view()
                                        .value()
                                        .orElseThrow()
                                        .equals(new ServerPolicyExample.Policy(reopen ? 11 : 12)));
                            savedBytes = Files.readAllBytes(filePath);
                            return null;
                          }));
      work =
          work.thenCompose(
                  ignored ->
                      run(
                          context,
                          () -> competing.actions().edit(EntryPoint.COMMAND, "level", "13")))
              .thenApply(ignored -> null);
      work =
          work.thenCompose(
                  ignored -> run(context, () -> competing.actions().save(EntryPoint.COMMAND)))
              .thenCompose(
                  commit ->
                      context.onOwner(
                          () -> {
                            check(
                                "a stale server draft cannot overwrite another editor",
                                commit.receipt().isEmpty()
                                    && !commit.editorReport().valid()
                                    && competing.actions().view().dirty()
                                    && Arrays.equals(savedBytes, Files.readAllBytes(filePath)));
                            return null;
                          }));
      work =
          work.thenCompose(
                  ignored -> run(context, () -> limited.actions().ensureLoaded(EntryPoint.COMMAND)))
              .thenApply(ignored -> null);
      work =
          work.thenCompose(
                  ignored ->
                      run(context, () -> limited.actions().edit(EntryPoint.COMMAND, "level", "14")))
              .thenApply(ignored -> null);
      work =
          work.thenCompose(
              ignored ->
                  context.onOwner(
                      () -> {
                        boolean denied = false;
                        try {
                          limited.actions().save(EntryPoint.COMMAND);
                        } catch (PolicyRefusal expected) {
                          denied =
                              expected.report().issues().stream()
                                  .anyMatch(issue -> issue.code().equals("owner.permission"));
                        }
                        check(
                            "read-only command access retains its draft without writing",
                            denied
                                && limited.actions().view().dirty()
                                && Arrays.equals(savedBytes, Files.readAllBytes(filePath)));
                        limited.close();
                        for (int i = 0; i < 80; i++)
                          file.editor(server.createCommandSourceStack()).close();
                        check(
                            "short-lived server editors release access and hook capacity",
                            file.context().isOpen());
                        return null;
                      }));
      work =
          work.thenCompose(
                  ignored ->
                      run(context, () -> editor.actions().edit(EntryPoint.COMMAND, "level", "12")))
              .thenApply(ignored -> null);
      work =
          work.thenCompose(ignored -> run(context, () -> editor.actions().save(EntryPoint.COMMAND)))
              .thenCompose(
                  commit ->
                      context.onOwner(
                          () -> {
                            check(
                                "explicit final save preserves the intended next-boot input",
                                commit.receipt().orElseThrow().acknowledgesBaseline()
                                    && file.runtime()
                                        .orElseThrow()
                                        .view()
                                        .value()
                                        .orElseThrow()
                                        .equals(new ServerPolicyExample.Policy(12)));
                            return null;
                          }));
      work =
          work.thenCompose(
                  ignored ->
                      run(
                          nativeDocument.context(),
                          () -> nativeEditor.actions().ensureLoaded(EntryPoint.COMMAND)))
              .thenCompose(
                  view ->
                      nativeDocument
                          .context()
                          .onOwner(
                              () -> {
                                check(
                                    "actual native policy loads without an invented runtime snapshot",
                                    view.validation().valid()
                                        && !view.dirty()
                                        && view.snapshot()
                                                .orElseThrow()
                                                .inspect()
                                                .candidate()
                                                .orElseThrow()
                                                .value()
                                            instanceof java.util.Map<?, ?> values
                                        && values.get("level").equals(reopen ? 13 : 7));
                                return null;
                              }));
      work =
          work.thenCompose(
                  ignored ->
                      run(
                          nativeDocument.context(),
                          () -> nativeEditor.actions().edit(EntryPoint.COMMAND, "level", "13")))
              .thenApply(ignored -> null);
      work =
          work.thenCompose(
                  ignored ->
                      run(
                          nativeDocument.context(),
                          () -> nativeEditor.actions().save(EntryPoint.COMMAND)))
              .thenCompose(
                  commit ->
                      nativeDocument
                          .context()
                          .onOwner(
                              () -> {
                                var receipt = commit.receipt().orElseThrow();
                                var nativeSave = new JsonObject();
                                nativeSave.addProperty("persistence", receipt.persistence().name());
                                nativeSave.addProperty("application", receipt.application().name());
                                nativeSave.addProperty("saveCallbacksBefore", reloads);
                                nativeSave.addProperty(
                                    "saveCallbacksAfter",
                                    NativeExample.serverPolicies().reloads().saves());
                                report.add("nativeSave", nativeSave);
                                check(
                                    "native policy retains actual serializer and staged callback receipt",
                                    receipt.persistence()
                                            == (reopen
                                                ? CommitReceipt.Persistence.UNCHANGED
                                                : CommitReceipt.Persistence.SAVED)
                                        && receipt.application()
                                            == (reopen
                                                ? CommitReceipt.Application.NOT_ATTEMPTED
                                                : CommitReceipt.Application.STAGED)
                                        && NativeExample.serverPolicies().reloads().saves()
                                            == reloads + (reopen ? 0 : 1));
                                check(
                                    "native save retains TOML without an injected schema envelope",
                                    Files.readString(
                                                NativeExample.serverPolicies()
                                                    .nativeConfig()
                                                    .getFullPath())
                                            .contains("level = 13")
                                        && !Files.readString(
                                                NativeExample.serverPolicies()
                                                    .nativeConfig()
                                                    .getFullPath())
                                            .contains("schemaVersion"));
                                return null;
                              }));
      work =
          work.thenCompose(
                  ignored ->
                      context.onOwner(
                          () -> {
                            var badDefinition =
                                new DocumentDefinition<>(
                                    DocumentId.parse(NativeExample.ID + ":server_bad"),
                                    1,
                                    ServerPolicyExample.DEFINITION.type(),
                                    ServerPolicyExample.DEFINITION::defaults);
                            var bad =
                                scope.serverPolicy(
                                    design.inherent.reform.neoforge.configuration
                                        .NeoForgeServerRegistration.of(
                                        NativeExample.serverPolicies().container()),
                                    badDefinition,
                                    StorageBinding.relative(
                                        filePath.getParent(), "reform-server-bad.json"));
                            return bad.initialization()
                                .orElseThrow()
                                .thenCompose(
                                    receipt ->
                                        bad.context()
                                            .<Void>onOwner(
                                                () -> {
                                                  check(
                                                      "malformed server source is retained without effective defaults",
                                                      receipt.application()
                                                              != CommitReceipt.Application.APPLIED
                                                          && bad.runtime()
                                                              .orElseThrow()
                                                              .view()
                                                              .value()
                                                              .isEmpty()
                                                          && Files.readString(
                                                                  filePath.resolveSibling(
                                                                      "reform-server-bad.json"))
                                                              .equals("malformed retained\n"));
                                                  return null;
                                                }));
                          }))
              .thenCompose(stage -> stage);
      work =
          work.thenCompose(
                  ignored ->
                      context.onOwner(
                          () -> {
                            retiredHandle = file.runtime().orElseThrow();
                            retiredContext = file.context();
                            scope.close();
                            check(
                                "closing one server scope closes its actual handles and editors",
                                !retiredContext.isOpen()
                                    && retiredHandle.view().availability()
                                        == SettingsHandle.Availability.CLOSED);
                            refused(
                                "a retired editor cannot target a replacement",
                                () -> editor.actions());
                            scope = ServerSettings.forServer(server);
                            check(
                                "replacement scope does not inherit old document bindings",
                                scope.document(ServerPolicyExample.DEFINITION.id()).isEmpty());
                            file =
                                scope.serverPolicy(
                                    design.inherent.reform.neoforge.configuration
                                        .NeoForgeServerRegistration.of(
                                        NativeExample.serverPolicies().container()),
                                    ServerPolicyExample.DEFINITION,
                                    StorageBinding.relative(
                                        filePath.getParent(), ServerPolicyExample.FILE));
                            nativeDocument =
                                design.inherent.reform.neoforge.configuration.NeoForgeServerSettings
                                    .registered(
                                        scope,
                                        NativeExample.serverPolicies().container(),
                                        NativeExample.serverPolicies().nativeConfig(),
                                        ServerPolicyExample.NATIVE);
                            return file.initialization()
                                .orElseThrow()
                                .thenCompose(
                                    receipt ->
                                        file.context()
                                            .<Void>onOwner(
                                                () -> {
                                                  check(
                                                      "replacement reads the actual saved source without reviving old handles",
                                                      receipt.application()
                                                              == CommitReceipt.Application.APPLIED
                                                          && file.runtime()
                                                              .orElseThrow()
                                                              .view()
                                                              .value()
                                                              .orElseThrow()
                                                              .equals(
                                                                  new ServerPolicyExample.Policy(
                                                                      12))
                                                          && retiredHandle.view().availability()
                                                              == SettingsHandle.Availability
                                                                  .CLOSED);
                                                  editor =
                                                      file.editor(
                                                          server.createCommandSourceStack());
                                                  nativeEditor =
                                                      nativeDocument.editor(
                                                          server.createCommandSourceStack());
                                                  return null;
                                                }));
                          }))
              .thenCompose(stage -> stage);
      work.thenCompose(
              ignored ->
                  file.context()
                      .onOwner(() -> NativeServerCommandControls.run(server, file, filePath))
                      .thenCompose(stage -> stage))
          .thenCompose(
              commands -> {
                report.add("actualCommands", new Gson().toJsonTree(commands));
                return file.context()
                    .onOwner(() -> NativeServerSearchControls.run(server, file.context()))
                    .thenCompose(stage -> stage);
              })
          .whenComplete(
              (observed, failed) -> {
                if (failed != null) report.addProperty("failure", failed.toString());
                else {
                  report.add("actualSearch", new Gson().toJsonTree(observed));
                  passed = true;
                }
                server.halt(false);
              });
    } catch (Throwable failed) {
      report.addProperty("failure", failed.toString());
      server.halt(false);
    }
  }

  private CompletionStage<Void> reloadSavedWithoutWriting(
      MinecraftServer server, int originalLevel) {
    var context = file.context();
    return context
        .onOwner(
            () -> {
              var nativeSource = server.createCommandSourceStack();
              refused(
                  "external reload checks actual command WRITE authority",
                  () -> file.reloadFromSaved(nativeSource.withPermission(0), 2));
              var original = Files.readString(filePath);
              var replacement = "{\"schemaVersion\":1,\"values\":{\"level\":11}}\n";
              Files.writeString(filePath, replacement);
              return file.reloadFromSaved(nativeSource, 2)
                  .thenCompose(
                      receipt ->
                          context.onOwner(
                              () -> {
                                check(
                                    "reload applies the actual saved policy without rewriting its bytes",
                                    receipt.application() == CommitReceipt.Application.APPLIED
                                        && receipt.persistence()
                                            == CommitReceipt.Persistence.NOT_ATTEMPTED
                                        && new ServerPolicyExample.Policy(11)
                                            .equals(
                                                NativeExample.serverPolicies().effectivePolicy())
                                        && Files.readString(filePath).equals(replacement));
                                Files.delete(filePath);
                                return file.reloadFromSaved(nativeSource, 2);
                              }))
                  .thenCompose(stage -> stage)
                  .thenCompose(
                      receipt ->
                          context.onOwner(
                              () -> {
                                check(
                                    "reload preserves previous effective policy and leaves missing source absent",
                                    receipt.application() == CommitReceipt.Application.UNAVAILABLE
                                        && receipt.persistence()
                                            == CommitReceipt.Persistence.NOT_ATTEMPTED
                                        && !Files.exists(filePath)
                                        && new ServerPolicyExample.Policy(11)
                                            .equals(
                                                NativeExample.serverPolicies().effectivePolicy()));
                                Files.writeString(filePath, original);
                                return file.reloadFromSaved(nativeSource, 2);
                              }))
                  .thenCompose(stage -> stage)
                  .thenAccept(
                      receipt ->
                          check(
                              "restored exact source can be applied after unavailable reload",
                              receipt.application() == CommitReceipt.Application.APPLIED
                                  && new ServerPolicyExample.Policy(originalLevel)
                                      .equals(NativeExample.serverPolicies().effectivePolicy())));
            })
        .thenCompose(stage -> stage);
  }

  private void stopped(ServerStoppedEvent event) {
    var server = event.getServer();
    try {
      check(
          "native stop closes current server documents",
          file != null
              && !file.context().isOpen()
              && !nativeDocument.context().isOpen()
              && file.runtime().orElseThrow().view().availability()
                  == SettingsHandle.Availability.CLOSED);
      refused("native shutdown closes file editors", () -> editor.actions());
      refused("native shutdown closes registered-native editors", () -> nativeEditor.actions());
      refused(
          "stopped server cannot acquire another scope", () -> ServerSettings.forServer(server));
      report.addProperty("serverShutdownClosed", true);
      report.addProperty("status", passed ? "PASS" : "FAIL");
    } catch (Throwable failed) {
      report.addProperty("shutdownFailure", failed.toString());
      report.addProperty("status", "FAIL");
    }
    report.add("checks", new Gson().toJsonTree(checks));
    try {
      Files.writeString(
          server.getServerDirectory().resolve("native-addon-server.json"),
          new GsonBuilder().setPrettyPrinting().create().toJson(report));
    } catch (Exception failed) {
      throw new IllegalStateException("Could not retain actual server-policy evidence", failed);
    }
  }
}
