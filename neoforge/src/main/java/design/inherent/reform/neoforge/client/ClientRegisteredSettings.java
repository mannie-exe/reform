package design.inherent.reform.neoforge.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.neoforge.command.SettingsCommands;
import design.inherent.reform.neoforge.configuration.RegisteredConfig;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueType;
import java.nio.file.Path;
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
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;

/** Existing native CLIENT preferences or explicitly local COMMON defaults, never SERVER policy. */
public final class ClientRegisteredSettings {
  private static final org.slf4j.Logger LOGGER =
      org.slf4j.LoggerFactory.getLogger(ClientRegisteredSettings.class);

  public enum Lifecycle {
    REGISTERED,
    READY,
    UNAVAILABLE,
    CLOSED
  }

  /** READY binds a native owner; it does not certify saved data or domain-effective caches. */
  public record Status(Lifecycle lifecycle, ValidationReport report) {}

  private record State(
      Lifecycle lifecycle,
      RegisteredConfig owner,
      OwnerContext context,
      OwnerContext.Access access,
      Path path,
      ValidationReport report) {}

  private final ModContainer container;
  private final ModConfig config;
  private final DocumentId document;
  private final DocumentId nativeDocument;
  private final Optional<RegisteredConfig.Delegation> delegation;
  private final Map<FieldPath, ValueType<?>> explicitTypes;
  private final RegisteredConfig.Limits limits;
  private final JsonLimits values;
  private final AtomicReference<State> state =
      new AtomicReference<>(
          new State(Lifecycle.REGISTERED, null, null, null, null, ValidationReport.VALID));
  private final CompletableFuture<Status> initialization = new CompletableFuture<>();
  private DocumentActions<Map<String, Object>> commandActions;
  private Consumer<RegisterClientCommandsEvent> commandRegistration;

  private ClientRegisteredSettings(
      ModContainer container,
      ModConfig config,
      DocumentId document,
      DocumentId nativeDocument,
      Optional<RegisteredConfig.Delegation> delegation,
      Map<FieldPath, ValueType<?>> explicitTypes,
      RegisteredConfig.Limits limits) {
    this.container = container;
    this.config = config;
    this.document = document;
    this.nativeDocument = nativeDocument;
    this.delegation = delegation;
    this.explicitTypes = Map.copyOf(explicitTypes);
    this.limits = limits;
    values = new JsonLimits(limits.maximumBytes(), 64, 16384, 32768, 128, 4096);
  }

  /** Bind the actual public native registration reference before FML client setup. No source IO. */
  public static ClientRegisteredSettings registeredNeoForge(
      IEventBus modBus, ModContainer container, DocumentId document, ModConfig config) {
    return registeredNeoForge(
        modBus, container, document, config, Map.of(), RegisteredConfig.Limits.STANDARD);
  }

  public static ClientRegisteredSettings registeredNeoForge(
      IEventBus modBus,
      ModContainer container,
      DocumentId document,
      ModConfig config,
      Map<FieldPath, ValueType<?>> explicitTypes,
      RegisteredConfig.Limits limits) {
    Objects.requireNonNull(config);
    if (!config.getModId().equals(container.getModId()))
      throw new IllegalArgumentException(
          "Use this mod's actual CLIENT or local COMMON registration");
    return register(
        modBus,
        modBus,
        container,
        document,
        config,
        document,
        Optional.empty(),
        explicitTypes,
        limits);
  }

  public static ClientRegisteredSettings delegatedNeoForge(
      IEventBus modBus,
      ModContainer consumer,
      DocumentId route,
      ModConfig config,
      DocumentId nativeId,
      RegisteredConfig.Delegation gate,
      Map<FieldPath, ValueType<?>> explicitTypes,
      RegisteredConfig.Limits limits) {
    var target = Objects.requireNonNull(gate).requireTarget(config);
    var eventBus =
        Objects.requireNonNull(target.getEventBus(), "Native config owner has no event bus");
    return register(
        modBus,
        eventBus,
        consumer,
        route,
        config,
        nativeId,
        Optional.of(gate),
        explicitTypes,
        limits);
  }

