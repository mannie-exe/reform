package design.inherent.reform.neoforge.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.neoforge.configuration.ServerSettings;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.FormPresentation;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

/** Native forms over explicitly registered policies for the current integrated host. */
public final class IntegratedSettings {
  public static Screen screen(Screen parent, Component title, DocumentId document) {
    return screen(parent, title, document, 2, FormPresentation.EMPTY);
  }

  /** The declared level (0–4) applies to both reading and saving, subject to native permission. */
  public static Screen screen(
      Screen parent, Component title, DocumentId document, int level, FormPresentation form) {
    ClientSettingsRuntime.requireClientThread();
    Objects.requireNonNull(document);
    Objects.requireNonNull(form);
    if (level < 0 || level > 4)
      throw new IllegalArgumentException("Native permission levels must be between zero and four");
    var client = Minecraft.getInstance();
    var server = client.getSingleplayerServer();
    var player = client.player;
    var listener = client.getConnection();
    var world = client.level;
    if (server == null
        || player == null
        || listener == null
        || world == null
        || !listener.getConnection().isMemoryConnection())
      return unavailable(
          parent,
          title,
          text("integrated_required", "Open a local world to edit its server policy."));
    var target = ServerSettings.admittedDocument(server, document);
    if (target.isEmpty())
      return unavailable(
          parent,
          title,
          text("integrated_missing", "This world has no registered policy for this entry."));
    var view =
        new Opening<>(
            new Host(client, server, player, listener, world),
            target.orElseThrow(),
            level,
            form,
            parent,
            title.copy());
    return new ClientStatusScreen(
        parent, title, view::state, view::open, Optional.empty(), view::begin, view::retire);
  }

  private record Host(
      Minecraft client,
      IntegratedServer server,
      LocalPlayer player,
      ClientPacketListener listener,
      ClientLevel level) {
    boolean current(int permission) {
      return client.getSingleplayerServer() == server
          && client.getConnection() == listener
          && client.player == player
          && client.level == level
          && listener.isAcceptingMessages()
          && player.hasPermissions(permission);
    }
  }

  private static final class Opening<T> {
    private final Host host;
    private final ServerSettings.Document<T> document;
    private final int level;
    private final FormPresentation form;
    private final Screen parent;
    private final Component title;
    private final AtomicBoolean live = new AtomicBoolean(true);
    private final AtomicReference<ServerSettings.LocalPresentation<T>> admitted =
        new AtomicReference<>();
    private volatile Component failure;
    private OwnerContext context;
    private boolean started, transferred;

    Opening(
        Host host,
        ServerSettings.Document<T> document,
        int level,
        FormPresentation form,
        Screen parent,
        Component title) {
      this.host = host;
      this.document = document;
      this.level = level;
      this.form = form;
      this.parent = parent;
      this.title = title;
    }

    private void begin() {
      if (started) return;
      started = true;
      if (!host.current(level)) {
        failure =
            text(
                "integrated_permission",
                "The local player needs current permission for this policy.");
        return;
      }
      try {
        form.requireCompatible(document.definition().type());
        context =
            new OwnerContext(
                host.client()::execute,
                ClientSettingsRuntime.workers(),
                ClientSettingsRuntime::requireClientThread,
                OwnerContext.Limits.STANDARD);
        context.onClose(
            () -> {
              live.set(false);
              closeAdmission();
            });
        ClientSettingsRuntime.manageContext(context, this::current);
        var connection = host.listener().getConnection();
        var playerId = host.player().getUUID();
        document
            .context()
            .onOwner(
                () -> {
                  if (!live.get() || !connection.isConnected()) throw closed();
                  var player = host.server().getPlayerList().getPlayer(playerId);
                  if (player == null) throw closed();
                  return document.localPresentation(
                      player.createCommandSourceStack(),
                      level,
                      () -> live.get() && connection.isConnected());
                })
            .whenComplete(
                (binding, failed) -> {
                  if (failed != null) failure = reason(failed);
                  else {
                    admitted.set(binding);
                    if (!live.get()) closeAdmission();
                  }
                });
      } catch (RuntimeException failed) {
        failure = reason(failed);
        if (context != null) context.close();
      }
    }

    private boolean current() {
      var binding = admitted.get();
      return host.current(level)
          && document.context().isOpen()
          && (binding == null || binding.isOpen());
    }

    private ClientStatusScreen.State state() {
      var rejected = failure;
      if (rejected != null) return new ClientStatusScreen.State(false, false, rejected);
      if (!live.get() || !current()) return new ClientStatusScreen.State(false, false, ended());
      return new ClientStatusScreen.State(
          admitted.get() != null,
          false,
          text("integrated_loading", "Checking the running world's policy and player permission…"));
    }

    private Screen open() {
      var binding = admitted.get();
      if (binding == null || !current() || !context.isOpen())
        return unavailable(parent, title, ended());
      try {
        var access = context.access(this::current, this::current);
        var actions = binding.attach(context, access);
        var screen =
            new SettingsScreen<>(
                parent,
                title,
                text("integrated_context", "Integrated world policy · running world"),
                document.definition(),
                actions,
                Map.of(),
                form);
        transferred = true;
        return screen;
      } catch (RuntimeException failed) {
        context.close();
        return unavailable(parent, title, reason(failed));
      }
    }

    private void retire() {
      if (!transferred && context != null) context.close();
    }

    private void closeAdmission() {
      var binding = admitted.getAndSet(null);
      if (binding != null) binding.close();
    }
  }

  private static Screen unavailable(Screen parent, Component title, Component message) {
    return new ClientStatusScreen(
        parent,
        title,
        () -> new ClientStatusScreen.State(false, false, message),
        () -> parent,
        Optional.empty());
  }

  private static Component reason(Throwable failure) {
    while (failure instanceof CompletionException && failure.getCause() != null)
      failure = failure.getCause();
    return failure instanceof PolicyRefusal refusal
        ? ClientStatusScreen.reason(refusal.report(), ended())
        : ended();
  }

  private static PolicyRefusal closed() {
    return new PolicyRefusal(
        "owner.closed", "The local world or player changed; reopen the policy.");
  }

  private static Component ended() {
    return text("integrated_closed", "The local world or player changed; reopen the policy.");
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.settings." + key, fallback);
  }

  private IntegratedSettings() {}
}
