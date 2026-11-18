package design.inherent.reform.neoforge.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.neoforge.command.SettingsCommands;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationScope;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.JsonOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.SettingsHandle;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.reference.ReferenceActions;
import design.inherent.reform.reference.ReferenceLimits;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.ClientCommandSourceStack;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** New client JSON documents only; existing/native/world owners keep their actual bindings. */
public final class ClientJsonSettings<T> {
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
      Optional<JsonOwner.Receipt<T>> initializationReceipt) {}

  private record State<T>(
      Lifecycle lifecycle,
      JsonOwner<T> owner,
      OwnerContext context,
      OwnerContext.Access access,
      ValidationReport report,
      JsonOwner.Receipt<T> receipt) {}

  private static final JsonLimits JSON = new JsonLimits(262144, 64, 16384, 32768, 128, 4096);
  private static final Registry REGISTRY = new Registry();

  private final ModContainer container;
  private final DocumentDefinition<T> definition;
  private final StorageBinding binding;
  private final AtomicReference<State<T>> state =
      new AtomicReference<>(
          new State<>(Lifecycle.REGISTERED, null, null, null, ValidationReport.VALID, null));
  private final CompletableFuture<Status<T>> initialization = new CompletableFuture<>();
  private CompletableFuture<Status<T>> activeInitialization;
  private DocumentActions<T> commandActions;
  private Consumer<RegisterClientCommandsEvent> commandRegistration;

  private ClientJsonSettings(
      ModContainer container, DocumentDefinition<T> definition, StorageBinding binding) {
    this.container = container;
    this.definition = definition;
    this.binding = binding;
  }

  /** Call from the owning physical-client mod's constructor, before FML client setup. No IO. */
  public static <T> ClientJsonSettings<T> clientJson(
      IEventBus modBus, ModContainer container, DocumentDefinition<T> definition) {
    Objects.requireNonNull(modBus);
    Objects.requireNonNull(container);
    Objects.requireNonNull(definition);
    if (FMLEnvironment.dist != Dist.CLIENT)
      throw new IllegalStateException("Client settings require physical-client registration");
    if (!container.getModId().equals(definition.id().namespace()))
      throw new IllegalArgumentException(
          "New client JSON must belong to its declaring mod namespace");
    // The loader declares the root. Binding construction validates metadata and performs no IO.
    var binding =
        StorageBinding.newJson(FMLPaths.CONFIGDIR.get().toAbsolutePath(), definition.id());
    var registration = new ClientJsonSettings<>(container, definition, binding);
    REGISTRY.add(registration);
    try {
      modBus.addListener(registration::setup);
    } catch (RuntimeException rejected) {
      REGISTRY.remove(registration);
      throw rejected;
    }
    return registration;
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
    return Optional.ofNullable(state.get().owner()).map(JsonOwner::handle);
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
      return new StatusScreen(parent, title, sources, presentation);
    current.context().requireOwner();
    return new SettingsScreen<>(
        parent,
        title,
        text("client_context", "Client preferences · applies to this installation"),
        definition,
        editingActions(current),
        sources,
        presentation);
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
    var editor =
        new EditorActor<>(
            current.context(),
            current.owner().open(current.access()),
            definition,
            JSON,
            DraftLimits.CONFIGURATION,
            64);
    return new DocumentActions<>(
        current.context(),
        current.access(),
        editor,
        new OperationScope.Limits(32, 64, 128, OperationScope.Limits.STANDARD.input()),
        JSON.maximumBytes());
  }

  /** Explicit local command entry. Server permissions and server settings are never inferred. */
  public LiteralArgumentBuilder<CommandSourceStack> commandBranch(
      String literal, Consumer<SettingsCommands.Reply> output) {
    Objects.requireNonNull(output);
    var owner = this;
    return SettingsCommands.branch(
        literal,
        definition.id(),
        new SettingsCommands.Binding<CommandSourceStack, T>() {
          @Override
          public boolean available(CommandSourceStack source) {
            return source instanceof ClientCommandSourceStack
                && Minecraft.getInstance().isSameThread()
                && source.getEntity() == Minecraft.getInstance().player
                && state.get().lifecycle() == Lifecycle.READY;
          }

          @Override
          public SettingsCommands.Target<T> target(CommandSourceStack source) {
            requireClientThread();
            var current = state.get();
            if (!available(source))
              throw new design.inherent.reform.owner.PolicyRefusal(
                  "owner.unavailable", "Client settings are not ready");
            if (commandActions == null) commandActions = editingActions(current);
            return new SettingsCommands.Target<>(current.context(), commandActions);
          }

          @Override
          public Optional<SettingsCommands.Target<T>> existing(CommandSourceStack source) {
            if (!available(source) || owner.commandActions == null) return Optional.empty();
            return Optional.of(
                new SettingsCommands.Target<>(state.get().context(), owner.commandActions));
          }

          @Override
          public void reply(CommandSourceStack source, SettingsCommands.Reply reply) {
            output.accept(reply);
          }
        },
        8192);
  }

  /** Register once during client construction. Existing native command roots are never replaced. */
  public synchronized void registerCommands(String literal) {
    if (commandRegistration != null || state.get().lifecycle() == Lifecycle.CLOSED)
      throw new IllegalStateException("Client command entry is already registered or closed");
    var branch =
        commandBranch(
            literal,
            reply -> {
              var client = Minecraft.getInstance();
              if (client.player != null)
                client.player.sendSystemMessage(
                    reply
                        .message()
                        .copy()
                        .withStyle(
                            reply.level() == SettingsCommands.Level.ERROR
                                ? ChatFormatting.RED
                                : reply.level() == SettingsCommands.Level.WARNING
                                    ? ChatFormatting.YELLOW
                                    : ChatFormatting.GRAY));
            });
    commandRegistration =
        event -> {
          if (event.getDispatcher().getRoot().getChild(literal) != null)
            throw new IllegalStateException("Client command root is already owned: " + literal);
          event.getDispatcher().register(branch);
        };
    NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, commandRegistration);
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
          var minecraft = Minecraft.getInstance();
          OwnerContext context = null;
          try {
            context =
                new OwnerContext(
                    minecraft::execute,
                    REGISTRY.workers(),
                    ClientJsonSettings::requireClientThread,
                    OwnerContext.Limits.STANDARD);
            var store =
                new JsonStore<>(
                    definition,
                    new OwnedFile(binding, JSON.maximumBytes()),
                    JsonStore.Format.NEW_ENVELOPE,
                    JSON,
                    DraftLimits.CONFIGURATION);
            var owner = new JsonOwner<>(context, store, 16);
            var access = context.access(() -> true, () -> true);
            initialize(
                new State<>(
                    Lifecycle.REGISTERED, owner, context, access, ValidationReport.VALID, null));
          } catch (RuntimeException failure) {
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
    var active =
        new State<>(
            Lifecycle.INITIALIZING,
            before.owner(),
            before.context(),
            before.access(),
            ValidationReport.VALID,
            null);
    state.set(active);
    var result = new CompletableFuture<Status<T>>();
    activeInitialization = result;
    var session = active.owner().open(active.access());
    session
        .initialize()
        .whenComplete(
            (receipt, failed) -> {
              session.close();
              active
                  .context()
                  .onOwner(
                      () -> {
                        if (state.get() != active) {
                          return status();
                        }
                        var report =
                            failed == null
                                ? receipt.persistenceReport().and(receipt.applicationReport())
                                : failure(
                                    "client.initialization",
                                    "Client settings initialization did not complete");
                        boolean available =
                            failed == null
                                && receipt.application() == CommitReceipt.Application.APPLIED;
                        state.set(
                            new State<>(
                                available ? Lifecycle.READY : Lifecycle.UNAVAILABLE,
                                active.owner(),
                                active.context(),
                                active.access(),
                                report,
                                receipt));
                        var observed = status();
                        // A completion observer may immediately start another attempt.
                        activeInitialization = null;
                        initialization.complete(observed);
                        return observed;
                      })
                  .whenComplete(
                      (observed, dispatchFailure) -> {
                        if (dispatchFailure == null) result.complete(observed);
                        else if (active.context().isOpen()) {
                          state.compareAndSet(
                              active,
                              new State<>(
                                  Lifecycle.UNAVAILABLE,
                                  active.owner(),
                                  active.context(),
                                  active.access(),
                                  failure(
                                      "client.observation",
                                      "Client settings result could not be observed"),
                                  receipt));
                          var unavailable = status();
                          initialization.complete(unavailable);
                          result.complete(unavailable);
                        } else if (state.get().lifecycle() == Lifecycle.CLOSED)
                          result.complete(status());
                      });
            });
    return result.minimalCompletionStage();
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
      initialization.complete(status());
      if (activeInitialization != null) {
        activeInitialization.complete(status());
        activeInitialization = null;
      }
      var listener = commandRegistration;
      commandRegistration = null;
      if (listener != null) NeoForge.EVENT_BUS.unregister(listener);
    }
  }

  private static void requireClientThread() {
    if (!Minecraft.getInstance().isSameThread())
      throw new IllegalStateException("Client settings require the native client thread");
  }

  /** Borrow the same bounded physical-client pool without registering or initializing a file. */
  static java.util.concurrent.Executor workers() {
    return REGISTRY.workers();
  }

  static void manageContext(OwnerContext context) {
    REGISTRY.manage(context);
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.settings." + key, fallback);
  }

  private static ValidationReport failure(String code, String fallback) {
    return new ValidationReport(
        List.of(Issue.error(code, design.inherent.reform.definition.FieldPath.ROOT, fallback)),
        false);
  }

  private static final class Registry {
    private final Map<DocumentId, ClientJsonSettings<?>> documents = new LinkedHashMap<>();
    private final Map<java.util.UUID, OwnerContext> contexts = new LinkedHashMap<>();
    private ThreadPoolExecutor workers;
    private boolean closing;

    Registry() {
      NeoForge.EVENT_BUS.addListener(this::shutdown);
    }

    synchronized void add(ClientJsonSettings<?> settings) {
      if (closing) throw new IllegalStateException("Client settings registration is closed");
      if (documents.size() >= 128)
        throw new IllegalStateException("Client document registration limit reached");
      if (documents.putIfAbsent(settings.definition.id(), settings) != null)
        throw new IllegalArgumentException("This client document already has an owner");
    }

    synchronized void remove(ClientJsonSettings<?> settings) {
      documents.remove(settings.definition.id(), settings);
    }

    synchronized void manage(OwnerContext context) {
      requireClientThread();
      if (closing || contexts.size() >= 128)
        throw new IllegalStateException("Client context admission is closed or full");
      if (contexts.putIfAbsent(context.id(), context) != null)
        throw new IllegalArgumentException("Client context already managed");
      try {
        context.onClose(
            () -> {
              synchronized (this) {
                contexts.remove(context.id());
              }
            });
      } catch (RuntimeException failed) {
        contexts.remove(context.id());
        throw failed;
      }
    }

    synchronized ThreadPoolExecutor workers() {
      requireClientThread();
      if (closing) throw new IllegalStateException("Client settings workers are closed");
      if (workers == null) {
        var sequence = new AtomicInteger();
        workers =
            new ThreadPoolExecutor(
                2,
                2,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256),
                task -> {
                  var thread =
                      new Thread(task, "reform-client-settings-" + sequence.incrementAndGet());
                  thread.setDaemon(true);
                  return thread;
                });
        workers.allowCoreThreadTimeOut(true);
      }
      return workers;
    }

    private void shutdown(GameShuttingDownEvent event) {
      final List<ClientJsonSettings<?>> selected;
      final List<OwnerContext> selectedContexts;
      final ThreadPoolExecutor executor;
      synchronized (this) {
        closing = true;
        selected = List.copyOf(documents.values());
        documents.clear();
        selectedContexts = List.copyOf(contexts.values());
        contexts.clear();
        executor = workers;
        workers = null;
      }
      try {
        Throwable failure = null;
        for (var settings : selected) {
          try {
            settings.close();
          } catch (RuntimeException | Error failed) {
            if (failure == null) failure = failed;
            else if (failure != failed) failure.addSuppressed(failed);
          }
        }
        for (var context : selectedContexts) {
          try {
            context.close();
          } catch (RuntimeException | Error failed) {
            if (failure == null) failure = failed;
            else if (failure != failed) failure.addSuppressed(failed);
          }
        }
        if (failure instanceof RuntimeException failed) throw failed;
        if (failure instanceof Error failed) throw failed;
      } finally {
        if (executor != null) executor.shutdown();
      }
    }
  }

  private final class StatusScreen extends FormScreen {
    private final Screen parent;
    private final Map<DocumentId, ? extends ReferenceActions<String, ?>> sources;
    private final FormPresentation presentation;
    private boolean closed;
    private Status<T> displayed;
    private FormPanel diagnostics;

    StatusScreen(
        Screen parent,
        Component title,
        Map<DocumentId, ? extends ReferenceActions<String, ?>> sources,
        FormPresentation presentation) {
      super(title);
      this.parent = parent;
      this.sources = Map.copyOf(sources);
      this.presentation = presentation;
    }

    @Override
    protected void init() {
      displayed = status();
      float scroll = diagnostics == null ? 0 : diagnostics.scroll();
      var actions = new ArrayList<Button>();
      if (displayed.lifecycle() == Lifecycle.UNAVAILABLE && state.get().context() != null)
        actions.add(
            Button.builder(
                    text("retry", "Retry"),
                    ignored -> {
                      if (!closed && minecraft.screen == this) retryInitialization();
                    })
                .bounds(0, 0, Math.max(56, font.width(text("retry", "Retry")) + 20), 20)
                .build());
      actions.add(
          Button.builder(text("back", "Back"), ignored -> onClose())
              .bounds(0, 0, Math.max(56, font.width(text("back", "Back")) + 20), 20)
              .build());
      var geometry =
          FormGeometry.controls(
              actions.stream().map(button -> new FormGeometry.Size(button.getWidth(), 20)).toList(),
              Math.max(1, width - 24),
              4);
      int top = height - 12 - geometry.height();
      for (int i = 0; i < actions.size(); i++) {
        var position = geometry.placements().get(i);
        var button = actions.get(i);
        button.setPosition(12 + position.x(), top + position.y());
        button.setWidth(position.width());
        addRenderableWidget(button);
      }
      diagnostics =
          new FormPanel(minecraft, 12, 44, Math.max(1, width - 24), Math.max(1, top - 52), false);
      diagnostics.row(Component.empty(), message());
      diagnostics.finish(scroll);
      addRenderableWidget(diagnostics);
    }

    @Override
    public void tick() {
      if (closed || minecraft.screen != this) return;
      var current = status();
      if (current.lifecycle() == Lifecycle.READY)
        minecraft.setScreen(screen(parent, title, sources, presentation));
      else if (!current.equals(displayed)) rebuildWidgets();
    }

    private Component message() {
      var current = status();
      if (current.lifecycle() == Lifecycle.REGISTERED
          || current.lifecycle() == Lifecycle.INITIALIZING)
        return text("initializing", "Loading client preferences…");
      return Component.literal(
          current.report().issues().stream()
              .map(Issue::fallback)
              .distinct()
              .limit(4)
              .reduce((first, second) -> first + "\n" + second)
              .orElse("Client preferences are unavailable; existing data has been retained."));
    }

    @Override
    public Component getNarrationMessage() {
      return title.copy().append(". ").append(message());
    }

    @Override
    public void render(GuiGraphics graphics, int x, int y, float delta) {
      super.render(graphics, x, y, delta);
      graphics.drawCenteredString(font, title, width / 2, 20, FormPanel.TITLE_TEXT);
    }

    @Override
    public void onClose() {
      if (closed || minecraft.screen != this) return;
      closed = true;
      minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
      closed = true;
    }
  }
}
