package design.inherent.reform.neoforge.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.importing.ImportSource;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.DocumentImports;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.PolicyRefusal;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Registered-source presenter over the actual document import scope, not another editor. */
final class ImportCommands<S, T> {
  private final SettingsCommands.Bridge<S, T> bridge;

  ImportCommands(SettingsCommands.Bridge<S, T> bridge) {
    this.bridge = bridge;
  }

  LiteralArgumentBuilder<S> tree() {
    var root =
        LiteralArgumentBuilder.<S>literal("import")
            .requires(source -> bridge.available(source) && choices(source).isPresent())
            .executes(command -> list(command.getSource()));
    root.then(
        LiteralArgumentBuilder.<S>literal("list").executes(command -> list(command.getSource())));
    root.then(
        LiteralArgumentBuilder.<S>literal("preview")
            .then(
                RequiredArgumentBuilder.<S, ResourceLocation>argument(
                        "source", ResourceLocationArgument.id())
                    .suggests(
                        (command, suggestions) -> {
                          if (bridge.available(command.getSource()))
                            choices(command.getSource())
                                .ifPresent(
                                    catalog ->
                                        catalog.sources().stream()
                                            .map(choice -> choice.id().toString())
                                            .filter(id -> id.startsWith(suggestions.getRemaining()))
                                            .forEach(suggestions::suggest));
                          return suggestions.buildFuture();
                        })
                    .then(
                        RequiredArgumentBuilder.<S, ResourceLocation>argument(
                                "format", ResourceLocationArgument.id())
                            .suggests(
                                (command, suggestions) -> {
                                  if (bridge.available(command.getSource()))
                                    choices(command.getSource())
                                        .ifPresent(
                                            catalog ->
                                                catalog.formats().stream()
                                                    .map(choice -> choice.id().toString())
                                                    .filter(
                                                        id ->
                                                            id.startsWith(
                                                                suggestions.getRemaining()))
                                                    .forEach(suggestions::suggest));
                                  return suggestions.buildFuture();
                                })
                            .executes(
                                command ->
                                    preview(
                                        command.getSource(),
                                        command.getArgument("source", ResourceLocation.class),
                                        command.getArgument("format", ResourceLocation.class))))));
    for (String name : java.util.List.of("review", "stage", "status", "cancel"))
      root.then(
          LiteralArgumentBuilder.<S>literal(name)
              .then(
                  RequiredArgumentBuilder.<S, UUID>argument("id", UuidArgument.uuid())
                      .executes(
                          command ->
                              selected(
                                  command.getSource(),
                                  name,
                                  command.getArgument("id", UUID.class)))));
    return root;
  }

  private Optional<ImportCatalog<T>> choices(S source) {
    try {
      return bridge
          .binding
          .imports(source)
          .filter(catalog -> catalog.definition().id().equals(bridge.document));
    } catch (RuntimeException unavailable) {
      return Optional.empty();
    }
  }

  private ImportCatalog<T> catalog(S source) {
    if (!bridge.available(source)) throw expired();
    return choices(source).orElseThrow(ImportCommands::expired);
  }

  private void requireCatalog(
      S source, SettingsCommands.Target<T> target, ImportCatalog<T> catalog) {
    target.context().requireOwner();
    if (!bridge.current(source, target)
        || catalog(source) != catalog
        || target.actions().definition() != catalog.definition()) throw expired();
  }

  private int list(S source) {
    try {
      var catalog = catalog(source);
      var text = new StringBuilder("Registered import sources (preview does not save):");
      for (var choice : catalog.sources()) {
        if (text.length() >= bridge.maximum) break;
        text.append("\n").append(choice.id()).append(" — ").append(choice.label());
      }
      text.append("\nExplicit formats:");
      for (var choice : catalog.formats()) {
        if (text.length() >= bridge.maximum) break;
        text.append("\n").append(choice.id()).append(" — ").append(choice.label());
      }
      if (!bridge.available(source) || catalog(source) != catalog) throw expired();
      bridge.deliver(
          source,
          new SettingsCommands.Reply(
              SettingsCommands.Kind.STATUS,
              SettingsCommands.Level.INFO,
              Component.literal(bridge.bounded(text.toString(), bridge.maximum)),
              Optional.empty()));
      return 1;
    } catch (RuntimeException refused) {
      bridge.error(source, refused);
      return 0;
    }
  }

