package design.inherent.reform.neoforge.configuration;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationScope;
import design.inherent.reform.owner.ApplicationEffects;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.DocumentSession;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.FileOwner;
import design.inherent.reform.owner.LiveApplication;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.SettingsHandle;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.storage.TextStore;
import design.inherent.reform.value.ValueType;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Actual server lifetime, bounded workers and explicit file/native policy bindings. No client code.
 */
public final class ServerSettings implements AutoCloseable {
  private static final Map<MinecraftServer, ServerSettings> SERVERS = new IdentityHashMap<>();
  private static final JsonLimits VALUES = new JsonLimits(262144, 64, 16384, 32768, 128, 4096);
  private static final OperationScope.Limits ACTIONS =
      new OperationScope.Limits(32, 64, 128, OperationScope.Limits.STANDARD.input());

  private record CommandEditor<T>(ServerGamePacketListenerImpl connection, Editor<T> editor) {}

  static {
    NeoForge.EVENT_BUS.addListener(ServerSettings::stopping);
    NeoForge.EVENT_BUS.addListener(ServerSettings::stopped);
    NeoForge.EVENT_BUS.addListener(ServerSettings::loggedOut);
    NeoForge.EVENT_BUS.addListener(ServerSettings::cloned);
  }

  /** One native editor owns its access; closing it leaves its document and other editors alive. */
  public static final class Editor<T> implements AutoCloseable {
    private final OwnerContext context;
    private final OwnerContext.Access access;
    private final DocumentActions<T> actions;
    private final OwnerContext.CloseHook hook;
    private boolean closed;

    private Editor(OwnerContext context, OwnerContext.Access access, DocumentActions<T> actions) {
      this.context = context;
      this.access = access;
      this.actions = actions;
      hook = context.onClose(this::close);
    }

    public DocumentActions<T> actions() {
      if (closed) throw new PolicyRefusal("editor.closed", "This server editor is closed");
      return actions;
    }

    @Override
    public void close() {
      if (closed) return;
      context.checkOwnerThread();
      closed = true;
      try {
        actions.close();
      } finally {
        access.close();
        hook.close();
      }
    }
  }

  /** One native player session awaiting transfer to a separate in-process form lifetime. */
  public static final class LocalPresentation<T> implements AutoCloseable {
    private final Document<T> document;
    private final OwnerContext.Access nativeAccess;
    private final DocumentSession<T> source;
    private final AtomicBoolean attached = new AtomicBoolean();

    private LocalPresentation(
        Document<T> document, OwnerContext.Access nativeAccess, DocumentSession<T> source) {
      this.document = document;
      this.nativeAccess = nativeAccess;
      this.source = source;
    }

    public boolean isOpen() {
      return source.isOpen();
    }

    /** Transfer the binding and the shorter-lived presentation context to the shared editor. */
    public DocumentActions<T> attach(OwnerContext context, OwnerContext.Access access) {
      context.requireOwner();
      if (context == document.context || !context.id().equals(access.context()))
        throw new IllegalArgumentException("Presentation needs a separate local editing context");
      if (!isOpen() || !attached.compareAndSet(false, true))
        throw new PolicyRefusal("owner.closed", "This server policy view is closed or attached");
      try {
        access.require(OwnerContext.Right.READ);
        context.onClose(this::close);
        var actor =
            EditorActor.presenting(
                context, source, document.definition, document.values, document.drafts, 64);
        return DocumentActions.owningContext(
            context, access, actor, ACTIONS, document.values.maximumBytes());
      } catch (RuntimeException | Error failed) {
        try {
          context.close();
        } finally {
          close();
        }
        throw failed;
      }
    }

    /** The built-in file and registered sessions support disposal from the presentation thread. */
    @Override
    public void close() {
      nativeAccess.close();
      source.close();
    }
  }

  /** The saved source, optional owned runtime handle and initialization receipt stay distinct. */
  public static final class Document<T> {
    private final ServerSettings scope;
    private final DocumentDefinition<T> definition;
    private final OwnerContext context;
    private final Function<OwnerContext.Access, DocumentSession<T>> sessions;
    private final JsonLimits values;
    private final DraftLimits drafts;
    private final Optional<SettingsHandle<T>> runtime;
    private final String file;
    private final Map<CommandSource, Map<Integer, CommandEditor<T>>> commandEditors =
        new IdentityHashMap<>();
    private CompletionStage<CommitReceipt<T>> initialization;

