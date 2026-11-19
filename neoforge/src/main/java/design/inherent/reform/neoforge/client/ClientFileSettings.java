package design.inherent.reform.neoforge.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.importing.ImportFormat;
import design.inherent.reform.importing.ImportSource;
import design.inherent.reform.importing.JsonImportFormat;
import design.inherent.reform.neoforge.command.ImportCatalog;
import design.inherent.reform.neoforge.command.SettingsCommands;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.owner.ApplicationEffects;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.FileOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.SettingsHandle;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.reference.ReferenceActions;
import design.inherent.reform.reference.ReferenceLimits;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.PropertiesStore;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.storage.TextStore;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Owned client text files share one lifecycle; native/world owners retain their actual adapters.
 */
public final class ClientFileSettings<T> {
  public enum Lifecycle {
    REGISTERED,
    INITIALIZING,
    READY,
    UNAVAILABLE,
    CLOSED
  }

  /** Registration state wraps the actual runtime view; it never supplies substitute defaults. */
  public record Status<T>(
      Lifecycle lifecycle,
      Optional<SettingsHandle.View<T>> effective,
      ValidationReport report,
      Optional<FileOwner.Receipt<T>> initializationReceipt) {}

  private record State<T>(
      Lifecycle lifecycle,
      FileOwner<T> owner,
      OwnerContext context,
      OwnerContext.Access access,
      ValidationReport report,
      FileOwner.Receipt<T> receipt) {}

  private record Startup<T>(Optional<Candidate<T>> selected) {}

  private static final JsonLimits JSON = new JsonLimits(262144, 64, 16384, 32768, 128, 4096);

  private final ModContainer container;
  private final DocumentDefinition<T> definition;
  private final TextStore<T> store;
  private final ApplicationEffects applicationEffects;
  private final Optional<Startup<T>> startup;
  private final AtomicReference<State<T>> state =
      new AtomicReference<>(
          new State<>(Lifecycle.REGISTERED, null, null, null, ValidationReport.VALID, null));
  private final CompletableFuture<Status<T>> initialization = new CompletableFuture<>();
  private final AtomicReference<CompletableFuture<Status<T>>> activeInitialization =
      new AtomicReference<>();
  private DocumentActions<T> commandActions;
  private Consumer<RegisterClientCommandsEvent> commandRegistration;

  private ClientFileSettings(
      ModContainer container,
      TextStore<T> store,
      ApplicationEffects applicationEffects,
      Optional<Startup<T>> startup) {
    this.container = container;
    this.definition = store.definition();
    this.store = store;
    this.applicationEffects = Objects.requireNonNull(applicationEffects);
    this.startup = Objects.requireNonNull(startup);
  }

  /** Call from the owning physical-client mod's constructor, before FML client setup. No IO. */
  public static <T> ClientFileSettings<T> clientJson(
      IEventBus modBus, ModContainer container, DocumentDefinition<T> definition) {
    return clientJson(modBus, container, definition, ApplicationEffects.OWNER_PUBLICATION);
  }

  /** Explicit immutable application metadata; it does not change persistence or add callbacks. */
  public static <T> ClientFileSettings<T> clientJson(
      IEventBus modBus,
      ModContainer container,
      DocumentDefinition<T> definition,
      ApplicationEffects applicationEffects) {
    return clientFile(
        modBus,
        container,
        new JsonStore<>(
            definition,
            new OwnedFile(
                StorageBinding.newJson(FMLPaths.CONFIGDIR.get().toAbsolutePath(), definition.id()),
                JSON.maximumBytes()),
            JsonStore.Format.NEW_ENVELOPE,
            JSON,
            DraftLimits.CONFIGURATION),
        applicationEffects);
  }

  /** An existing owned JSON source retains its explicit filename and representation. No IO. */
  public static <T> ClientFileSettings<T> existingJson(
      IEventBus modBus,
      ModContainer container,
      DocumentDefinition<T> definition,
      JsonStore.Format format,
      String... location) {
    return clientFile(
        modBus,
        container,
        new JsonStore<>(
            definition,
            new OwnedFile(configurationFile(location), JSON.maximumBytes()),
            format,
            JSON,
            DraftLimits.CONFIGURATION));
  }

  /** Native-semantics UTF-8 properties with the supplied flat definition and filename. No IO. */
  public static <T> ClientFileSettings<T> properties(
      IEventBus modBus,
      ModContainer container,
      DocumentDefinition<T> definition,
      String... location) {
    return clientFile(modBus, container, propertiesStore(definition, location));
  }

