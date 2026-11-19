package design.inherent.reform.neoforge.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.neoforge.command.SettingsCommands;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Physical-client registration for existing native Controls; no preference file or key registrar.
 */
public final class ClientKeyBindings {
  public enum Lifecycle {
    REGISTERED,
    WAITING_FOR_NATIVE,
    READY,
    UNAVAILABLE,
    CLOSED
  }

  /**
   * READY means the native owner is bound; editor reads still validate saved versus loaded data.
   */
  public record Status(Lifecycle lifecycle, ValidationReport report) {}

  private record State(
      Lifecycle lifecycle,
      KeyBindingOptions owner,
      OwnerContext context,
      OwnerContext.Access access,
      ValidationReport report) {}

  private final ModContainer container;
  private final DocumentId document;
  private final Supplier<? extends Collection<? extends KeyMapping>> mappings;
  private final KeyBindingOptions.Limits limits;
  private final AtomicReference<State> state =
      new AtomicReference<>(
          new State(Lifecycle.REGISTERED, null, null, null, ValidationReport.VALID));
  private final CompletableFuture<Status> initialization = new CompletableFuture<>();
  private Consumer<ClientTickEvent.Post> nativeAdmission;
  private Consumer<RegisterClientCommandsEvent> commandRegistration;
  private DocumentActions<Map<String, KeyBindingOptions.Binding>> commandActions;

  private ClientKeyBindings(
      ModContainer container,
      DocumentId document,
      Supplier<? extends Collection<? extends KeyMapping>> mappings,
      KeyBindingOptions.Limits limits) {
    this.container = container;
    this.document = document;
    this.mappings = mappings;
    this.limits = limits;
  }

  /** Register during the owning physical-client mod's construction, before client setup. No IO. */
  public static ClientKeyBindings clientKeys(
      IEventBus modBus,
      ModContainer container,
      DocumentId document,
      Supplier<? extends Collection<? extends KeyMapping>> mappings) {
    return clientKeys(modBus, container, document, mappings, KeyBindingOptions.Limits.STANDARD);
  }