  private static ClientRegisteredSettings register(
      IEventBus modBus,
      IEventBus nativeBus,
      ModContainer container,
      DocumentId document,
      ModConfig config,
      DocumentId nativeDocument,
      Optional<RegisteredConfig.Delegation> delegation,
      Map<FieldPath, ValueType<?>> explicitTypes,
      RegisteredConfig.Limits limits) {
    Objects.requireNonNull(modBus);
    Objects.requireNonNull(limits);
    if (FMLEnvironment.dist != Dist.CLIENT)
      throw new IllegalStateException(
          "Native client settings require physical-client registration");
    if (!container.getModId().equals(document.namespace())
        || !config.getModId().equals(nativeDocument.namespace())
        || config.getType() == ModConfig.Type.SERVER
        || !(config.getSpec() instanceof ModConfigSpec))
      throw new IllegalArgumentException("Use an admitted CLIENT or local COMMON registration");
    var registration =
        new ClientRegisteredSettings(
            container, config, document, nativeDocument, delegation, explicitTypes, limits);
    ClientSettingsRuntime.registerNative(
        document,
        registration,
        config.getSpec(),
        FMLPaths.CONFIGDIR.get().resolve(config.getFileName()),
        registration::close);
    try {
      modBus.addListener(registration::setup);
      nativeBus.addListener(registration::loading);
      nativeBus.addListener(registration::reloading);
      nativeBus.addListener(registration::unloading);
    } catch (RuntimeException failed) {
      ClientSettingsRuntime.unregisterDocument(document, registration);
      throw failed;
    }
    return registration;
  }

  public DocumentId document() {
    return document;
  }

  public Status status() {
    var current = state.get();
    return new Status(current.lifecycle(), current.report());
  }

  /** Historical first binding outcome, not a file-load receipt or promise of effective values. */
  public CompletionStage<Status> initialization() {
    return initialization.minimalCompletionStage();
  }

  public Optional<DocumentDefinition<Map<String, Object>>> definition() {
    return Optional.ofNullable(state.get().owner()).map(RegisteredConfig::definition);
  }

  public void registerMenu(Component title) {
    registerMenu(title, FormPresentation.EMPTY);
  }

  public void registerMenu(Component title, FormPresentation presentation) {
    Objects.requireNonNull(title);
    Objects.requireNonNull(presentation);
    if (container.getCustomExtension(IConfigScreenFactory.class).isPresent())
      throw new IllegalStateException("This mod already declares a configuration screen");
    container.registerExtensionPoint(
        IConfigScreenFactory.class, (ignored, parent) -> screen(parent, title, presentation));
  }

  /** Declares this actual native owner without opening an editor or initializing native data. */
  public ClientForm form(Component title) {
    return form(title, FormPresentation.EMPTY);
  }

  public ClientForm form(Component title, FormPresentation presentation) {
    return form(title, new ClientFormOptions<>(presentation, Map.of()));
  }

  public ClientForm form(Component title, ClientFormOptions<Map<String, Object>> options) {
    Objects.requireNonNull(options);
    var label = Objects.requireNonNull(title).copy();
    return ClientForm.bound(
            nativeDocument,
            label,
            this::statusDisplay,
            parent -> screen(parent, label, options),
            Optional.of(this::retry))
        .atRoute(document);
  }

  public Screen screen(Screen parent, Component title) {
    return screen(parent, title, FormPresentation.EMPTY);
  }

  public Screen screen(Screen parent, Component title, FormPresentation presentation) {
    return screen(parent, title, new ClientFormOptions<>(presentation, Map.of()));
  }