  /** Selected startup inputs must be the consumer's actual immutable bootstrap selection. */
  public static <T> ClientFileSettings<T> startupProperties(
      IEventBus modBus,
      ModContainer container,
      DocumentDefinition<T> definition,
      Optional<Candidate<T>> selected,
      String... location) {
    return startupFile(modBus, container, propertiesStore(definition, location), selected);
  }

  /** Save next-start choices; absent actual selection stays unknown, never inferred from disk. */
  public static <T> ClientFileSettings<T> startupFile(
      IEventBus modBus,
      ModContainer container,
      TextStore<T> store,
      Optional<Candidate<T>> selected) {
    Objects.requireNonNull(store);
    Objects.requireNonNull(selected);
    if (selected.isPresent() && selected.orElseThrow().definition() != store.definition())
      throw new IllegalArgumentException("Selected startup inputs require the actual definition");
    return register(
        modBus,
        container,
        store,
        ApplicationEffects.UNDECLARED,
        Optional.of(new Startup<>(selected)));
  }

  private static <T> PropertiesStore<T> propertiesStore(
      DocumentDefinition<T> definition, String... location) {
    return new PropertiesStore<>(
        definition,
        new OwnedFile(configurationFile(location), JSON.maximumBytes()),
        JSON,
        DraftLimits.CONFIGURATION);
  }

  /** Advanced owned-format binding; it supplies no parser, source defaults or path inference. */
  public static <T> ClientFileSettings<T> clientFile(
      IEventBus modBus, ModContainer container, TextStore<T> store) {
    return clientFile(modBus, container, store, ApplicationEffects.OWNER_PUBLICATION);
  }

  public static <T> ClientFileSettings<T> clientFile(
      IEventBus modBus,
      ModContainer container,
      TextStore<T> store,
      ApplicationEffects applicationEffects) {
    return register(modBus, container, store, applicationEffects, Optional.empty());
  }

  private static <T> ClientFileSettings<T> register(
      IEventBus modBus,
      ModContainer container,
      TextStore<T> store,
      ApplicationEffects applicationEffects,
      Optional<Startup<T>> startup) {
    Objects.requireNonNull(modBus);
    Objects.requireNonNull(container);
    Objects.requireNonNull(store);
    var definition = store.definition();
    if (FMLEnvironment.dist != Dist.CLIENT)
      throw new IllegalStateException("Client settings require physical-client registration");
    if (!container.getModId().equals(definition.id().namespace()))
      throw new IllegalArgumentException(
          "Client files must belong to their declaring mod namespace");
    var registration = new ClientFileSettings<>(container, store, applicationEffects, startup);
    ClientSettingsRuntime.registerFile(
        definition.id(), registration, store.binding(), registration::close);
    try {
      modBus.addListener(registration::setup);
    } catch (RuntimeException rejected) {
      ClientSettingsRuntime.unregisterDocument(definition.id(), registration);
      throw rejected;
    }
    return registration;
  }

  private static StorageBinding configurationFile(String... location) {
    return StorageBinding.relative(FMLPaths.CONFIGDIR.get().toAbsolutePath(), location);
  }

  public DocumentDefinition<T> definition() {
    return definition;
  }

  public Status<T> status() {
    var current = state.get();
    return new Status<>(
        current.lifecycle(),
        current.owner() == null ? Optional.empty() : Optional.of(current.owner().handle().view()),
        current.report(),
        Optional.ofNullable(current.receipt()));
  }

  /**
   * First admitted initialization attempt, including unavailable/closed outcomes. Never block a
   * native thread.
   */
  public CompletionStage<Status<T>> initialization() {
    return initialization.minimalCompletionStage();
  }

  /**
   * Read the actual published handle only after the native owner exists. No IO or fallback value.
   */
  public Optional<SettingsHandle<T>> runtime() {
    return Optional.ofNullable(state.get().owner()).map(FileOwner::handle);
  }

  /** Explicit native menu registration; an existing owner factory is never replaced. */
  public void registerMenu(Component title) {
    registerMenu(title, FormPresentation.EMPTY);
  }

  public void registerMenu(Component title, FormPresentation presentation) {
    Objects.requireNonNull(title);
    Objects.requireNonNull(presentation).requireCompatible(definition.type());
    if (container.getCustomExtension(IConfigScreenFactory.class).isPresent())
      throw new IllegalStateException("This mod already declares a configuration screen");
    IConfigScreenFactory factory = (ignored, parent) -> screen(parent, title, presentation);
    container.registerExtensionPoint(IConfigScreenFactory.class, factory);
  }

