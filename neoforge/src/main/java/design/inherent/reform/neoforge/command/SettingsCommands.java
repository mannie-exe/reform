package design.inherent.reform.neoforge.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;

/**
 * Native Brigadier frontend for the same local actions used by generated screens. The binding
 * resolves real native actors/contexts, owns their lifetimes and delivers to the current recipient.
 * No file path, actor UUID, command string or client-supplied identity grants editing authority.
 */
public final class SettingsCommands {
  private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(SettingsCommands.class);

  public enum Level {
    INFO,
    WARNING,
    ERROR
  }

  public enum Kind {
    QUEUED,
    COMPLETED,
    STATUS,
    CANCELLATION,
    ERROR
  }

  /** Identity and response channel remain distinct when completion and cancellation overlap. */
  public record Correlation(UUID operationId, Kind responseKind) {
    public Correlation {
      Objects.requireNonNull(operationId);
      if (Objects.requireNonNull(responseKind) == Kind.ERROR)
        throw new IllegalArgumentException("Select the original response channel, not ERROR");
    }
  }

  /** An admitted identity survives a refused result-disclosure guard without exposing its data. */
  public record Reply(
      Kind kind,
      Level level,
      Component message,
      Optional<OperationSnapshot<?>> operation,
      Optional<Correlation> correlation) {
    public Reply {
      Objects.requireNonNull(kind);
      Objects.requireNonNull(level);
      Objects.requireNonNull(message);
      Objects.requireNonNull(operation);
      Objects.requireNonNull(correlation);
      if (operation.isPresent()
          && !correlation.equals(operation.map(snapshot -> new Correlation(snapshot.id(), kind))))
        throw new IllegalArgumentException("Feedback must retain its actual operation identity");
      if (kind != Kind.ERROR
          && correlation.isPresent()
          && correlation.orElseThrow().responseKind() != kind)
        throw new IllegalArgumentException("Feedback must retain its actual response channel");
    }

    public Reply(
        Kind kind, Level level, Component message, Optional<OperationSnapshot<?>> operation) {
      this(
          kind,
          level,
          message,
          operation,
          operation.map(snapshot -> new Correlation(snapshot.id(), kind)));
    }

    public Optional<UUID> operationId() {
      return correlation.map(Correlation::operationId);
    }
  }

  public record Target<T>(OwnerContext context, DocumentActions<T> actions) {
    public Target {
      Objects.requireNonNull(context);
      Objects.requireNonNull(actions);
      if (!context.id().equals(actions.context()))
        throw new IllegalArgumentException("Command target context does not match its actions");
    }
  }

  public interface Binding<S, T> {
    /** Native, current availability/actor check. This must not load files or create sessions. */
    boolean available(S source);

    /** Resolve/open the actual admitted actor's local editor only for command execution. */
    Target<T> target(S source);

    /** Read-only lookup for suggestions and late delivery; it must not create another session. */
    Optional<Target<T>> existing(S source);

    /**
     * Visible registered import metadata only; no session creation, capture or IO. Retain the same
     * catalog instance while admission is unchanged. Replacement invalidates earlier reviews.
     */
    default Optional<ImportCatalog<T>> imports(S source) {
      return Optional.empty();
    }

    /** Called on the target's native executor, after current availability/context checks. */
    void reply(S source, Reply reply);
  }

  public static <S, T> LiteralArgumentBuilder<S> branch(
      String literal, DocumentId document, Binding<S, T> binding, int maximumMessageCharacters) {
    if (!literal.matches("[a-z0-9_.-]+")
        || maximumMessageCharacters < 128
        || maximumMessageCharacters > 32768)
      throw new IllegalArgumentException("Use a native literal and bounded feedback size");
    return new Bridge<>(document, binding, maximumMessageCharacters).tree(literal);
  }

  static final class Bridge<S, T> {
    final DocumentId document;
    final Binding<S, T> binding;
    final int maximum;

    Bridge(DocumentId document, Binding<S, T> binding, int maximum) {
      this.document = Objects.requireNonNull(document);
      this.binding = Objects.requireNonNull(binding);
      this.maximum = maximum;
    }