  public Screen screen(
      Screen parent, Component title, ClientFormOptions<Map<String, Object>> options) {
    ClientSettingsRuntime.requireClientThread();
    Objects.requireNonNull(title);
    Objects.requireNonNull(options);
    var observed = state.get();
    if (observed.lifecycle() == Lifecycle.READY && !sameBinding(observed.path())) retire(observed);
    var current = state.get();
    if (current.lifecycle() != Lifecycle.READY)
      return new ClientStatusScreen(
          parent,
          title,
          this::statusDisplay,
          () -> screen(parent, title, options),
          Optional.of(this::retry));
    options.presentation().requireCompatible(current.owner().definition().type());
    return editing(
        current,
        actions ->
            options.open(
                parent,
                title,
                config.getType() == ModConfig.Type.CLIENT
                    ? text("client_context", "Client preferences · native registered configuration")
                    : text(
                        "defaults_context", "Installation defaults · native COMMON configuration"),
                current.owner().definition(),
                actions,
                new SettingsScreen.SubmissionLabels(
                    text("save", "Save native settings"),
                    text("save_reviewed", "Save reviewed native settings"),
                    text("save_close", "Save native settings and close"))));
  }

  private <R> R editing(
      State current, java.util.function.Function<DocumentActions<Map<String, Object>>, R> entry) {
    current.context().requireOwner();
    return ClientEditors.open(
        current.context(),
        current.access(),
        current.owner().session(current.access()),
        current.owner().definition(),
        values,
        entry);
  }

  public LiteralArgumentBuilder<CommandSourceStack> commandBranch(
      String literal, Consumer<SettingsCommands.Reply> output) {
    Objects.requireNonNull(output);
    return ClientCommands.branch(
        literal,
        document,
        new ClientCommands.Owner<Map<String, Object>>() {
          @Override
          public boolean ready() {
            var current = state.get();
            return current.lifecycle() == Lifecycle.READY && sameBinding(current.path());
          }

          @Override
          public SettingsCommands.Target<Map<String, Object>> target() {
            var current = state.get();
            if (commandActions == null) commandActions = editing(current, actions -> actions);
            return new SettingsCommands.Target<>(current.context(), commandActions);
          }

          @Override
          public Optional<SettingsCommands.Target<Map<String, Object>>> existing() {
            return commandActions == null
                ? Optional.empty()
                : Optional.of(new SettingsCommands.Target<>(state.get().context(), commandActions));
          }
        },
        Optional.empty(),
        output);
  }

  public synchronized void registerCommands(String literal) {
    if (commandRegistration != null || state.get().lifecycle() == Lifecycle.CLOSED)
      throw new IllegalStateException("Client command entry is already registered or closed");
    commandRegistration =
        ClientCommands.registerRoot(literal, commandBranch(literal, ClientCommands::chat));
  }

  /**
   * Explicitly bind the currently loaded native context; never load/correct/create a native file.
   */
  public CompletionStage<Status> retry() {
    ClientSettingsRuntime.requireClientThread();
    if (state.get().lifecycle() != Lifecycle.UNAVAILABLE)
      throw new IllegalStateException("Retry requires an unavailable native client binding");
    bind();
    return CompletableFuture.completedStage(status());
  }

  private void setup(FMLClientSetupEvent event) {
    event.enqueueWork(
        () -> {
          if (state.get().lifecycle() == Lifecycle.REGISTERED) bind();
        });
  }

