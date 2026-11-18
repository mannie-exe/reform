package design.inherent.reform.neoforge.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import design.inherent.reform.edit.ConflictReview;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.DocumentConflicts;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.PolicyRefusal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/** Native bounded manual choices over the same editor/review service used by the screen. */
final class ConflictCommands<S, T> {
  private final SettingsCommands.Bridge<S, T> bridge;

  ConflictCommands(SettingsCommands.Bridge<S, T> bridge) {
    this.bridge = bridge;
  }

  LiteralArgumentBuilder<S> tree() {
    var root =
        LiteralArgumentBuilder.<S>literal("conflict")
            .executes(command -> preview(command.getSource()));
    root.then(
        LiteralArgumentBuilder.<S>literal("preview")
            .executes(command -> preview(command.getSource())));
    var reviewed =
        RequiredArgumentBuilder.<S, UUID>argument("review", UuidArgument.uuid())
            .executes(
                command ->
                    review(command.getSource(), command.getArgument("review", UUID.class), 0));
    reviewed.then(
        RequiredArgumentBuilder.<S, Integer>argument("row", IntegerArgumentType.integer(1))
            .executes(
                command ->
                    review(
                        command.getSource(),
                        command.getArgument("review", UUID.class),
                        IntegerArgumentType.getInteger(command, "row"))));
    root.then(LiteralArgumentBuilder.<S>literal("review").then(reviewed));
    var row = RequiredArgumentBuilder.<S, Integer>argument("row", IntegerArgumentType.integer(1));
    for (String name : List.of("baseline", "observed", "draft", "clear"))
      row.then(
          LiteralArgumentBuilder.<S>literal(name)
              .executes(
                  command ->
                      choose(
                          command.getSource(),
                          command.getArgument("review", UUID.class),
                          IntegerArgumentType.getInteger(command, "row"),
                          name)));
    root.then(
        LiteralArgumentBuilder.<S>literal("choose")
            .then(
                RequiredArgumentBuilder.<S, UUID>argument("review", UuidArgument.uuid())
                    .then(row)));
    for (String name : List.of("resolve", "status", "cancel"))
      root.then(
          LiteralArgumentBuilder.<S>literal(name)
              .then(
                  RequiredArgumentBuilder.<S, UUID>argument("id", UuidArgument.uuid())
                      .executes(
                          command ->
                              control(
                                  command.getSource(),
                                  name,
                                  command.getArgument("id", UUID.class)))));
    return root;
  }

  private DocumentConflicts<T> active(
      S source, SettingsCommands.Target<T> target, DocumentConflicts<T> expected) {
    if (!bridge.current(source, target)) throw expired();
    var actual = target.actions().activeConflicts().orElseThrow(ConflictCommands::expired);
    if (expected != null && actual != expected) throw expired();
    if (!bridge.current(source, target)) throw expired();
    return actual;
  }

  private int preview(S source) {
    try {
      var target = bridge.target(source);
      var conflicts = target.actions().conflicts();
      return bridge.begin(
          source,
          target,
          ignored -> conflicts.preview(EntryPoint.COMMAND),
          null,
          () -> active(source, target, conflicts));
    } catch (RuntimeException refused) {
      bridge.error(source, refused);
      return 0;
    }
  }

  private DocumentConflicts.Preview selected(DocumentConflicts<T> conflicts, UUID id) {
    var result = conflicts.review(id).orElseThrow(ConflictCommands::expired);
    // This also checks actual READ authority and the exact current draft/observation.
    conflicts.choices(result);
    return result;
  }

  private static DocumentConflicts.Entry row(DocumentConflicts.Preview review, int number) {
    if (number < 1 || number > review.entries().size())
      throw new PolicyRefusal("conflict.field", "Select a numbered row in this exact review");
    return review.entries().get(number - 1);
  }

  private int review(S source, UUID id, int number) {
    try {
      var target = bridge.target(source);
      var conflicts = active(source, target, null);
      var selected = selected(conflicts, id);
      if (number != 0) row(selected, number);
      var reply =
          previewReply(
              bridge,
              SettingsCommands.Kind.STATUS,
              selected,
              target.actions(),
              Optional.empty(),
              number);
      active(source, target, conflicts);
      conflicts.choices(selected);
      bridge.deliver(source, reply);
      return 1;
    } catch (RuntimeException refused) {
      bridge.error(source, refused);
      return 0;
    }
  }