    LiteralArgumentBuilder<S> tree(String name) {
      var root =
          LiteralArgumentBuilder.<S>literal(name)
              .requires(this::available)
              .executes(
                  command ->
                      run(
                          command.getSource(),
                          actions -> actions.describe(EntryPoint.COMMAND),
                          null));
      root.then(command("describe", actions -> actions.describe(EntryPoint.COMMAND)));
      root.then(
          LiteralArgumentBuilder.<S>literal("get")
              .executes(
                  command ->
                      run(
                          command.getSource(),
                          actions -> actions.explain(EntryPoint.COMMAND),
                          null))
              .then(
                  RequiredArgumentBuilder.<S, String>argument("field", StringArgumentType.string())
                      .suggests(
                          (command, suggestions) -> {
                            if (available(command.getSource()))
                              binding
                                  .existing(command.getSource())
                                  .ifPresent(
                                      target -> {
                                        if (target.actions().definition().id().equals(document))
                                          target.actions().fields().selectors().keySet().stream()
                                              .filter(
                                                  selector ->
                                                      matches(selector, suggestions.getRemaining()))
                                              .forEach(
                                                  selector ->
                                                      suggestions.suggest(
                                                          StringArgumentType.escapeIfRequired(
                                                              selector)));
                                      });
                            return suggestions.buildFuture();
                          })
                      .executes(
                          command ->
                              run(
                                  command.getSource(),
                                  actions -> actions.explain(EntryPoint.COMMAND),
                                  StringArgumentType.getString(command, "field")))));
      root.then(
          LiteralArgumentBuilder.<S>literal("set")
              .then(
                  RequiredArgumentBuilder.<S, String>argument("field", StringArgumentType.string())
                      .suggests(
                          (command, suggestions) -> {
                            if (available(command.getSource()))
                              binding
                                  .existing(command.getSource())
                                  .ifPresent(
                                      target -> {
                                        if (target.actions().definition().id().equals(document))
                                          target.actions().fields().fields().stream()
                                              .filter(field -> field.scalar())
                                              .filter(
                                                  field ->
                                                      matches(
                                                          field.selector(),
                                                          suggestions.getRemaining()))
                                              .forEach(
                                                  field ->
                                                      suggestions.suggest(
                                                          StringArgumentType.escapeIfRequired(
                                                              field.selector())));
                                      });
                            return suggestions.buildFuture();
                          })
                      .then(
                          RequiredArgumentBuilder.<S, String>argument(
                                  "value", StringArgumentType.string())
                              .executes(
                                  command ->
                                      run(
                                          command.getSource(),
                                          actions ->
                                              actions.edit(
                                                  EntryPoint.COMMAND,
                                                  StringArgumentType.getString(command, "field"),
                                                  StringArgumentType.getString(command, "value")),
                                          null)))));
      root.then(command("reload", actions -> actions.load(EntryPoint.COMMAND)));
      root.then(command("undo", actions -> actions.undo(EntryPoint.COMMAND)));
      root.then(command("redo", actions -> actions.redo(EntryPoint.COMMAND)));
      root.then(command("revert", actions -> actions.revert(EntryPoint.COMMAND)));
      root.then(command("defaults", actions -> actions.defaults(EntryPoint.COMMAND)));
      root.then(command("validate", actions -> actions.validate(EntryPoint.COMMAND)));
      root.then(command("compare", actions -> actions.compare(EntryPoint.COMMAND)));
      root.then(command("explain", actions -> actions.explain(EntryPoint.COMMAND)));
      root.then(command("export", actions -> actions.export(EntryPoint.COMMAND)));
      root.then(command("save", actions -> actions.save(EntryPoint.COMMAND)));
      root.then(command("admit", actions -> actions.admit(EntryPoint.COMMAND)));
      root.then(command("apply", actions -> actions.applySaved(EntryPoint.COMMAND)));
      root.then(new ImportCommands<>(this).tree());
      root.then(new ConflictCommands<>(this).tree());
      for (String action : List.of("status", "cancel"))
        root.then(
            LiteralArgumentBuilder.<S>literal(action)
                .then(
                    RequiredArgumentBuilder.<S, String>argument(
                            "operation", StringArgumentType.word())
                        .executes(
                            command ->
                                control(
                                    command.getSource(),
                                    StringArgumentType.getString(command, "operation"),
                                    action.equals("cancel")))));
      return root;
    }