  private void bind() {
    ClientSettingsRuntime.requireClientThread();
    var before = state.get();
    if (before.lifecycle() != Lifecycle.REGISTERED && before.lifecycle() != Lifecycle.UNAVAILABLE)
      throw new IllegalStateException("Native binding requires an unbound client registration");
    OwnerContext context = null;
    try {
      var loaded = config.getLoadedConfig();
      if (loaded == null)
        throw new PolicyRefusal(
            "native.unavailable", "The actual native client configuration is not loaded");
      var path = config.getFullPath().toAbsolutePath().normalize();
      ClientSettingsRuntime.nativeFile(document, this, path);
      context =
          new OwnerContext(
              Minecraft.getInstance()::execute,
              ClientSettingsRuntime.workers(),
              ClientSettingsRuntime::requireClientThread,
              OwnerContext.Limits.STANDARD);
      var owner =
          delegation.isPresent()
              ? RegisteredConfig.delegated(
                  context, config, nativeDocument, delegation.orElseThrow(), explicitTypes, limits)
              : new RegisteredConfig(context, config, nativeDocument, explicitTypes, limits);
      var access = context.access(() -> sameBinding(path), () -> sameBinding(path));
      if (loaded != config.getLoadedConfig())
        throw new PolicyRefusal(
            "native.context_changed", "The native loaded context changed while binding");
      if (!state.compareAndSet(
          before, new State(Lifecycle.READY, owner, context, access, path, ValidationReport.VALID)))
        context.close();
    } catch (RuntimeException failed) {
      LOGGER.warn("Could not bind native client document {}", document, failed);
      if (context != null) context.close();
      state.compareAndSet(
          before,
          new State(
              Lifecycle.UNAVAILABLE,
              null,
              null,
              null,
              null,
              failed instanceof PolicyRefusal refused
                  ? refused.report()
                  : failure(
                      "client.native_unavailable",
                      "Native registered settings could not be bound; existing data was retained.")));
    }
    initialization.complete(status());
  }

  private void loading(ModConfigEvent.Loading event) {
    changed(event, false);
  }

  private void reloading(ModConfigEvent.Reloading event) {
    changed(event, false);
  }

  private void unloading(ModConfigEvent.Unloading event) {
    changed(event, true);
  }

  private void changed(ModConfigEvent event, boolean unloading) {
    var before = state.get();
    if (event.getConfig() != config || before.lifecycle() != Lifecycle.READY) return;
    Minecraft.getInstance()
        .execute(
            () -> {
              if (state.get() != before || (!unloading && sameBinding(before.path()))) return;
              retire(before);
            });
  }

  private boolean sameBinding(Path path) {
    ClientSettingsRuntime.requireClientThread();
    if (config.getLoadedConfig() == null) return false;
    try {
      return config.getFullPath().toAbsolutePath().normalize().equals(path);
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private void retire(State before) {
    ClientSettingsRuntime.requireClientThread();
    state.set(
        new State(
            Lifecycle.UNAVAILABLE,
            null,
            null,
            null,
            null,
            failure(
                "native.context_changed",
                "The native loaded context changed. Review its current source before editing again.")));
    commandActions = null;
    before.context().close();
  }

  private ClientStatusScreen.State statusDisplay() {
    var current = status();
    return new ClientStatusScreen.State(
        current.lifecycle() == Lifecycle.READY,
        current.lifecycle() == Lifecycle.UNAVAILABLE,
        current.lifecycle() == Lifecycle.REGISTERED
            ? text("waiting", "Waiting for native client configuration…")
            : ClientStatusScreen.reason(
                current.report(),
                text("unavailable", "Native registered settings are unavailable.")));
  }

  private void close() {
    ClientSettingsRuntime.requireClientThread();
    var before = state.get();
    if (before.lifecycle() == Lifecycle.CLOSED) return;
    state.set(
        new State(
            Lifecycle.CLOSED,
            null,
            null,
            null,
            null,
            failure("client.closed", "This native client settings lifetime is closed")));
    commandActions = null;
    initialization.complete(status());
    try {
      if (before.context() != null) before.context().close();
    } finally {
      var command = commandRegistration;
      commandRegistration = null;
      if (command != null) NeoForge.EVENT_BUS.unregister(command);
    }
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.native." + key, fallback);
  }

  private static ValidationReport failure(String code, String fallback) {
    return new ValidationReport(List.of(Issue.error(code, FieldPath.ROOT, fallback)), false);
  }
}
