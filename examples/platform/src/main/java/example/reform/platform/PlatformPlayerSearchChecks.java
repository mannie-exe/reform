package example.reform.platform;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.minecraft.command.NativeCommandSupport;
import design.inherent.reform.minecraft.command.SearchCommands;
import design.inherent.reform.minecraft.command.SearchRecipient;
import design.inherent.reform.operation.SearchReport;
import design.inherent.reform.operation.WorkBudget;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;

/** Real player command packets, received Components and issued native navigation links. */
final class PlatformPlayerSearchChecks {
  private static State state;
  private static final SearchCommands<String> BRIDGE =
      new SearchCommands<>(
          "platformlocate",
          source ->
              Optional.ofNullable(state)
                  .filter(value -> value.server == source.getServer())
                  .map(value -> value.job));
  private static Check active;

  static LiteralArgumentBuilder<CommandSourceStack> tree() {
    return BRIDGE.branch();
  }

  static CompletionStage<List<String>> run(
      ClientRegistration registration, MinecraftServer server, UUID player) {
    if (active != null) throw new IllegalStateException("Player search check already active");
    active = new Check(server, player);
    active.subscription = registration.onTick(active::tick);
    return active.result.minimalCompletionStage();
  }

  static void received(Component message) {
    if (active != null) {
      if (!Minecraft.getInstance().isSameThread())
        throw new AssertionError("Player search feedback left the native client thread");
      if (active.messages.size() >= 64)
        throw new AssertionError("Player search feedback exceeded the fixture bound");
      active.messages.add(message);
    }
  }

  static void serverStopped(MinecraftServer server) {
    if (state != null && state.server == server) {
      if (state.recipient.current().isPresent())
        throw new AssertionError("Stopped search retained its actual player recipient");
      state.work.stop();
      state.work.releaseTickets();
      state = null;
    }
  }

  private static final class State {
    private final MinecraftServer server;
    private final SearchRecipient recipient;
    private final WorkBudget budget;
    private WorkBudget.Group work;
    private SearchReport<String> report;
    private SearchCommands.Job<String> job;

    private State(MinecraftServer server, UUID player) {
      this.server = server;
      recipient =
          SearchRecipient.capture(
              server.getPlayerList().getPlayer(player).createCommandSourceStack(), 2);
      Runnable owner =
          () -> {
            if (!server.isSameThread()) throw new AssertionError("Search left native server owner");
          };
      budget = new WorkBudget(2, owner);
      work = budget.group(2);
      report = new SearchReport<>(new SearchReport.Limits(4, 2, 8, 40), owner);
      select();
      observe(3, List.of("B", "C", "D"));
    }

    private void observe(long matches, List<String> rows) {
      report.observe(
          new SearchReport.Progress(matches, matches, 0, OptionalLong.empty()), matches, rows);
    }

    private void select() {
      var selected = report;
      job =
          new SearchCommands.Job<>(
              recipient,
              selected,
              work,
              row -> Component.literal("[native result " + row + "]"),
              () -> selected.finish(SearchReport.Outcome.CANCELLED, "Requested by operator"));
    }

    private UUID replace() {
      var old = report.id();
      Runnable owner =
          () -> {
            if (!server.isSameThread()) throw new AssertionError("Search left native server owner");
          };
      report = new SearchReport<>(new SearchReport.Limits(4, 2, 8, 40), owner);
      work = budget.group(2);
      select();
      return old;
    }
  }

  private static final class Check {
    private final MinecraftServer server;
    private final CompletableFuture<List<String>> result = new CompletableFuture<>();
    private final List<String> checks = new ArrayList<>();
    private final List<Component> messages = new ArrayList<>();
    private ClientRegistration.Subscription subscription;
    private CompletableFuture<?> pending;
    private Style next;
    private UUID old;
    private int stage, ticks;

    private Check(MinecraftServer server, UUID player) {
      this.server = server;
      pending =
          server.submit(
              () -> {
                state = new State(server, player);
                return null;
              });
    }

    private void check(String name, boolean passed) {
      if (!passed)
        throw new AssertionError(
            name + ": " + messages.stream().map(Component::getString).toList());
      checks.add(name);
    }

    private boolean received(String text) {
      return messages.stream().anyMatch(message -> message.getString().contains(text));
    }

    private void typed(String command) {
      messages.clear();
      NativeWorldProbe.typed(command);
    }

    private Style link(String label) {
      for (var message : messages) {
        var found =
            message.visit(
                (style, text) ->
                    text.equals(label) && style.getClickEvent() != null
                        ? Optional.of(style)
                        : Optional.<Style>empty(),
                Style.EMPTY);
        if (found.isPresent()) return found.orElseThrow();
      }
      return null;
    }