    private Document(
        ServerSettings scope,
        DocumentDefinition<T> definition,
        OwnerContext context,
        Function<OwnerContext.Access, DocumentSession<T>> sessions,
        JsonLimits values,
        DraftLimits drafts,
        Optional<SettingsHandle<T>> runtime,
        String file) {
      this.scope = scope;
      this.definition = definition;
      this.context = context;
      this.sessions = sessions;
      this.values = values;
      this.drafts = drafts;
      this.runtime = runtime;
      this.file = file;
      context.onClose(commandEditors::clear);
    }

    public DocumentDefinition<T> definition() {
      return definition;
    }

    /** Explicit advanced adapters borrow this actual context, not a process-global server. */
    public OwnerContext context() {
      return context;
    }

    /** Registered native values are not misrepresented as a domain-effective runtime handle. */
    public Optional<SettingsHandle<T>> runtime() {
      return runtime;
    }

    /** File initialization only; native configs retain their real loader initialization. */
    public Optional<CompletionStage<CommitReceipt<T>>> initialization() {
      return Optional.ofNullable(initialization);
    }

    public DocumentSession<T> session(OwnerContext.Access access) {
      scope.requireCurrent();
      return sessions.apply(access);
    }

    public Editor<T> editor(CommandSourceStack source) {
      return editor(source, 2, 2);
    }

    /**
     * Apply the actual current saved bytes with this actor's authority, without creating or writing
     * the file.
     */
    public CompletionStage<? extends CommitReceipt<T>> reloadFromSaved(
        CommandSourceStack source, int level) {
      var access = actorAccess(source, level, level, () -> true);
      final DocumentSession<T> session;
      try {
        access.require(OwnerContext.Right.READ);
        access.require(OwnerContext.Right.WRITE);
        session = session(access);
      } catch (RuntimeException | Error failed) {
        access.close();
        throw failed;
      }
      try {
        if (!session.operations().contains(DocumentSession.Operation.APPLY_SAVED))
          throw new PolicyRefusal(
              "owner.unsupported", "The native owner has no apply-only operation");
        return session
            .read()
            .thenCompose(session::applySaved)
            .whenComplete(
                (receipt, failure) -> {
                  session.close();
                  access.close();
                });
      } catch (RuntimeException | Error failed) {
        session.close();
        access.close();
        throw failed;
      }
    }

    boolean commandAvailable(CommandSourceStack source, int level) {
      if (!source.getServer().isSameThread()
          || scope.server != source.getServer()
          || !scope.current()) return false;
      var player = source.getPlayer();
      return scope.permitted(source, player, player == null ? null : player.connection, level);
    }

    Optional<Editor<T>> commandEditor(CommandSourceStack source, int level, boolean create) {
      scope.requireCurrent();
      if (!commandAvailable(source, level)) return Optional.empty();
      var editors = commandEditors.get(source.source);
      var retained = editors == null ? null : editors.get(level);
      var player = source.getPlayer();
      var connection = player == null ? null : player.connection;
      if (retained != null && retained.connection() == connection)
        return Optional.of(retained.editor());
      if (!create) return Optional.empty();
      if (retained != null) retireCommands(source.source);
      var opened = editor(source, level, level);
      commandEditors
          .computeIfAbsent(source.source, ignored -> new HashMap<>())
          .put(level, new CommandEditor<>(connection, opened));
      return Optional.of(opened);
    }

    private void retireCommands(CommandSource actor) {
      var editors = commandEditors.remove(actor);
      if (editors != null) editors.values().forEach(retained -> retained.editor().close());
    }

    /** Captured command permission is a restriction; actual native permission is checked afresh. */
    public Editor<T> editor(CommandSourceStack source, int readLevel, int writeLevel) {
      var access = actorAccess(source, readLevel, writeLevel, () -> true);
      DocumentSession<T> session = null;
      EditorActor<T> actor = null;
      DocumentActions<T> actions = null;
      try {
        session = session(access);
        actor = new EditorActor<>(context, session, definition, values, drafts, 64);
        actions = new DocumentActions<>(context, access, actor, ACTIONS, values.maximumBytes());
        return new Editor<>(context, access, actions);
      } catch (RuntimeException | Error failed) {
        try {
          if (actions != null) actions.close();
          else if (actor != null) actor.close();
          else if (session != null) session.close();
        } finally {
          access.close();
        }
        throw failed;
      }
    }

