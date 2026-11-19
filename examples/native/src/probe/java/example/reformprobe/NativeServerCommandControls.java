package example.reformprobe;

import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import design.inherent.reform.neoforge.configuration.ServerSettings;
import example.reform.ServerPolicyExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Real Brigadier entry and console feedback in a disposable dedicated world. */
final class NativeServerCommandControls {
  private record Invocation(String command, int accepted, String feedback) {}

  private final MinecraftServer server;
  private final ServerSettings.Document<?> document;
  private final Path file;
  private final CompletableFuture<List<String>> result = new CompletableFuture<>();
  private final ArrayList<String> observed = new ArrayList<>();
  private final Consumer<ServerTickEvent.Post> callback = this::tick;
  private final NativeConsoleCapture console;
  private final List<Invocation> commands =
      List.of(
          new Invocation("file status " + new UUID(0L, 0L), 0, "Operation is not retained"),
          new Invocation("file describe", 1, "declared fields: level"),
          new Invocation("file set level 15", 1, "dirty=true"),
          new Invocation("file validate", 1, ": valid"),
          new Invocation("file compare", 1, "level: 12 -> 15"),
          new Invocation("file review", 1, "captured draft="),
          new Invocation("file export", 1, "in-memory document-value JSON"),
          new Invocation("file save", 1, "persistence=SAVED, application=APPLIED"),
          new Invocation("file set level 12", 1, "dirty=true"),
          new Invocation("file save", 1, "persistence=SAVED, application=APPLIED"),
          new Invocation("toml get level", 1, "draft level = 13"));
  private int index, ticks;
  private boolean submitted;

  private NativeServerCommandControls(
      MinecraftServer server, ServerSettings.Document<?> document, Path file) {
    this.server = server;
    this.document = document;
    this.file = file;
    this.console = new NativeConsoleCapture(server);
  }

  static CompletionStage<List<String>> run(
      MinecraftServer server, ServerSettings.Document<?> document, Path file) {
    var controls = new NativeServerCommandControls(server, document, file);
    NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, controls.callback);
    return controls.result.minimalCompletionStage();
  }

  private void tick(ServerTickEvent.Post event) {
    if (event.getServer() != server || result.isDone()) return;
    try {
      if (++ticks > 300)
        throw new AssertionError("Timed out awaiting native console: " + index + " " + console);
      if (index == 0 && !submitted) {
        var source = server.createCommandSourceStack();
        requireDenied(source.withPermission(0));
        requireDenied(source.withSource(CommandSource.NULL));
        observed.add("actual console authority refuses lowered or foreign native source");
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
      observed.add(invocation.command() + " => " + feedback.orElseThrow());
      if (index == 0) {
        // One ordinary editor is already held by the source fixture. Status must allocate none.
        var leases = new ArrayList<design.inherent.reform.owner.OwnerContext.Access>();
        try {
          for (int i = 0; i < 63; i++)
            leases.add(document.context().access(() -> true, () -> false));
        } finally {
          leases.forEach(design.inherent.reform.owner.OwnerContext.Access::close);
        }
      }
      if (index == 2 || index == 7 || index == 9) {
        var expected = new ServerPolicyExample.Policy(index == 7 ? 15 : 12);
        var actual = document.runtime().orElseThrow().view().value().orElseThrow();
        int saved =
            JsonParser.parseString(Files.readString(file))
                .getAsJsonObject()
                .getAsJsonObject("values")
                .get("level")
                .getAsInt();
        if (!actual.equals(expected) || saved != expected.level())
          throw new AssertionError("Console result disagrees with actual disk and runtime value");
      }
      submitted = false;
      if (++index == commands.size()) finish(null);
    } catch (Exception | AssertionError failure) {
      finish(failure);
    }
  }

  private void requireDenied(CommandSourceStack source) throws Exception {
    try {
      invoke("file describe", source);
    } catch (CommandSyntaxException expected) {
      return;
    }
    throw new AssertionError("Native selector admitted a foreign or lowered source");
  }

  private int invoke(String command, CommandSourceStack source) throws CommandSyntaxException {
    return server.getCommands().getDispatcher().execute("reformpolicy " + command, source);
  }

  private void finish(Throwable failure) {
    NeoForge.EVENT_BUS.unregister(callback);
    console.close();
    if (failure == null) result.complete(List.copyOf(observed));
    else result.completeExceptionally(failure);
  }
}