  public static ClientKeyBindings clientKeys(
      IEventBus modBus,
      ModContainer container,
      DocumentId document,
      Supplier<? extends Collection<? extends KeyMapping>> mappings,
      KeyBindingOptions.Limits limits) {
    Objects.requireNonNull(modBus);
    Objects.requireNonNull(container);
    Objects.requireNonNull(document);
    Objects.requireNonNull(mappings);
    Objects.requireNonNull(limits);
    if (FMLEnvironment.dist != Dist.CLIENT)
      throw new IllegalStateException("Native keybindings require physical-client registration");
    if (!container.getModId().equals(document.namespace()))
      throw new IllegalArgumentException(
          "Native key documents belong to their declaring mod namespace");
    var registration = new ClientKeyBindings(container, document, mappings, limits);
    ClientSettingsRuntime.registerDocument(document, registration, registration::close);
    try {
      modBus.addListener(registration::setup);
    } catch (RuntimeException rejected) {
      ClientSettingsRuntime.unregisterDocument(document, registration);
      throw rejected;
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

  /** First actual binding outcome. Cancelling a returned copy does not cancel native startup. */
  public CompletionStage<Status> initialization() {
    return initialization.minimalCompletionStage();
  }

  /**
   * Actual admitted definition only, never a default record invented before native registration.
   */
  public Optional<DocumentDefinition<Map<String, KeyBindingOptions.Binding>>> definition() {
    return Optional.ofNullable(state.get().owner()).map(KeyBindingOptions::definition);
  }

  public void registerMenu(Component title) {
    Objects.requireNonNull(title);
    if (container.getCustomExtension(IConfigScreenFactory.class).isPresent())
      throw new IllegalStateException("This mod already declares a configuration screen");
    container.registerExtensionPoint(
        IConfigScreenFactory.class, (ignored, parent) -> screen(parent, title));
  }

  /** Declares native Controls as one independently saved document in a composed workflow. */
  public ClientForm form(Component title) {
    var label = Objects.requireNonNull(title).copy();
    return ClientForm.bound(
        document, label, this::statusDisplay, parent -> screen(parent, label), Optional.empty());
  }

  public Screen screen(Screen parent, Component title) {
    ClientSettingsRuntime.requireClientThread();
    var current = state.get();
    if (current.lifecycle() != Lifecycle.READY)
      return new ClientStatusScreen(
          parent, title, this::statusDisplay, () -> screen(parent, title), Optional.empty());
    var session = current.owner().session(current.access());
    return ClientEditors.open(
        current.context(),
        current.access(),
        session,
        current.owner().definition(),
        current.owner().jsonLimits(),
        actions ->
            new SettingsScreen<>(
                parent,
                title,
                text("context", "Client keybindings · native Controls"),
                current.owner().definition(),
                actions,
                Map.of(),
                FormPresentation.EMPTY,
                KeyBindingEditors.forSession(session)));
  }

  private DocumentActions<Map<String, KeyBindingOptions.Binding>> editing(State current) {
    current.context().requireOwner();
    var session = current.owner().session(current.access());
    return ClientEditors.open(
        current.context(),
        current.access(),
        session,
        current.owner().definition(),
        current.owner().jsonLimits(),
        actions -> actions);
  }

  public LiteralArgumentBuilder<CommandSourceStack> commandBranch(
      String literal, Consumer<SettingsCommands.Reply> output) {
    Objects.requireNonNull(output);
    return ClientCommands.branch(
        literal,
        document,
        new ClientCommands.Owner<Map<String, KeyBindingOptions.Binding>>() {
          @Override
          public boolean ready() {
            return state.get().lifecycle() == Lifecycle.READY;
          }

          @Override
          public SettingsCommands.Target<Map<String, KeyBindingOptions.Binding>> target() {
            var current = state.get();
            if (commandActions == null) commandActions = editing(current);
            return new SettingsCommands.Target<>(current.context(), commandActions);
          }

          @Override
          public Optional<SettingsCommands.Target<Map<String, KeyBindingOptions.Binding>>>
              existing() {
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

  private void setup(FMLClientSetupEvent event) {
    event.enqueueWork(
        () -> {
          ClientSettingsRuntime.requireClientThread();
          if (state.get().lifecycle() != Lifecycle.REGISTERED) return;
          state.set(
              new State(Lifecycle.WAITING_FOR_NATIVE, null, null, null, ValidationReport.VALID));
          // ClientHooks registers the final native mapping array later in Minecraft bootstrap.
          nativeAdmission = ignored -> bindNative();
          NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, nativeAdmission);
        });
  }

  private void bindNative() {
    ClientSettingsRuntime.requireClientThread();
    if (state.get().lifecycle() != Lifecycle.WAITING_FOR_NATIVE) return;
    var listener = nativeAdmission;
    nativeAdmission = null;
    if (listener != null) NeoForge.EVENT_BUS.unregister(listener);
    var client = Minecraft.getInstance();
    OwnerContext context = null;
    try {
      context =
          new OwnerContext(
              client::execute,
              ClientSettingsRuntime.workers(),
              ClientSettingsRuntime::requireClientThread,
              OwnerContext.Limits.STANDARD);
      var owner = new KeyBindingOptions(context, client.options, document, mappings.get(), limits);
      var access = context.access(() -> true, () -> true);
      state.set(new State(Lifecycle.READY, owner, context, access, ValidationReport.VALID));
      initialization.complete(status());
    } catch (RuntimeException failed) {
      try {
        if (context != null) context.close();
      } finally {
        state.set(
            new State(
                Lifecycle.UNAVAILABLE,
                null,
                null,
                null,
                failed instanceof PolicyRefusal refused
                    ? refused.report()
                    : failure(
                        "client.keys_unavailable",
                        "Native keybinding registration could not be bound; existing options were retained.")));
        initialization.complete(status());
      }
    }
  }

  private ClientStatusScreen.State statusDisplay() {
    var current = status();
    Component message =
        current.lifecycle() == Lifecycle.REGISTERED
                || current.lifecycle() == Lifecycle.WAITING_FOR_NATIVE
            ? text("waiting", "Waiting for native Controls registration…")
            : ClientStatusScreen.reason(
                current.report(),
                text(
                    "unavailable",
                    "Native keybindings are unavailable; existing options were retained."));
    return new ClientStatusScreen.State(current.lifecycle() == Lifecycle.READY, false, message);
  }

  private void close() {
    ClientSettingsRuntime.requireClientThread();
    var current = state.get();
    if (current.lifecycle() == Lifecycle.CLOSED) return;
    try {
      if (current.context() != null) current.context().close();
    } finally {
      commandActions = null;
      state.set(
          new State(
              Lifecycle.CLOSED,
              current.owner(),
              null,
              null,
              failure("client.closed", "This client keybinding context is closed")));
      initialization.complete(status());
      var admission = nativeAdmission;
      nativeAdmission = null;
      if (admission != null) NeoForge.EVENT_BUS.unregister(admission);
      var command = commandRegistration;
      commandRegistration = null;
      if (command != null) NeoForge.EVENT_BUS.unregister(command);
    }
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.keys." + key, fallback);
  }

  private static ValidationReport failure(String code, String fallback) {
    return new ValidationReport(List.of(Issue.error(code, FieldPath.ROOT, fallback)), false);
  }
}
