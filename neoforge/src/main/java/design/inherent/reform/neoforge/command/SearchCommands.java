package design.inherent.reform.neoforge.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import design.inherent.reform.operation.SearchReport;
import design.inherent.reform.operation.WorkBudget;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/** Native reporting for searches scheduled by a consumer. */
public final class SearchCommands<R> {
  public interface Binding<R> {
    /** Look up a retained current job without starting work or loading a chunk. */
    Optional<Job<R>> current(CommandSourceStack source);

    /** A domain retaining older jobs can resolve them by exact ID. */
    default Optional<Job<R>> retained(CommandSourceStack source, UUID id) {
      return current(source).filter(job -> job.report.id().equals(id));
    }
  }

  public static final class Job<R> {
    private final SearchRecipient recipient;
    private final SearchReport<R> report;
    private final WorkBudget.Group work;
    private final Function<R, Component> display;
    private final Runnable cancel;
    private boolean watching = true;

    public Job(
        SearchRecipient recipient,
        SearchReport<R> report,
        WorkBudget.Group work,
        Function<R, Component> display,
        Runnable cancel) {
      this.recipient = Objects.requireNonNull(recipient);
      this.report = Objects.requireNonNull(report);
      this.work = Objects.requireNonNull(work);
      this.display = Objects.requireNonNull(display);
      this.cancel = Objects.requireNonNull(cancel);
    }
  }

  private final String command;
  private final String literal;
  private final Binding<R> binding;

  /**
   * Path under the consumer's dispatcher, without a leading slash. The final word is the branch.
   */
  public SearchCommands(String commandPath, Binding<R> binding) {
    Objects.requireNonNull(commandPath);
    if (commandPath.length() > 128 || !commandPath.matches("[a-z0-9_.:-]+( [a-z0-9_.:-]+)*"))
      throw new IllegalArgumentException("Use an explicit native search command path");
    this.literal = commandPath.substring(commandPath.lastIndexOf(' ') + 1);
    this.command = "/" + commandPath;
    this.binding = Objects.requireNonNull(binding);
  }