  private int preview(S source, ResourceLocation sourceName, ResourceLocation formatName) {
    try {
      var sourceId = DocumentId.parse(sourceName.toString());
      var format = DocumentId.parse(formatName.toString());
      var catalog = catalog(source);
      // Validate selections before opening/replacing a review, preserving an earlier valid ticket.
      var admitted = catalog.source(sourceId);
      if (admitted == null
          || catalog.formats().stream().noneMatch(choice -> choice.id().equals(format)))
        throw new PolicyRefusal("import.choice", "Select a registered source and explicit format");
      var target = bridge.target(source);
      requireCatalog(source, target, catalog);
      var selected = new RegisteredSource<>(bridge, source, target, catalog, admitted);
      selected.requireCurrent();
      var review = target.actions().imports(selected, catalog.parsers(), catalog.limits());
      return bridge.begin(
          source,
          target,
          ignored -> review.preview(EntryPoint.COMMAND, format),
          null,
          () -> requireReview(source, target, catalog, review));
    } catch (RuntimeException refused) {
      bridge.error(source, refused);
      return 0;
    }
  }

  private DocumentImports<T> requireReview(
      S source,
      SettingsCommands.Target<T> target,
      ImportCatalog<T> catalog,
      DocumentImports<T> expected) {
    requireCatalog(source, target, catalog);
    var review = target.actions().activeImports().orElseThrow(ImportCommands::expired);
    if (expected != null && review != expected) throw expired();
    if (!(review.admittedSource() instanceof RegisteredSource<?, ?> admitted)
        || admitted.catalog != catalog
        || admitted.target.actions() != target.actions()
        || admitted.target.context() != target.context()
        || admitted.bridge != bridge) throw expired();
    requireCatalog(source, target, catalog);
    if (target.actions().activeImports().orElse(null) != review) throw expired();
    return review;
  }

  private int selected(S source, String action, UUID id) {
    try {
      var catalog = catalog(source);
      var target = bridge.target(source);
      var review = requireReview(source, target, catalog, null);
      if (action.equals("status") || action.equals("cancel")) {
        var handle =
            review
                .operation(id)
                .orElseThrow(
                    () ->
                        new PolicyRefusal(
                            "import.operation", "Operation is not retained in this import review"));
        if (action.equals("status"))
          bridge.deliver(
              source,
              bridge.reply(
                  SettingsCommands.Kind.STATUS, handle.snapshot(), target.actions(), null));
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
                                    requireReview(source, target, catalog, review);
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
      } else {
        var selected =
            review
                .review(id)
                .orElseThrow(
                    () ->
                        new PolicyRefusal(
                            "import.expired", "The import review expired; preview it again"));
        if (action.equals("review"))
          bridge.deliver(
              source,
              previewReply(
                  bridge,
                  SettingsCommands.Kind.STATUS,
                  selected,
                  target.actions(),
                  Optional.empty()));
        else
          return bridge.begin(
              source,
              target,
              ignored -> review.stage(EntryPoint.COMMAND, selected),
              null,
              () -> requireReview(source, target, catalog, review));
      }
      return 1;
    } catch (RuntimeException refused) {
      bridge.error(source, refused);
      return 0;
    }
  }

