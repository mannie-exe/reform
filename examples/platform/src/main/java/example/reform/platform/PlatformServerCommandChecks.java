package example.reform.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import design.inherent.reform.minecraft.configuration.ServerSettings;
import design.inherent.reform.owner.OwnerContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/** Actual loader-registered Brigadier tree, native console authority and asynchronous feedback. */
final class PlatformServerCommandChecks implements AutoCloseable {
  private record Invocation(String command, int accepted, String feedback) {}

  private final MinecraftServer server;
  private final ServerSettings.Document<PlatformData.Values> document;
  private final Path file;
  private final CompletableFuture<JsonObject> result = new CompletableFuture<>();
  private final JsonArray checks = new JsonArray();
  private final PlatformConsoleCapture console;
  private final List<Invocation> commands;
  private final String original;
  private int index, ticks;
  private boolean submitted;

  PlatformServerCommandChecks(
      MinecraftServer server, ServerSettings.Document<PlatformData.Values> document, Path file)
      throws java.io.IOException {
    this.server = server;
    this.document = document;
    this.file = file;
    this.original = Files.readString(file);
    this.console = new PlatformConsoleCapture(server);
    var sequence = new ArrayList<Invocation>();
    sequence.add(new Invocation("file status " + new UUID(0, 0), 0, "Operation is not retained"));
    sequence.add(new Invocation("file describe", 1, "declared fields: distance"));
    sequence.add(new Invocation("file get distance", 1, "draft distance = 9"));
    sequence.add(new Invocation("file set distance 12", 1, "dirty=true"));
    sequence.add(new Invocation("file validate", 1, ": valid"));
    sequence.add(new Invocation("file compare", 1, "distance: 9 -> 12"));
    sequence.add(new Invocation("file review", 1, "captured draft="));
    sequence.add(new Invocation("file export", 1, "in-memory document-value JSON"));
    sequence.add(new Invocation("file save", 1, "persistence=SAVED, application=APPLIED"));
    sequence.add(new Invocation("file set distance 9", 1, "dirty=true"));
    sequence.add(new Invocation("file save", 1, "persistence=SAVED, application=APPLIED"));
    if (server.getCommands().getDispatcher().getRoot().getChild("reformplatform").getChild("toml")
        != null) sequence.add(new Invocation("toml get level", 1, "draft level = 9"));
    commands = List.copyOf(sequence);
  }

  CompletionStage<JsonObject> result() {
    return result.minimalCompletionStage();
  }

  void tick() {
    if (result.isDone()) return;
    try {
      if (!server.isSameThread())
        throw new AssertionError("Console probe left the native server thread");
      if (++ticks > 300)
        throw new AssertionError("Timed out awaiting console " + index + ": " + console);
      if (index == 0 && !submitted) {
        var source = server.createCommandSourceStack();
        requireDenied(NativeServerCommandProbe.lowered(source));
        requireDenied(source.withSource(CommandSource.NULL));
        checks.add("actual native console refuses lowered permissions and foreign feedback source");
      }
      var invocation = commands.get(index);
      if (!submitted) {
        console.clear();
        if (invoke(invocation.command(), server.createCommandSourceStack())
            != invocation.accepted())
          throw new AssertionError("Unexpected Brigadier result for " + invocation.command());
        submitted = true;
      }
      var feedback = console.find(invocation.feedback());
      if (feedback.isEmpty()) return;
      checks.add(invocation.command() + " => " + feedback.orElseThrow());
      if (index == 0) {
        // No ordinary editor is held here. An unknown status lookup must consume no access slot.
        var leases = new ArrayList<OwnerContext.Access>();
        try {
          for (int i = 0; i < OwnerContext.Limits.STANDARD.maximumAccesses(); i++)
            leases.add(document.context().access(() -> true, () -> false));
        } finally {
          leases.forEach(OwnerContext.Access::close);
        }
        checks.add("unknown operation status allocates no editor or access lease");
      }
      if (index == 3 || index == 8 || index == 10) {
        int expected = index == 8 ? 12 : 9;
        var actual = document.runtime().orElseThrow().view().value().orElseThrow();
        int saved =
            JsonParser.parseString(Files.readString(file))
                .getAsJsonObject()
                .getAsJsonObject("values")
                .get("distance")
                .getAsInt();
        if (actual.distance() != expected
            || saved != expected
            || !actual.label().equals("platform"))
          throw new AssertionError(
              "Console result disagrees with saved/effective/unselected fields");
        if (index != 8 && !Files.readString(file).equals(original))
          throw new AssertionError("Staging or explicit restoration changed original source bytes");
        checks.add("command " + index + " agrees with actual disk, runtime and unselected value");
      }
      submitted = false;
      if (++index == commands.size()) {
        var report = new JsonObject();
        report.addProperty("status", "PASS");
        report.addProperty("commands", commands.size());
        report.addProperty("nativeToml", commands.size() == 12);
        report.add("checks", checks);
        close();
        result.complete(report);
      }
    } catch (Exception | AssertionError failed) {
      close();
      result.completeExceptionally(failed);
    }
  }

  private void requireDenied(CommandSourceStack source) throws CommandSyntaxException {
    try {
      invoke("file describe", source);
    } catch (CommandSyntaxException expected) {
      return;
    }
    throw new AssertionError("Native command tree admitted a lowered or foreign source");
  }

  private int invoke(String command, CommandSourceStack source) throws CommandSyntaxException {
    return server.getCommands().getDispatcher().execute("reformplatform " + command, source);
  }

  @Override
  public void close() {
    console.close();
  }
}