    /** Admit only this integrated server's actual local host player, on the server thread. */
    public LocalPresentation<T> localPresentation(
        CommandSourceStack source, int level, BooleanSupplier presentationCurrent) {
      scope.requireCurrent();
      Objects.requireNonNull(presentationCurrent);
      var player = Objects.requireNonNull(source).getPlayer();
      if (scope.server.isDedicatedServer()
          || player == null
          || !scope.server.isSingleplayerOwner(player.getGameProfile())
          || player.connection == null
          || !player.connection.getConnection().isMemoryConnection())
        throw new PolicyRefusal("owner.permission", "Use the integrated world's local host player");
      var access = actorAccess(source, level, level, presentationCurrent);
      try {
        access.require(OwnerContext.Right.READ);
        return new LocalPresentation<>(this, access, session(access));
      } catch (RuntimeException | Error failed) {
        access.close();
        throw failed;
      }
    }

    private OwnerContext.Access actorAccess(
        CommandSourceStack source, int readLevel, int writeLevel, BooleanSupplier active) {
      scope.requireCurrent();
      if (readLevel < 0 || readLevel > 4 || writeLevel < 0 || writeLevel > 4)
        throw new IllegalArgumentException(
            "Native permission levels must be between zero and four");
      Objects.requireNonNull(source);
      var player = source.getPlayer();
      var connection = player == null ? null : player.connection;
      return context.access(
          () -> active.getAsBoolean() && scope.permitted(source, player, connection, readLevel),
          () -> active.getAsBoolean() && scope.permitted(source, player, connection, writeLevel));
    }

    private void initialize(FileOwner<T> owner) {
      var access = context.access(scope::current, scope::current);
      var session = owner.open(access);
      try {
        initialization =
            session
                .initialize()
                .<CommitReceipt<T>>thenApply(receipt -> receipt)
                .whenComplete(
                    (ignored, failed) -> {
                      session.close();
                      access.close();
                    })
                .toCompletableFuture()
                .minimalCompletionStage();
      } catch (RuntimeException failed) {
        session.close();
        access.close();
        initialization = CompletableFuture.<CommitReceipt<T>>failedStage(failed);
      }
    }
  }

  private MinecraftServer server;
  private boolean open = true;
  private final Map<DocumentId, Document<?>> documents = new ConcurrentHashMap<>();
  private ThreadPoolExecutor workers;

  private ServerSettings(MinecraftServer server) {
    this.server = server;
  }

  /**
   * Call from an admitted server lifecycle callback, on that server's actual thread. No file IO.
   */
  public static ServerSettings forServer(MinecraftServer server) {
    Objects.requireNonNull(server);
    requireThread(server);
    if (!server.isRunning() || server.isStopped())
      throw new PolicyRefusal("owner.closed", "This native server is not running");
    synchronized (SERVERS) {
      var existing = SERVERS.get(server);
      if (existing != null) return existing;
      if (SERVERS.size() >= 8)
        throw new IllegalStateException("Native server settings admission is full");
      var scope = new ServerSettings(server);
      SERVERS.put(server, scope);
      return scope;
    }
  }

  /** Genuinely new JSON; the caller explicitly supplies its real server/world persistence root. */
  public <T> Document<T> serverPolicy(
      ModContainer container, DocumentDefinition<T> definition, StorageBinding binding) {
    return serverPolicy(
        container,
        new JsonStore<>(
            definition,
            new OwnedFile(binding, VALUES.maximumBytes()),
            JsonStore.Format.NEW_ENVELOPE,
            VALUES,
            DraftLimits.CONFIGURATION));
  }

  /** Explicit existing properties, bare JSON or custom formats retain their actual source. */
  public <T> Document<T> serverPolicy(ModContainer container, TextStore<T> store) {
    return serverPolicy(container, store, ApplicationEffects.OWNER_PUBLICATION);
  }

  public <T> Document<T> serverPolicy(
      ModContainer container, TextStore<T> store, ApplicationEffects effects) {
    return serverPolicy(container, store, effects, Optional.empty());
  }

  /** Reuse the existing server-thread domain effect and verify its actual readback. */
  public <T> Document<T> serverPolicy(
      ModContainer container,
      TextStore<T> store,
      ApplicationEffects effects,
      LiveApplication<T> liveApplication) {
    return serverPolicy(container, store, effects, Optional.of(liveApplication));
  }