    <O> LiteralArgumentBuilder<S> command(
        String name, Function<DocumentActions<T>, OperationHandle<O>> action) {
      return LiteralArgumentBuilder.<S>literal(name)
          .executes(command -> run(command.getSource(), action, null));
    }

    Target<T> target(S source) {
      if (!available(source))
        throw new PolicyRefusal("owner.permission", "Current native permission is required");
      var target = Objects.requireNonNull(binding.target(source));
      target.context().requireOwner();
      if (!target.actions().definition().id().equals(document))
        throw new PolicyRefusal("owner.context", "Command target document changed");
      return target;
    }

    <O> int run(
        S source, Function<DocumentActions<T>, OperationHandle<O>> action, String selected) {
      try {
        return begin(source, target(source), action, selected);
      } catch (RuntimeException refused) {
        error(source, refused);
        return 0;
      }
    }

    <O> int begin(
        S source,
        Target<T> target,
        Function<DocumentActions<T>, OperationHandle<O>> action,
        String selected) {
      return begin(source, target, action, selected, () -> {});
    }

    <O> int begin(
        S source,
        Target<T> target,
        Function<DocumentActions<T>, OperationHandle<O>> action,
        String selected,
        Runnable beforeReply) {
      try {
        if (selected != null && target.actions().fields().field(selected).isEmpty())
          throw new PolicyRefusal("field.unavailable", "Select a declared field");
        var handle = action.apply(target.actions());
        var id = handle.snapshot().id();
        queued(source, handle);
        handle
            .completion()
            .whenComplete(
                (observed, failed) ->
                    target
                        .context()
                        .onOwner(
                            () -> {
                              if (current(source, target)) {
                                try {
                                  beforeReply.run();
                                  if (failed != null) error(source, failed, id);
                                  else
                                    deliver(
                                        source,
                                        reply(
                                            Kind.COMPLETED, observed, target.actions(), selected));
                                } catch (RuntimeException refused) {
                                  error(source, refused, id);
                                }
                              }
                              return null;
                            }));
        return 1; // Accepted async invocation, never a completed-effect count.
      } catch (RuntimeException refused) {
        error(source, refused);
        return 0;
      }
    }

    int control(S source, String identity, boolean cancel) {
      try {
        var target = target(source);
        final UUID id;
        try {
          id = UUID.fromString(identity);
        } catch (IllegalArgumentException invalid) {
          throw new PolicyRefusal("operation.identity", "Enter the operation UUID");
        }
        var handle =
            target
                .actions()
                .operation(id)
                .orElseThrow(
                    () ->
                        new PolicyRefusal(
                            "operation.unavailable",
                            "Operation is not retained in this actor scope"));
        if (cancel)
          handle
              .requestCancellation()
              .whenComplete(
                  (snapshot, failed) ->
                      target
                          .context()
                          .onOwner(
                              () -> {
                                if (current(source, target)) {
                                  if (failed != null) error(source, failed, id, Kind.CANCELLATION);
                                  else
                                    deliver(
                                        source,
                                        reply(Kind.CANCELLATION, snapshot, target.actions(), null));
                                }
                                return null;
                              }));
        else deliver(source, reply(Kind.STATUS, handle.snapshot(), target.actions(), null));
        return 1;
      } catch (RuntimeException refused) {
        error(source, refused);
        return 0;
      }
    }

    boolean current(S source, Target<T> target) {
      try {
        return available(source)
            && binding
                .existing(source)
                .filter(
                    actual ->
                        actual.context() == target.context()
                            && actual.actions() == target.actions())
                .isPresent();
      } catch (RuntimeException unavailable) {
        LOGGER.warn("Settings command recipient lookup failed for {}", document, unavailable);
        return false;
      }
    }

    boolean available(S source) {
      try {
        return binding.available(source);
      } catch (RuntimeException unavailable) {
        LOGGER.warn("Settings command availability failed for {}", document, unavailable);
        return false;
      }
    }