  private int choose(S source, UUID id, int number, String name) {
    try {
      var target = bridge.target(source);
      var conflicts = active(source, target, null);
      var selected = selected(conflicts, id);
      var entry = row(selected, number);
      var choice =
          switch (name) {
            case "baseline" -> Optional.of(ConflictReview.Choice.BASELINE);
            case "observed" -> Optional.of(ConflictReview.Choice.OBSERVED);
            case "draft" -> Optional.of(ConflictReview.Choice.DRAFT);
            case "clear" -> Optional.<ConflictReview.Choice>empty();
            default -> throw new IllegalArgumentException("Unknown conflict choice");
          };
      conflicts.choose(selected, entry.path(), choice);
      active(source, target, conflicts);
      bridge.deliver(
          source,
          previewReply(
              bridge,
              SettingsCommands.Kind.STATUS,
              selected,
              target.actions(),
              Optional.empty(),
              number));
      return 1;
    } catch (RuntimeException refused) {
      bridge.error(source, refused);
      return 0;
    }
  }

  private int control(S source, String action, UUID id) {
    try {
      var target = bridge.target(source);
      var conflicts = active(source, target, null);
      if (action.equals("resolve")) {
        var selected = selected(conflicts, id);
        return bridge.begin(
            source,
            target,
            ignored -> conflicts.resolve(EntryPoint.COMMAND, selected),
            null,
            () -> active(source, target, conflicts));
      }
      var handle =
          conflicts
              .operation(id)
              .orElseThrow(
                  () ->
                      new PolicyRefusal(
                          "conflict.operation",
                          "Operation is not retained in this conflict scope"));
      if (action.equals("status"))
        bridge.deliver(
            source,
            bridge.reply(SettingsCommands.Kind.STATUS, handle.snapshot(), target.actions(), null));
      else
        handle
            .requestCancellation()
            .whenComplete(
                (snapshot, failed) ->
                    target
                        .context()
                        .onOwner(
                            () -> {
                              if (bridge.current(source, target)) {
                                try {
                                  active(source, target, conflicts);
                                  if (failed != null) bridge.error(source, failed);
                                  else
                                    bridge.deliver(
                                        source,
                                        bridge.reply(
                                            SettingsCommands.Kind.CANCELLATION,
                                            snapshot,
                                            target.actions(),
                                            null));
                                } catch (RuntimeException refused) {
                                  bridge.error(source, refused);
                                }
                              }
                              return null;
                            }));
      return 1;
    } catch (RuntimeException refused) {
      bridge.error(source, refused);
      return 0;
    }
  }

  static <S, T> SettingsCommands.Reply previewReply(
      SettingsCommands.Bridge<S, T> bridge,
      SettingsCommands.Kind kind,
      DocumentConflicts.Preview preview,
      DocumentActions<T> actions,
      Optional<OperationSnapshot<?>> operation,
      int selectedRow) {
    var conflicts = actions.activeConflicts().orElseThrow(ConflictCommands::expired);
    var choices = conflicts.choices(preview);
    var text =
        new StringBuilder("Conflict review ")
            .append(preview.id())
            .append("; ")
            .append(choices.size())
            .append("/")
            .append(preview.entries().size())
            .append(" explicit choices; resolve draft only; Save is separate")
            .append("\nBaseline: ")
            .append(preview.basis())
            .append(" ")
            .append(preview.baselineRevision())
            .append("\nObserved: ")
            .append(preview.observedRevision())
            .append("\nSource: ")
            .append(preview.source().description());
    for (int index = 0; index < preview.entries().size(); index++) {
      if (selectedRow != 0 && selectedRow != index + 1) continue;
      if (text.length() >= bridge.maximum) break;
      var entry = preview.entries().get(index);
      text.append("\n")
          .append(index + 1)
          .append(". ")
          .append(entry.selector().orElse(entry.path().toString()))
          .append(entry.wholeValue() ? " [whole value]" : "")
          .append("; choice=")
          .append(
              Optional.ofNullable(choices.get(entry.path())).map(Enum::name).orElse("UNCHOSEN"));
      if (selectedRow != 0)
        text.append("\nbaseline=")
            .append(entry.baseline())
            .append("\nobserved=")
            .append(entry.observed())
            .append("\ndraft=")
            .append(entry.draft());
    }
    var message =
        Component.literal(bridge.bounded(text.toString(), bridge.maximum))
            .withStyle(
                style ->
                    style.withClickEvent(
                        new ClickEvent(
                            ClickEvent.Action.COPY_TO_CLIPBOARD, preview.id().toString())));
    return new SettingsCommands.Reply(kind, SettingsCommands.Level.INFO, message, operation);
  }

  private static PolicyRefusal expired() {
    return new PolicyRefusal(
        "conflict.expired",
        "No current conflict review is retained in this editor; reload and preview it again");
  }
}