  private <T> Document<T> serverPolicy(
      ModContainer container,
      TextStore<T> store,
      ApplicationEffects effects,
      Optional<LiveApplication<T>> liveApplication) {
    Objects.requireNonNull(store);
    Objects.requireNonNull(effects);
    var definition = store.definition();
    var file = portable(store.binding().path());
    admit(container, definition.id(), file);
    var context = contextForDocument();
    try {
      var owner =
          liveApplication
              .map(effect -> new FileOwner<>(context, store, 16, effects, effect))
              .orElseGet(() -> new FileOwner<>(context, store, 16, effects));
      var document =
          new Document<>(
              this,
              definition,
              context,
              owner::open,
              store.valueLimits(),
              store.draftLimits(),
              Optional.of(owner.handle()),
              file);
      document.initialize(owner);
      documents.put(definition.id(), document);
      return document;
    } catch (RuntimeException failed) {
      documents.remove(definition.id());
      context.close();
      throw failed;
    }
  }

  public Document<Map<String, Object>> registeredNeoForge(
      ModContainer container, ModConfig config, DocumentId id) {
    return registeredNeoForge(container, config, id, Map.of(), RegisteredConfig.Limits.STANDARD);
  }

  /** Explicit server admission; a COMMON filename alone grants no server authority. */
  public Document<Map<String, Object>> registeredNeoForge(
      ModContainer container,
      ModConfig config,
      DocumentId id,
      Map<FieldPath, ValueType<?>> explicitTypes,
      RegisteredConfig.Limits limits) {
    requireCurrent();
    Objects.requireNonNull(config);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(explicitTypes);
    if (config.getType() == ModConfig.Type.CLIENT
        || !config.getModId().equals(Objects.requireNonNull(container).getModId()))
      throw new IllegalArgumentException("Use this mod's actual server-admitted native config");
    return admitRegistered(
        container,
        config,
        id,
        limits,
        context -> new RegisteredConfig(context, config, id, explicitTypes, limits));
  }

  /**
   * Consumer navigation has its own route. The definition and native reads/writes retain the actual
   * owner.
   */
  public Document<Map<String, Object>> delegatedNeoForge(
      ModContainer consumer,
      DocumentId route,
      ModConfig config,
      DocumentId nativeId,
      RegisteredConfig.Delegation delegation,
      Map<FieldPath, ValueType<?>> explicitTypes,
      RegisteredConfig.Limits limits) {
    requireCurrent();
    Objects.requireNonNull(delegation).requireTarget(config);
    if (config.getType() == ModConfig.Type.CLIENT)
      throw new IllegalArgumentException("A client registration cannot be delegated on the server");
    return admitRegistered(
        consumer,
        config,
        route,
        limits,
        context ->
            RegisteredConfig.delegated(
                context, config, nativeId, delegation, explicitTypes, limits));
  }

  private Document<Map<String, Object>> admitRegistered(
      ModContainer container,
      ModConfig config,
      DocumentId route,
      RegisteredConfig.Limits limits,
      Function<OwnerContext, RegisteredConfig> bind) {
    var file = portable(config.getFullPath());
    admit(container, route, file);
    var context = contextForDocument();
    try {
      var owner = bind.apply(context);
      var document =
          new Document<>(
              this,
              owner.definition(),
              context,
              owner::session,
              new JsonLimits(limits.maximumBytes(), 64, 16384, 32768, 128, 4096),
              DraftLimits.CONFIGURATION,
              Optional.empty(),
              file);
      documents.put(route, document);
      return document;
    } catch (RuntimeException failed) {
      context.close();
      throw failed;
    }
  }

  /** Metadata lookup creates no owner, editor, source read or default. */
  public Optional<Document<?>> document(DocumentId id) {
    requireCurrent();
    return Optional.ofNullable(documents.get(Objects.requireNonNull(id)));
  }

  /** Cross-thread lookup of immutable registration metadata for this exact server. No creation. */
  public static Optional<Document<?>> admittedDocument(MinecraftServer server, DocumentId id) {
    Objects.requireNonNull(server);
    Objects.requireNonNull(id);
    synchronized (SERVERS) {
      var scope = SERVERS.get(server);
      return scope == null ? Optional.empty() : Optional.ofNullable(scope.documents.get(id));
    }
  }

  @SuppressWarnings("unchecked") // Exact definition identity pairs its document's value type.
  static <T> Optional<Document<T>> admittedDocument(
      MinecraftServer server, DocumentDefinition<T> definition) {
    return admittedDocument(server, definition.id(), definition);
  }