    void deliver(S source, Reply reply) {
      try {
        binding.reply(source, reply);
      } catch (RuntimeException failed) {
        // Delivery failure cannot undo or change acceptance of an already admitted invocation.
        LOGGER.warn("Settings command feedback failed for {}", document, failed);
      }
    }

    boolean matches(String selector, String remaining) {
      var escaped = StringArgumentType.escapeIfRequired(selector);
      return escaped.toLowerCase(Locale.ROOT).startsWith(remaining.toLowerCase(Locale.ROOT));
    }

    void queued(S source, OperationHandle<?> handle) {
      deliver(
          source,
          new Reply(
              Kind.QUEUED,
              Level.INFO,
              Component.literal(bounded(document.toString(), maximum - 46) + ": queued ")
                  .append(
                      Component.literal(handle.snapshot().id().toString())
                          .withStyle(
                              style ->
                                  style.withClickEvent(
                                      new ClickEvent(
                                          ClickEvent.Action.COPY_TO_CLIPBOARD,
                                          handle.snapshot().id().toString())))),
              Optional.of(handle.snapshot())));
    }

    Reply reply(
        Kind kind, OperationSnapshot<?> snapshot, DocumentActions<T> actions, String selected) {
      StringBuilder text = new StringBuilder(document + " · " + snapshot.state() + " · ");
      Level level =
          snapshot.state() == OperationSnapshot.State.FAILED
                  || snapshot.state() == OperationSnapshot.State.REFUSED
              ? Level.ERROR
              : Level.INFO;
      var result = snapshot.result().orElse(null);
      if (result instanceof design.inherent.reform.operation.DocumentImports.Preview preview)
        return ImportCommands.previewReply(this, kind, preview, actions, Optional.of(snapshot));
      if (result instanceof design.inherent.reform.operation.DocumentConflicts.Preview preview)
        return ConflictCommands.previewReply(
            this, kind, preview, actions, Optional.of(snapshot), 0);
      if (result
          instanceof design.inherent.reform.operation.DocumentConflicts.Resolution<?> resolved) {
        text.append("choices resolved in draft ")
            .append(resolved.resolved().snapshot().orElseThrow().version())
            .append("; Save is separate; validation=")
            .append(resolved.report().valid());
        if (!resolved.report().valid()) {
          level = Level.WARNING;
          text.append("; ").append(first(resolved.report()));
        }
      } else if (result instanceof EditorActor.Commit<?> saved) {
        if (saved.receipt().isPresent()) {
          var receipt = saved.receipt().orElseThrow();
          text.append("persistence=")
              .append(receipt.persistence())
              .append(", application=")
              .append(receipt.application());
          if (!receipt.persistenceReport().valid() || !receipt.applicationReport().valid())
            level = Level.WARNING;
        } else {
          text.append(first(saved.editorReport()));
          level = Level.WARNING;
        }
      } else if (result instanceof EditorActor.Validation validation) {
        text.append("draft ")
            .append(validation.version())
            .append(": ")
            .append(validation.report().valid() ? "valid" : first(validation.report()));
        if (!validation.report().valid()) level = Level.WARNING;
      } else if (result instanceof DocumentActions.Description description) {
        text.append(description.fields().size()).append(" declared fields: ");
        for (var field : description.fields()) {
          if (text.length() >= maximum) break;
          text.append(field.selector()).append(" ");
        }
      } else if (result instanceof DocumentActions.Comparison comparison) {
        text.append(comparison.changes().size())
            .append(" typed changes; authored changes=")
            .append(comparison.authoredChanges());
        if (!comparison.report().valid()) {
          level = Level.WARNING;
          text.append(" · ").append(first(comparison.report()));
        }
        for (var change : comparison.changes()) {
          if (text.length() >= maximum) break;
          text.append("\n")
              .append(
                  actions
                      .fields()
                      .field(change.path())
                      .map(field -> field.selector())
                      .orElse(change.path().toString()))
              .append(": ")
              .append(shortText(change.beforeJson()))
              .append(" -> ")
              .append(shortText(change.afterJson()));
        }
      } else if (result instanceof EditorActor.Inspection<?> inspection) {
        if (selected != null) {
          var field = actions.fields().field(selected).orElseThrow();
          var node = inspection.snapshot().draft().at(field.path());
          text.append("draft ")
              .append(selected)
              .append(" = ")
              .append(
                  node instanceof DraftNode.Scalar scalar
                      ? shortText(scalar.raw())
                      : "[structured/invalid value; inspect in GUI or export]");
        } else
          text.append("source=")
              .append(inspection.provenance().kind())
              .append("; draft=")
              .append(inspection.snapshot().version())
              .append("; baseline=")
              .append(inspection.snapshot().sourceRevision())
              .append("; effective=")
              .append(
                  inspection
                      .effective()
                      .map(view -> view.availability().name())
                      .orElse("not observed by owner"));
      } else if (result instanceof DocumentActions.ExportArtifact artifact) {
        text.append("in-memory document-value JSON: ")
            .append(artifact.size())
            .append(" bytes, sha256=")
            .append(artifact.sha256());
      } else if (result instanceof EditorActor.View<?> view) {
        text.append(view.availability())
            .append("; dirty=")
            .append(view.dirty())
            .append("; validation=")
            .append(view.validation().valid());
        if (!view.validation().valid()) level = Level.WARNING;
        if (snapshot.operation().equals(DocumentId.parse("reform:import/stage")))
          text.append("; draft only; Save is separate");
      } else if (!snapshot.report().issues().isEmpty()) text.append(first(snapshot.report()));
      else
        text.append(
            snapshot.started()
                ? "Provider entered; review its actual effect result"
                : "Provider not entered");
      boolean copy =
          result instanceof DocumentActions.ExportArtifact artifact
              && artifact.size() <= maximum / 2;
      String copyLabel = " [copy value JSON]";
      var message =
          Component.literal(bounded(text.toString(), maximum - (copy ? copyLabel.length() : 0)));
      if (copy && result instanceof DocumentActions.ExportArtifact artifact)
        message.append(
            Component.literal(copyLabel)
                .withStyle(
                    style ->
                        style.withClickEvent(
                            new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, artifact.text()))));
      return new Reply(kind, level, message, Optional.of(snapshot));
    }