  public Screen screen(Screen parent, Component title) {
    return screen(parent, title, Map.of());
  }

  public Screen screen(Screen parent, Component title, FormPresentation presentation) {
    return screen(parent, title, Map.of(), presentation);
  }

  /** Borrowed context-owned sources; no capture, lookup or source replacement occurs here. */
  public Screen screen(
      Screen parent,
      Component title,
      Map<DocumentId, ? extends ReferenceActions<String, ?>> sources) {
    return screen(parent, title, sources, FormPresentation.EMPTY);
  }

  public Screen screen(
      Screen parent,
      Component title,
      Map<DocumentId, ? extends ReferenceActions<String, ?>> sources,
      FormPresentation presentation) {
    requireClientThread();
    Objects.requireNonNull(presentation).requireCompatible(definition.type());
    var current = state.get();
    if (current.lifecycle() != Lifecycle.READY)
      return new ClientStatusScreen(
          parent,
          title,
          this::statusDisplay,
          () -> screen(parent, title, sources, presentation),
          Optional.of(this::retryInitialization));
    current.context().requireOwner();
    return ClientEditors.open(
        current.context(),
        current.access(),
        current.owner().open(current.access()),
        definition,
        store.valueLimits(),
        store.draftLimits(),
        actions ->
            new SettingsScreen<>(
                parent,
                title,
                startup.isPresent()
                    ? text(
                        "startup_context",
                        "Startup choices · saved changes require restarting the game")
                    : text("client_context", "Client preferences · applies to this installation"),
                definition,
                actions,
                sources,
                presentation,
                FieldEditors.builder(definition).build(),
                startup.isPresent()
                    ? new SettingsScreen.SubmissionLabels(
                        text("save_startup", "Save for restart"),
                        text("review_startup", "Save reviewed choices"),
                        text("close_startup", "Save for restart and close"))
                    : SettingsScreen.SubmissionLabels.standard(actions.commitKind())));
  }

  /** Explicit source registration on this ready owner; caller supplies capture/reload policy. */
  public <K, V> ReferenceActions<K, V> references(
      DocumentId source, ValueType<K> keys, SnapshotPolicy<V> values, ReferenceLimits limits) {
    requireClientThread();
    var current = state.get();
    if (current.lifecycle() != Lifecycle.READY)
      throw new PolicyRefusal("reference.context", "Client reference owner is not ready");
    return new ReferenceActions<>(
        current.context(), current.access(), source, keys, values, limits);
  }

  private DocumentActions<T> editingActions(State<T> current) {
    return ClientEditors.open(
        current.context(),
        current.access(),
        current.owner().open(current.access()),
        definition,
        store.valueLimits(),
        store.draftLimits(),
        actions -> actions);
  }

  /** Explicit local command entry. Server permissions and server settings are never inferred. */
  public LiteralArgumentBuilder<CommandSourceStack> commandBranch(
      String literal, Consumer<SettingsCommands.Reply> output) {
    return commandBranch(literal, Optional.empty(), output);
  }

  /** Explicit registered sources with the same value/envelope formats as the generated form. */
  public ImportCatalog<T> importCatalog(List<? extends ImportSource> sources) {
    return new ImportCatalog<>(
        definition,
        sources,
        JsonImportFormat.documentFormats(
            definition,
            store.valueLimits(),
            store.draftLimits(),
            ImportFormat.InvalidDraftPolicy.ALLOW_REPAIRABLE));
  }

  public LiteralArgumentBuilder<CommandSourceStack> commandBranch(
      String literal, ImportCatalog<T> imports, Consumer<SettingsCommands.Reply> output) {
    if (Objects.requireNonNull(imports).definition() != definition)
      throw new IllegalArgumentException("Command imports require the actual client definition");
    return commandBranch(literal, Optional.of(imports), output);
  }

  private LiteralArgumentBuilder<CommandSourceStack> commandBranch(
      String literal, Optional<ImportCatalog<T>> imports, Consumer<SettingsCommands.Reply> output) {
    Objects.requireNonNull(output);
    return ClientCommands.branch(
        literal,
        definition.id(),
        new ClientCommands.Owner<T>() {
          @Override
          public boolean ready() {
            return state.get().lifecycle() == Lifecycle.READY;
          }

          @Override
          public SettingsCommands.Target<T> target() {
            var current = state.get();
            if (commandActions == null) commandActions = editingActions(current);
            return new SettingsCommands.Target<>(current.context(), commandActions);
          }

          @Override
          public Optional<SettingsCommands.Target<T>> existing() {
            return commandActions == null
                ? Optional.empty()
                : Optional.of(new SettingsCommands.Target<>(state.get().context(), commandActions));
          }
        },
        imports,
        output);
  }