  /** Typed metadata for a particular route. Opening an actor still requires native admission. */
  @SuppressWarnings("unchecked") // Exact definition identity pairs its document's value type.
  public static <T> Optional<Document<T>> admittedDocument(
      MinecraftServer server, DocumentId route, DocumentDefinition<T> definition) {
    Objects.requireNonNull(definition);
    return admittedDocument(server, route)
        .filter(doc -> doc.definition() == definition)
        .map(doc -> (Document<T>) doc);
  }

  @SuppressWarnings(
      "unchecked") // The registered factory alone supplies a document without a handle.
  static Optional<Document<Map<String, Object>>> admittedRegistered(
      MinecraftServer server, DocumentId id) {
    return admittedDocument(server, id)
        .filter(doc -> doc.runtime().isEmpty())
        .map(doc -> (Document<Map<String, Object>>) doc);
  }

  private void admit(ModContainer container, DocumentId id, String file) {
    requireCurrent();
    if (!Objects.requireNonNull(container).getModId().equals(id.namespace()))
      throw new IllegalArgumentException("Settings must belong to their actual declaring mod");
    if (documents.size() >= 128 || documents.containsKey(id))
      throw new IllegalArgumentException(
          "Server document identity is duplicate or admission is full");
    if (documents.values().stream().anyMatch(document -> document.file.equals(file)))
      throw new IllegalArgumentException("This server file already has an admitted document");
  }

  private OwnerContext contextForDocument() {
    var target = server;
    return new OwnerContext(
        target::execute, workers(), () -> requireThread(target), OwnerContext.Limits.STANDARD);
  }

  private ThreadPoolExecutor workers() {
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
                    new Thread(task, "reform-server-settings-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
              });
      workers.allowCoreThreadTimeOut(true);
    }
    return workers;
  }

  private boolean current() {
    return open && server != null && server.isRunning() && !server.isStopped();
  }

  private void requireCurrent() {
    if (!current())
      throw new PolicyRefusal("owner.closed", "This server settings context is closed");
    requireThread(server);
  }

  private boolean permitted(
      CommandSourceStack source,
      ServerPlayer player,
      ServerGamePacketListenerImpl connection,
      int level) {
    requireCurrent();
    if (source.getServer() != server || !source.hasPermission(level)) return false;
    if (player == null)
      return source.source == server
          && source.getEntity() == null
          && server.createCommandSourceStack().hasPermission(level);
    return source.source == player
        && connection != null
        && player.connection == connection
        && connection.isAcceptingMessages()
        && server.getPlayerList().getPlayer(player.getUUID()) == player
        && player.createCommandSourceStack().hasPermission(level);
  }

  private static String portable(java.nio.file.Path path) {
    return path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
  }

  private static void requireThread(MinecraftServer server) {
    if (!server.isSameThread())
      throw new IllegalStateException("Server settings require that native server's thread");
  }

  private static void stopping(ServerStoppingEvent event) {
    release(event.getServer());
  }

  private static void stopped(ServerStoppedEvent event) {
    release(event.getServer());
  }

  private static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
    if (event.getEntity() instanceof ServerPlayer player) retireCommands(player);
  }

  private static void cloned(PlayerEvent.Clone event) {
    if (event.getOriginal() instanceof ServerPlayer player) retireCommands(player);
  }

  private static void retireCommands(ServerPlayer player) {
    var server = player.getServer();
    if (server == null || !server.isSameThread()) return;
    final ServerSettings scope;
    synchronized (SERVERS) {
      scope = SERVERS.get(server);
    }
    if (scope != null) scope.documents.values().forEach(doc -> doc.retireCommands(player));
  }

  private static void release(MinecraftServer server) {
    final ServerSettings selected;
    synchronized (SERVERS) {
      selected = SERVERS.remove(server);
    }
    if (selected != null) selected.close();
  }

  @Override
  public void close() {
    if (!open) return;
    requireThread(server);
    open = false;
    synchronized (SERVERS) {
      SERVERS.remove(server, this);
    }
    var selected = List.copyOf(documents.values());
    documents.clear();
    try {
      Throwable failure = null;
      for (var document : selected) {
        try {
          document.context.close();
        } catch (RuntimeException | Error failed) {
          if (failure == null) failure = failed;
          else if (failure != failed) failure.addSuppressed(failed);
        }
      }
      if (failure instanceof RuntimeException failed) throw failed;
      if (failure instanceof Error failed) throw failed;
    } finally {
      if (workers != null) workers.shutdown();
      workers = null;
      server = null;
    }
  }
}