    private void tick() {
      try {
        if (++ticks > 600)
          throw new AssertionError(
              "Player search timed out at stage "
                  + stage
                  + "; messages="
                  + messages.stream().map(Component::getString).toList());
        if (!pending.isDone()) return;
        pending.join();
        switch (stage) {
          case 0 -> {
            typed("/platformlocate page");
            stage++;
          }
          case 1 -> {
            if (!received("[native result B]") || (next = link("Next")) == null) return;
            check("real typed search reaches actual client chat", received("[native result C]"));
            check("native page issues a clickable next link", next.getClickEvent() != null);
            pending =
                server.submit(
                    () -> {
                      state.observe(7, List.of("A", "B", "C", "D"));
                      return null;
                    });
            stage++;
          }
          case 2 -> {
            messages.clear();
            NativeWorldProbe.click(next);
            stage++;
          }
          case 3 -> {
            if (!received("Captured at earlier search progress") || !received("[native result D]"))
              return;
            check("issued native link preserves old ranking", !received("[native result C]"));
            typed("/platformlocate watch off");
            stage++;
          }
          case 4 -> {
            if (!received("Search updates paused")) return;
            pending =
                server.submit(
                    () -> {
                      state.observe(8, List.of("A", "B", "C", "D"));
                      if (BRIDGE.notifyMatches(state.job, 40))
                        throw new AssertionError("Paused player received notice");
                      return null;
                    });
            checks.add("actual player watch pauses native publication");
            stage++;
          }
          case 5 -> {
            typed("/platformlocate watch on");
            stage++;
          }
          case 6 -> {
            if (!received("Search updates enabled")) return;
            pending =
                server.submit(
                    () -> {
                      if (!BRIDGE.notifyMatches(state.job, 80))
                        throw new AssertionError("Resumed notice was lost");
                      return null;
                    });
            stage++;
          }
          case 7 -> {
            if (!received("8 new matches found")) return;
            check("resumed player receives coalesced native notice", true);
            messages.clear();
            pending =
                server.submit(
                    () -> {
                      var player = state.recipient.current().orElseThrow().getPlayer();
                      var restore = NativeWorldProbe.revokePermission(player);
                      try {
                        if (NativeCommandSupport.allowed(player.createCommandSourceStack(), 2))
                          throw new AssertionError("Player permission did not change");
                        state.observe(9, List.of("A", "B", "C", "D"));
                        if (BRIDGE.notifyMatches(state.job, 160))
                          throw new AssertionError("Revoked player received notice");
                        if (server
                                .getCommands()
                                .getDispatcher()
                                .execute("platformlocate status", player.createCommandSourceStack())
                            != 0) throw new AssertionError("Revoked player received status");
                      } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failed) {
                        throw new AssertionError("Native search branch disappeared", failed);
                      } finally {
                        restore.run();
                      }
                      if (!BRIDGE.notifyMatches(state.job, 161))
                        throw new AssertionError("Restored player lost pending notice");
                      return null;
                    });
            stage++;
          }
          case 8 -> {
            if (!received("unavailable to the current operator")
                || !received("One new match found")) return;
            check(
                "native permission revocation refuses status and retains pending notice",
                !received("[native result"));
            typed("/platformlocate cancel");
            stage++;
          }
          case 9 -> {
            if (!received("Search cancelled")) return;
            check(
                "typed native cancellation reports observed partial results",
                received("9 matches observed, 4 retained"));
            pending = server.submit(() -> state.replace());
            stage++;
          }
          case 10 -> {
            old = (UUID) pending.join();
            messages.clear();
            NativeWorldProbe.click(next);
            stage++;
          }
          case 11 -> {
            if (!received("unavailable to the current operator")) return;
            check(
                "old received page link cannot disclose successor rows",
                !received("[native result"));
            typed("/platformlocate cancel " + old);
            stage++;
          }
          case 12 -> {
            if (!received("unavailable to the current operator")) return;
            pending =
                server.submit(
                    () -> {
                      if (state.report.status().outcome() != SearchReport.Outcome.RUNNING
                          || state.report.status().cancellationRequested())
                        throw new AssertionError("Stale native cancel controlled successor");
                      return null;
                    });
            stage++;
          }
          case 13 -> {
            check("stale typed native cancel leaves successor running", true);
            subscription.close();
            active = null;
            result.complete(List.copyOf(checks));
          }
          default -> throw new AssertionError("Unknown player search stage");
        }
      } catch (Exception | AssertionError failed) {
        subscription.close();
        active = null;
        result.completeExceptionally(failed);
      }
    }
  }

  private PlatformPlayerSearchChecks() {}
}