  /** Register once during client construction. Existing native command roots are never replaced. */
  public synchronized void registerCommands(String literal) {
    registerCommands(literal, Optional.empty());
  }

  public synchronized void registerCommands(String literal, ImportCatalog<T> imports) {
    if (Objects.requireNonNull(imports).definition() != definition)
      throw new IllegalArgumentException("Command imports require the actual client definition");
    registerCommands(literal, Optional.of(imports));
  }

  private void registerCommands(String literal, Optional<ImportCatalog<T>> imports) {
    if (commandRegistration != null || state.get().lifecycle() == Lifecycle.CLOSED)
      throw new IllegalStateException("Client command entry is already registered or closed");
    commandRegistration =
        ClientCommands.registerRoot(literal, commandBranch(literal, imports, ClientCommands::chat));
  }

  /**
   * Explicit recovery after a failed initialization; valid existing data is read, never defaulted
   * over.
   */
  public CompletionStage<Status<T>> retryInitialization() {
    requireClientThread();
    var current = state.get();
    if (current.lifecycle() != Lifecycle.UNAVAILABLE || current.context() == null)
      return CompletableFuture.completedStage(status());
    return initialize(current);
  }

  private void setup(FMLClientSetupEvent event) {
    event.enqueueWork(
        () -> {
          requireClientThread();
          if (state.get().lifecycle() != Lifecycle.REGISTERED) return;
          OwnerContext context = null;
          try {
            context =
                new OwnerContext(
                    Minecraft.getInstance()::execute,
                    ClientSettingsRuntime.workers(),
                    ClientFileSettings::requireClientThread,
                    OwnerContext.Limits.STANDARD);
            initialize(
                new State<>(
                    Lifecycle.REGISTERED,
                    null,
                    context,
                    context.access(() -> true, () -> true),
                    ValidationReport.VALID,
                    null));
          } catch (RuntimeException failed) {
            if (context != null) context.close();
            state.set(
                new State<>(
                    Lifecycle.UNAVAILABLE,
                    null,
                    null,
                    null,
                    failure(
                        "client.initialization", "Client settings initialization could not start"),
                    null));
            initialization.complete(status());
          }
        });
  }

  private CompletionStage<Status<T>> initialize(State<T> before) {
    var context = before.context();
    var access = before.access();
    var result = new CompletableFuture<Status<T>>();
    activeInitialization.set(result);
    state.set(
        new State<>(
            Lifecycle.INITIALIZING, before.owner(), context, access, ValidationReport.VALID, null));
    CompletionStage<FileOwner<T>> construction;
    try {
      construction =
          before.owner() != null
              ? CompletableFuture.completedStage(before.owner())
              : startup.isPresent()
                  ? FileOwner.startup(context, store, 16, startup.orElseThrow().selected())
                  : CompletableFuture.completedStage(
                      new FileOwner<>(context, store, 16, applicationEffects));
    } catch (RuntimeException failed) {
      construction = CompletableFuture.failedStage(failed);
    }
    construction.whenComplete(
        (owner, failed) ->
            context
                .onOwner(
                    () -> {
                      if (!currentAttempt(context, result)) {
                        result.complete(status());
                        return null;
                      }
                      if (failed != null) {
                        finishAttempt(
                            context,
                            result,
                            owner,
                            null,
                            problem(
                                failed,
                                "client.owner",
                                "The client settings owner could not be constructed"),
                            false);
                        return null;
                      }
                      var admitted = Objects.requireNonNull(owner);
                      state.set(
                          new State<>(
                              Lifecycle.INITIALIZING,
                              admitted,
                              context,
                              access,
                              ValidationReport.VALID,
                              null));
                      try {
                        initializeSource(context, result, admitted, access);
                      } catch (RuntimeException refused) {
                        finishAttempt(
                            context,
                            result,
                            admitted,
                            null,
                            problem(
                                refused,
                                "client.initialization",
                                "Client settings initialization could not start"),
                            false);
                      }
                      return null;
                    })
                .whenComplete(
                    (ignored, dispatchFailure) -> {
                      if (dispatchFailure != null)
                        finishAttempt(
                            context,
                            result,
                            owner,
                            null,
                            failure(
                                "client.observation",
                                "Client settings construction could not be observed"),
                            false);
                    }));
    return result.minimalCompletionStage();
  }

