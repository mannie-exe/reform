package example.reform.platform;

import design.inherent.reform.minecraft.command.SearchCommands;
import design.inherent.reform.minecraft.command.SearchRecipient;
import design.inherent.reform.operation.SearchReport;
import design.inherent.reform.operation.WorkBudget;
import design.inherent.reform.owner.OwnerContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/** Independent locator provider with real Brigadier feedback, tickets and late native delivery. */
final class PlatformServerSearchChecks {
  static CompletionStage<List<String>> run(MinecraftServer server, OwnerContext owner) {
    var source = server.createCommandSourceStack().withPosition(new Vec3(7, 70, 8));
    var recipient = SearchRecipient.capture(source, 2);
    var level = recipient.level();
    Runnable nativeThread = owner::checkOwnerThread;
    var report = search(nativeThread);
    var budget = new WorkBudget(2, nativeThread);
    var oldGroup = budget.group(2);
    var otherGroup = budget.group(2);
    var job =
        new SearchCommands.Job<String>(
            recipient,
            report,
            oldGroup,
            row -> Component.literal("[result " + row + "]"),
            () -> {
              oldGroup.releaseTickets();
              report.finish(SearchReport.Outcome.CANCELLED, "Requested by operator");
            });
    var selected = new AtomicReference<>(job);
    var bridge =
        new SearchCommands<String>("reformplatform locate", ignored -> Optional.of(selected.get()));
    server
        .getCommands()
        .getDispatcher()
        .register(Commands.literal("reformplatform").then(bridge.branch()));
    var checks = new ArrayList<String>();

    var cache = level.getChunkSource();
    var tickets = new NativeSearchTickets(level);
    var chunk = tickets.chunk();
    var console = new PlatformConsoleCapture(server);
    var admission =
        Executors.newSingleThreadExecutor(
            task -> {
              var thread = new Thread(task, "reform-probe-native-search");
              thread.setDaemon(true);
              return thread;
            });
    var old = oldGroup.tryReserve().orElseThrow();
    var unrelated = otherGroup.tryReserve().orElseThrow();
    try {
      check(
          checks,
          "actual console is current and original origin survives a moved invocation",
          recipient.owns(source.withPosition(new Vec3(200, 75, 300)))
              && recipient.origin().equals(new Vec3(7, 70, 8)));
      check(
          checks,
          "moved feedback source and lowered permission cannot disclose rows",
          invoke(server, source.withSource(CommandSource.NULL), "status") == 0
              && invoke(server, NativeServerCommandProbe.lowered(source), "status") == 0);
      check(
          checks,
          "native status distinguishes unknown enumeration",
          invoke(server, source, "status") == 1 && console.find("Total unknown").isPresent());
      report.observe(scanned(3), 3, List.of("B", "C", "D"));
      var earlier = report.capture(2).orElseThrow().cursor();
      check(
          checks,
          "first native notice sends current count",
          bridge.notifyMatches(job, 1)
              && console.find("3 new matches found (3 observed)").isPresent());
      report.observe(scanned(7), 7, List.of("A", "B", "C", "D"));
      console.clear();
      check(
          checks,
          "rank changes leave older native page intact",
          invoke(server, source, page(earlier)) == 1
              && console.find("[result D]").isPresent()
              && console.find("[result C]").isEmpty()
              && console.find("Captured at earlier search progress").isPresent());
      console.clear();
      check(
          checks,
          "later native results coalesce across pause and resume",
          !bridge.notifyMatches(job, 2)
              && invoke(server, source, "watch off") == 1
              && !bridge.notifyMatches(job, 80)
              && invoke(server, source, "watch on") == 1
              && bridge.notifyMatches(job, 81)
              && console.find("4 new matches found (7 observed)").isPresent()
              && !bridge.notifyMatches(job, 200));

      // Scene setup: 26.3 no longer keeps the dedicated spawn chunk loaded without players.
      // The request under test below still uses create=false on this one prepared flat chunk.
      cache.getChunk(tickets.x(), tickets.z(), ChunkStatus.FULL, true);
      check(
          checks,
          "ticket fixture starts with an already loaded chunk and no probe tickets",
          cache.getChunkNow(tickets.x(), tickets.z()) != null
              && !tickets.live(0)
              && !tickets.live(1));
      old.attachTicket(tickets.acquire(0));
      unrelated.attachTicket(tickets.acquire(1));
      check(
          checks,
          "native ticket owners coexist on one loaded chunk",
          tickets.live(0) && tickets.live(1));
      var nativeFuture =
          CompletableFuture.supplyAsync(
                  () ->
                      cache.getChunkFuture(
                          tickets.x(), tickets.z(), ChunkStatus.STRUCTURE_STARTS, false),
                  admission)
              .thenCompose(stage -> stage);

      console.clear();
      check(
          checks,
          "cancel reports terminal partial results while one request still drains",
          invoke(server, source, "cancel " + report.id()) == 1
              && console.find("Search cancelled").isPresent()
              && console.find("7 matches observed, 4 retained").isPresent()
              && console.find("One request still outstanding").isPresent()
              && oldGroup.draining()
              && budget.inUse() == 2
              && !tickets.live(0)
              && tickets.live(1));
      var replacement = search(nativeThread);
      var replacementGroup = budget.group(2);
      selected.set(
          new SearchCommands.Job<>(
              recipient,
              replacement,
              replacementGroup,
              Component::literal,
              () -> {
                throw new AssertionError("Stale cancel targeted successor");
              }));
      check(
          checks,
          "old native page and cancel cannot control the successor",
          invoke(server, source, page(earlier)) == 0
              && invoke(server, source, "cancel " + report.id()) == 0
              && replacement.status().outcome() == SearchReport.Outcome.RUNNING
              && !replacement.status().cancellationRequested());
      check(
          checks,
          "successor cannot take outstanding native capacity",
          replacementGroup.tryReserve().isEmpty());

      return nativeFuture
          .handle(
              (value, failed) ->
                  owner.onOwner(
                      () -> {
                        try {
                          if (failed != null)
                            throw new AssertionError("Actual native future failed", failed);
                          var nativeChunk = value.orElse(null);
                          check(
                              checks,
                              "actual native STRUCTURE_STARTS completed at requested existing chunk",
                              nativeChunk != null && nativeChunk.getPos().equals(chunk));
                          old.close();
                          old.close();
                          check(
                              checks,
                              "late settlement clears the stopped job and releases one shared slot",
                              oldGroup.pending() == 0
                                  && !oldGroup.draining()
                                  && budget.inUse() == 1
                                  && otherGroup.pending() == 1
                                  && !tickets.live(0)
                                  && tickets.live(1));
                          var resumed = replacementGroup.tryReserve().orElseThrow();
                          try {
                            check(
                                checks,
                                "settled capacity is reusable while the unrelated request stays live",
                                budget.inUse() == 2);
                          } finally {
                            resumed.close();
                          }
                          unrelated.close();
                          check(
                              checks,
                              "unrelated ticket stays independent until its own settlement",
                              !tickets.live(0) && !tickets.live(1) && budget.inUse() == 0);
                          replacement.observe(
                              new SearchReport.Progress(0, 0, 0, OptionalLong.of(0)), 0, List.of());
                          replacement.finish(SearchReport.Outcome.COMPLETE, "");
                          console.clear();
                          check(
                              checks,
                              "complete native empty result is a successful page with explicit zero",
                              invoke(server, source, "page") == 1
                                  && console.find("Search complete with no matches").isPresent());
                          return List.copyOf(checks);
                        } finally {
                          old.close();
                          unrelated.close();
                          console.close();
                          admission.shutdown();
                        }
                      }))
          .thenCompose(stage -> stage);
    } catch (Exception | AssertionError failed) {
      old.close();
      unrelated.close();
      console.close();
      admission.shutdown();
      return CompletableFuture.failedStage(failed);
    }
  }

  private static SearchReport<String> search(Runnable thread) {
    return new SearchReport<>(new SearchReport.Limits(4, 2, 8, 40), thread);
  }

  private static SearchReport.Progress scanned(long count) {
    return new SearchReport.Progress(count, count, 0, OptionalLong.empty());
  }

  private static String page(SearchReport.Cursor cursor) {
    return "page at " + cursor.job() + " " + cursor.revision() + " " + cursor.page();
  }

  private static int invoke(MinecraftServer server, CommandSourceStack source, String command)
      throws com.mojang.brigadier.exceptions.CommandSyntaxException {
    return server.getCommands().getDispatcher().execute("reformplatform locate " + command, source);
  }

  private static void check(ArrayList<String> checks, String label, boolean passed) {
    if (!passed) throw new AssertionError(label);
    checks.add(label);
  }
}