  /** Consumer supplies search arguments, native admission and scheduling under this branch. */
  public LiteralArgumentBuilder<CommandSourceStack> branch() {
    var exactPage =
        Commands.argument("job", UuidArgument.uuid())
            .then(
                Commands.argument("revision", LongArgumentType.longArg(0))
                    .then(
                        Commands.argument("number", IntegerArgumentType.integer(1))
                            .executes(
                                ctx ->
                                    show(
                                        ctx.getSource(),
                                        new SearchReport.Cursor(
                                            UuidArgument.getUuid(ctx, "job"),
                                            LongArgumentType.getLong(ctx, "revision"),
                                            IntegerArgumentType.getInteger(ctx, "number"))))));
    var page =
        Commands.literal("page")
            .executes(ctx -> showCurrent(ctx.getSource(), 1))
            .then(
                Commands.argument("number", IntegerArgumentType.integer(1))
                    .executes(
                        ctx ->
                            showCurrent(
                                ctx.getSource(), IntegerArgumentType.getInteger(ctx, "number"))))
            .then(Commands.literal("at").then(exactPage));
    return Commands.literal(literal)
        .executes(ctx -> showCurrent(ctx.getSource(), 1))
        .then(
            Commands.literal("status")
                .executes(ctx -> status(ctx.getSource(), current(ctx.getSource())))
                .then(
                    Commands.argument("job", UuidArgument.uuid())
                        .executes(
                            ctx ->
                                status(
                                    ctx.getSource(),
                                    retained(ctx.getSource(), UuidArgument.getUuid(ctx, "job"))))))
        .then(page)
        .then(
            Commands.literal("cancel")
                .executes(ctx -> cancel(ctx.getSource(), current(ctx.getSource())))
                .then(
                    Commands.argument("job", UuidArgument.uuid())
                        .executes(
                            ctx ->
                                cancel(
                                    ctx.getSource(),
                                    retained(ctx.getSource(), UuidArgument.getUuid(ctx, "job"))))))
        .then(
            Commands.literal("watch")
                .then(Commands.literal("on").executes(ctx -> watch(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> watch(ctx.getSource(), false))));
  }

  /** Offer one coalesced notice to the currently admitted recipient on its native thread. */
  public boolean notifyMatches(Job<R> job, long tick) {
    if (!job.watching) return false;
    var recipient =
        job.recipient
            .current()
            .filter(
                source ->
                    retained(source, job.report.id()).filter(actual -> actual == job).isPresent());
    if (recipient.isEmpty()) return false;
    var notice = job.report.notice(tick);
    if (notice.isEmpty()) return false;
    var found = notice.orElseThrow();
    var count = found.firstPage().status().found();
    var message =
        found.newlyFound() == 1
            ? text("notice_one", "One new match found (%s observed).", count)
            : text("notice", "%s new matches found (%s observed).", found.newlyFound(), count);
    send(
        recipient.orElseThrow(),
        message
            .copy()
            .append(" ")
            .append(link(text("results", "Show results"), pageSuffix(found.firstPage().cursor()))));
    return true;
  }

  private Optional<Job<R>> current(CommandSourceStack source) {
    return binding.current(source).filter(job -> job.recipient.owns(source));
  }

  private Optional<Job<R>> retained(CommandSourceStack source, UUID id) {
    return binding
        .retained(source, id)
        .filter(job -> job.report.id().equals(id) && job.recipient.owns(source));
  }

  private int status(CommandSourceStack source, Optional<Job<R>> selected) {
    if (selected.isEmpty()) return unavailable(source);
    var job = selected.orElseThrow();
    var page = job.report.capture(1).orElseThrow();
    send(
        source,
        summary(page.status(), job.work.pending())
            .copy()
            .append(" ")
            .append(link(text("results", "Show results"), pageSuffix(page.cursor()))));
    return 1;
  }

  private int showCurrent(CommandSourceStack source, int number) {
    var job = current(source);
    return job.isEmpty()
        ? unavailable(source)
        : showPage(source, job.orElseThrow(), job.orElseThrow().report.capture(number));
  }

  private int show(CommandSourceStack source, SearchReport.Cursor cursor) {
    var job = retained(source, cursor.job());
    return job.isEmpty()
        ? unavailable(source)
        : showPage(source, job.orElseThrow(), job.orElseThrow().report.page(cursor));
  }

  private int showPage(
      CommandSourceStack source, Job<R> job, Optional<SearchReport.Page<R>> result) {
    if (result.isEmpty()) {
      source.sendFailure(
          text(
              "page_unavailable",
              "This results page has expired or is unavailable. Use status for current results."));
      return 0;
    }
    var page = result.orElseThrow();
    var lines = page.rows().stream().map(job.display).map(Component::copy).toList();
    var now = job.report.status();
    boolean earlier = now.revision() != page.status().revision();
    var header =
        summary(page.status(), earlier ? 0 : job.work.pending())
            .copy()
            .append(" ")
            .append(text("page", "Page %s of %s.", page.cursor().page(), page.pages()));
    if (earlier) header.append(" ").append(text("earlier", "Captured at earlier search progress."));
    send(source, header);
    lines.forEach(line -> send(source, line));
    var nav = Component.empty();
    page.previous()
        .ifPresent(
            cursor ->
                nav.append(link(text("previous", "Previous"), pageSuffix(cursor))).append(" "));
    page.next()
        .ifPresent(
            cursor -> nav.append(link(text("next", "Next"), pageSuffix(cursor))).append(" "));
    nav.append(link(text("status", "Status"), "status " + job.report.id()));
    if (!now.terminal() && !now.cancellationRequested())
      nav.append(" ").append(link(text("cancel", "Cancel"), "cancel " + job.report.id()));
    send(source, nav);
    return 1;
  }

  private int cancel(CommandSourceStack source, Optional<Job<R>> selected) {
    if (selected.isEmpty()) return unavailable(source);
    var job = selected.orElseThrow();
    if (!job.report.requestCancellation(job.report.id())) return status(source, selected);
    job.work.stop();
    job.cancel.run();
    return status(source, selected);
  }

  private int watch(CommandSourceStack source, boolean enabled) {
    var selected = current(source);
    if (selected.isEmpty()) return unavailable(source);
    selected.orElseThrow().watching = enabled;
    send(
        source,
        enabled
            ? text("watch_on", "Search updates enabled.")
            : text("watch_off", "Search updates paused."));
    return 1;
  }

  private static Component summary(SearchReport.Status status, int pending) {
    var outcome =
        switch (status.outcome()) {
          case RUNNING ->
              status.cancellationRequested()
                  ? text("stopping", "Cancellation requested.")
                  : text("running", "Search running.");
          case COMPLETE ->
              status.found() == 0
                  ? text("zero", "Search complete with no matches.")
                  : text("complete", "Search complete.");
          case PARTIAL -> text("partial", "Partial search.");
          case FAILED -> text("failed", "Search failed.");
          case CANCELLED -> text("cancelled", "Search cancelled.");
        };
    var progress = status.progress();
    var message = outcome.copy().append(" ");
    if (!status.reason().isEmpty()) message.append(status.reason()).append(" ");
    message.append(
        progress.total().isPresent()
            ? text(
                "known",
                "Checked %s of %s candidates (%s rejected).",
                progress.checked(),
                progress.total().getAsLong(),
                progress.rejected())
            : text(
                "unknown",
                "Checked %s of %s enumerated candidates (%s rejected). Total unknown.",
                progress.checked(),
                progress.enumerated(),
                progress.rejected()));
    message
        .append(" ")
        .append(
            status.rowsTruncated()
                ? text(
                    "truncated",
                    "%s matches observed, %s retained.",
                    status.found(),
                    status.retained())
                : text("found", "%s matches observed.", status.found()));
    if (pending == 1)
      message.append(" ").append(text("drain_one", "One request still outstanding."));
    else if (pending > 1)
      message.append(" ").append(text("drain", "%s requests still outstanding.", pending));
    return message;
  }

  private static int unavailable(CommandSourceStack source) {
    source.sendFailure(
        text("unavailable", "This search is unavailable to the current operator in this world."));
    return 0;
  }

  private static void send(CommandSourceStack source, Component message) {
    source.sendSuccess(() -> message, false);
  }

  private static String pageSuffix(SearchReport.Cursor cursor) {
    return "page at " + cursor.job() + " " + cursor.revision() + " " + cursor.page();
  }

  private Component link(Component label, String suffix) {
    return label
        .copy()
        .withStyle(
            style ->
                style
                    .withColor(ChatFormatting.YELLOW)
                    .withClickEvent(
                        new ClickEvent(ClickEvent.Action.RUN_COMMAND, command + " " + suffix)));
  }

  private static Component text(String key, String fallback, Object... args) {
    return Component.translatableWithFallback("reform.search." + key, fallback, args);
  }
}