  private boolean currentAttempt(OwnerContext context, CompletableFuture<Status<T>> result) {
    var current = state.get();
    return activeInitialization.get() == result
        && current.context() == context
        && current.lifecycle() == Lifecycle.INITIALIZING;
  }

  private void initializeSource(
      OwnerContext context,
      CompletableFuture<Status<T>> result,
      FileOwner<T> owner,
      OwnerContext.Access access) {
    var session = owner.open(access);
    session
        .initialize()
        .whenComplete(
            (receipt, failed) -> {
              session.close();
              context
                  .onOwner(
                      () -> {
                        var report =
                            failed == null
                                ? receipt.persistenceReport().and(receipt.applicationReport())
                                : problem(
                                    failed,
                                    "client.initialization",
                                    "Client settings initialization did not complete");
                        boolean ready =
                            failed == null
                                && report.valid()
                                && receipt.application()
                                    == (startup.isPresent()
                                        ? CommitReceipt.Application.STAGED
                                        : CommitReceipt.Application.APPLIED);
                        finishAttempt(context, result, owner, receipt, report, ready);
                        return null;
                      })
                  .whenComplete(
                      (ignored, dispatchFailure) -> {
                        if (dispatchFailure != null)
                          finishAttempt(
                              context,
                              result,
                              owner,
                              receipt,
                              failure(
                                  "client.observation",
                                  "Client settings result could not be observed"),
                              false);
                      });
            });
  }

  private void finishAttempt(
      OwnerContext context,
      CompletableFuture<Status<T>> result,
      FileOwner<T> owner,
      FileOwner.Receipt<T> receipt,
      ValidationReport report,
      boolean ready) {
    var current = state.get();
    if (activeInitialization.get() == result
        && current.context() == context
        && current.lifecycle() == Lifecycle.INITIALIZING) {
      state.compareAndSet(
          current,
          new State<>(
              ready ? Lifecycle.READY : Lifecycle.UNAVAILABLE,
              owner,
              context,
              current.access(),
              report,
              receipt));
    }
    // Release this attempt before either completion observer can start an explicit retry.
    activeInitialization.compareAndSet(result, null);
    var observed = status();
    initialization.complete(observed);
    result.complete(observed);
  }

  private static ValidationReport problem(Throwable failed, String code, String fallback) {
    while (failed instanceof java.util.concurrent.CompletionException && failed.getCause() != null)
      failed = failed.getCause();
    return failed instanceof PolicyRefusal refused ? refused.report() : failure(code, fallback);
  }

  private void close() {
    requireClientThread();
    var current = state.get();
    if (current.lifecycle() == Lifecycle.CLOSED) return;
    try {
      if (current.context() != null) current.context().close();
    } finally {
      commandActions = null;
      state.set(
          new State<>(
              Lifecycle.CLOSED,
              current.owner(),
              null,
              null,
              failure("client.closed", "This client settings context is closed"),
              current.receipt()));
      var pending = activeInitialization.getAndSet(null);
      initialization.complete(status());
      if (pending != null) pending.complete(status());
      var listener = commandRegistration;
      commandRegistration = null;
      if (listener != null) NeoForge.EVENT_BUS.unregister(listener);
    }
  }

  private static void requireClientThread() {
    if (!Minecraft.getInstance().isSameThread())
      throw new IllegalStateException("Client settings require the native client thread");
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.settings." + key, fallback);
  }

  private static ValidationReport failure(String code, String fallback) {
    return new ValidationReport(
        List.of(Issue.error(code, design.inherent.reform.definition.FieldPath.ROOT, fallback)),
        false);
  }

  private ClientStatusScreen.State statusDisplay() {
    var current = status();
    var message =
        current.lifecycle() == Lifecycle.REGISTERED || current.lifecycle() == Lifecycle.INITIALIZING
            ? text("initializing", "Loading client preferences…")
            : ClientStatusScreen.reason(
                current.report(),
                Component.literal(
                    "Client preferences are unavailable; existing data has been retained."));
    return new ClientStatusScreen.State(
        current.lifecycle() == Lifecycle.READY,
        current.lifecycle() == Lifecycle.UNAVAILABLE && state.get().context() != null,
        message);
  }
}