    String bounded(String text, int limit) {
      if (text.length() <= limit) return text;
      String suffix = " [details truncated]";
      int end = limit - suffix.length();
      if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
      return text.substring(0, end) + suffix;
    }

    String shortText(String value) {
      return value.length() <= 512 ? value : value.substring(0, 492) + " [value truncated]";
    }

    String first(design.inherent.reform.validation.ValidationReport report) {
      return report.issues().isEmpty()
          ? "Required observation unavailable"
          : shortText(report.issues().getFirst().fallback());
    }

    void error(S source, Throwable failed) {
      error(source, failed, Optional.empty());
    }

    void error(S source, Throwable failed, UUID operation) {
      error(source, failed, operation, Kind.COMPLETED);
    }

    void error(S source, Throwable failed, UUID operation, Kind responseKind) {
      error(source, failed, Optional.of(new Correlation(operation, responseKind)));
    }

    private void error(S source, Throwable failed, Optional<Correlation> correlation) {
      while (failed instanceof CompletionException && failed.getCause() != null)
        failed = failed.getCause();
      String text =
          failed instanceof PolicyRefusal refused
              ? first(refused.report())
              : "Settings operation is unavailable";
      String prefix =
          correlation
              .map(
                  id -> {
                    String channel =
                        switch (id.responseKind()) {
                          case QUEUED -> "Admission";
                          case COMPLETED -> "Completion";
                          case STATUS -> "Status";
                          case CANCELLATION -> "Cancellation";
                          case ERROR ->
                              throw new IllegalArgumentException("Unknown original response");
                        };
                    return channel + " reply for " + id.operationId() + ": ";
                  })
              .orElse("");
      if (available(source))
        deliver(
            source,
            new Reply(
                Kind.ERROR,
                Level.ERROR,
                Component.literal(prefix + bounded(text, maximum - prefix.length())),
                Optional.empty(),
                correlation));
    }
  }

  private SettingsCommands() {}
}