  static <S, T> SettingsCommands.Reply previewReply(
      SettingsCommands.Bridge<S, T> bridge,
      SettingsCommands.Kind kind,
      DocumentImports.Preview preview,
      DocumentActions<T> actions,
      Optional<OperationSnapshot<?>> operation) {
    var text =
        new StringBuilder("Import review ")
            .append(preview.id())
            .append("; draft replacement only; Save is separate")
            .append("\nSource: ")
            .append(preview.source().id())
            .append(" — ")
            .append(preview.source().label())
            .append("; ")
            .append(preview.source().bytes())
            .append(" bytes; sha256=")
            .append(preview.source().sha256())
            .append("\nFormat: ")
            .append(preview.format().id())
            .append("; source schema: ")
            .append(
                preview.sourceSchemaVersion().isPresent()
                    ? Integer.toString(preview.sourceSchemaVersion().getAsInt())
                    : "unversioned")
            .append("\nTarget: ")
            .append(preview.target().document())
            .append("; draft ")
            .append(preview.target().version())
            .append("; current draft: ")
            .append(
                preview.currentValidation().valid()
                    ? "valid"
                    : bridge.first(preview.currentValidation()))
            .append("\nImported draft: ")
            .append(
                preview.importedValidation().valid()
                    ? "valid"
                    : bridge.first(preview.importedValidation()))
            .append("; stageable=")
            .append(preview.stageable())
            .append("; comparison=")
            .append(preview.comparisonAvailable() ? "available" : "unavailable")
            .append("; authored changes=")
            .append(preview.authoredChanges())
            .append("; input changes=")
            .append(preview.inputChanges());
    for (var change : preview.changes()) {
      if (text.length() >= bridge.maximum) break;
      text.append("\n")
          .append(
              actions
                  .fields()
                  .field(change.path())
                  .map(field -> field.selector())
                  .orElse(change.path().toString()))
          .append(": ")
          .append(bridge.shortText(change.beforeJson()))
          .append(" -> ")
          .append(bridge.shortText(change.afterJson()));
    }
    String copyLabel = " [copy review ID]";
    var message =
        Component.literal(bridge.bounded(text.toString(), bridge.maximum - copyLabel.length()))
            .append(
                Component.literal(copyLabel)
                    .withStyle(
                        style ->
                            style.withClickEvent(
                                new ClickEvent(
                                    ClickEvent.Action.COPY_TO_CLIPBOARD,
                                    preview.id().toString()))));
    return new SettingsCommands.Reply(
        kind,
        preview.currentValidation().valid()
                && preview.importedValidation().valid()
                && preview.stageable()
                && preview.comparisonAvailable()
            ? SettingsCommands.Level.INFO
            : SettingsCommands.Level.WARNING,
        message,
        operation);
  }

  private static PolicyRefusal expired() {
    return new PolicyRefusal(
        "import.admission", "The registered import context changed; preview it again");
  }

  private static final class RegisteredSource<S, T> implements ImportSource {
    private final SettingsCommands.Bridge<S, T> bridge;
    private final S nativeSource;
    private final SettingsCommands.Target<T> target;
    private final ImportCatalog<T> catalog;
    private final ImportSource delegate;
    private final DocumentId id;
    private final String label;
    private final int maximumBytes;

    RegisteredSource(
        SettingsCommands.Bridge<S, T> bridge,
        S nativeSource,
        SettingsCommands.Target<T> target,
        ImportCatalog<T> catalog,
        ImportSource delegate) {
      this.bridge = bridge;
      this.nativeSource = nativeSource;
      this.target = target;
      this.catalog = catalog;
      this.delegate = delegate;
      var descriptor =
          catalog.sources().stream()
              .filter(choice -> catalog.source(choice.id()) == delegate)
              .findFirst()
              .orElseThrow();
      id = descriptor.id();
      label = descriptor.label();
      maximumBytes = descriptor.maximumBytes();
    }

    @Override
    public DocumentId id() {
      return id;
    }

    @Override
    public String label() {
      return label;
    }

    @Override
    public int maximumBytes() {
      return maximumBytes;
    }

    private void check() {
      target.context().requireOwner();
      if (!bridge.current(nativeSource, target)
          || bridge.binding.imports(nativeSource).orElse(null) != catalog) throw expired();
    }

    @Override
    public void requireCurrent() {
      check();
      delegate.requireCurrent();
      check();
    }

    @Override
    public Capture capture() throws IOException {
      return delegate.capture();
    }
  }
}
