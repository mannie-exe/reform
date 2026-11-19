package design.inherent.reform.neoforge.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.storage.StorageBinding;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** Physical-client lifetime and bounded workers, independent of a document's native/file format. */
final class ClientSettingsRuntime {
  private record Document(Object owner, Runnable close, String ownedFile, Object nativeIdentity) {}

  private static final Object GATE = new Object();
  private static final Map<DocumentId, Document> documents = new LinkedHashMap<>();
  private static final Map<UUID, OwnerContext> contexts = new LinkedHashMap<>();
  private static ThreadPoolExecutor workers;
  private static boolean closing;

  static {
    NeoForge.EVENT_BUS.addListener(ClientSettingsRuntime::shutdown);
  }

  /** Metadata registration can occur during mod construction; no worker or file is initialized. */
  static void registerDocument(DocumentId id, Object owner, Runnable close) {
    register(id, owner, close, null, null);
  }

  /** Conservative portable collision policy; registration performs no filesystem lookup. */
  static void registerFile(DocumentId id, Object owner, StorageBinding binding, Runnable close) {
    register(id, owner, close, portable(binding.path()), null);
  }

  /** Native filenames retain their native spelling/rules; metadata admission performs no IO. */
  static void registerNative(DocumentId id, Object owner, Object spec, Path file, Runnable close) {
    register(id, owner, close, portable(file), Objects.requireNonNull(spec));
  }

  /** Bind the actual loaded path without trusting a predicted root as the source authority. */
  static void nativeFile(DocumentId id, Object owner, Path file) {
    requireClientThread();
    var path = portable(file);
    synchronized (GATE) {
      var current = documents.get(id);
      if (closing
          || current == null
          || current.owner() != owner
          || current.nativeIdentity() == null)
        throw new IllegalStateException("Native client registration is closed or changed");
      if (documents.values().stream()
          .anyMatch(value -> value != current && path.equals(value.ownedFile())))
        throw new IllegalArgumentException("The native file already has another client owner");
      documents.put(
          id, new Document(current.owner(), current.close(), path, current.nativeIdentity()));
    }
  }

  private static String portable(Path path) {
    return Objects.requireNonNull(path)
        .toAbsolutePath()
        .normalize()
        .toString()
        .toLowerCase(Locale.ROOT);
  }

  private static void register(
      DocumentId id, Object owner, Runnable close, String ownedFile, Object nativeIdentity) {
    Objects.requireNonNull(id);
    var document =
        new Document(
            Objects.requireNonNull(owner),
            Objects.requireNonNull(close),
            ownedFile,
            nativeIdentity);
    synchronized (GATE) {
      if (closing) throw new IllegalStateException("Client settings registration is closed");
      if (documents.size() >= 128)
        throw new IllegalStateException("Client document registration limit reached");
      if (ownedFile != null
          && documents.values().stream().anyMatch(value -> ownedFile.equals(value.ownedFile())))
        throw new IllegalArgumentException("This owned client file already has a registered owner");
      if (nativeIdentity != null
          && documents.values().stream()
              .anyMatch(value -> value.nativeIdentity() == nativeIdentity))
        throw new IllegalArgumentException(
            "This native client spec already has a registered owner");
      if (documents.putIfAbsent(id, document) != null)
        throw new IllegalArgumentException("This client document already has an owner");
    }
  }

  static void unregisterDocument(DocumentId id, Object owner) {
    synchronized (GATE) {
      var current = documents.get(id);
      if (current != null && current.owner() == owner) documents.remove(id);
    }
  }

  /** Shorter-lived contexts have their own admission budget, separate from registered documents. */
  static void manageContext(OwnerContext context) {
    requireClientThread();
    synchronized (GATE) {
      if (closing || contexts.size() >= 128)
        throw new IllegalStateException("Client context admission is closed or full");
      if (contexts.putIfAbsent(context.id(), context) != null)
        throw new IllegalArgumentException("Client context already managed");
      try {
        context.onClose(
            () -> {
              synchronized (GATE) {
                contexts.remove(context.id());
              }
            });
      } catch (RuntimeException failed) {
        contexts.remove(context.id());
        throw failed;
      }
    }
  }

  /** Borrow the same pool without creating/initializing any document or taking pool ownership. */
  static Executor workers() {
    requireClientThread();
    synchronized (GATE) {
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
  }

  static void requireClientThread() {
    if (!Minecraft.getInstance().isSameThread())
      throw new IllegalStateException("Client settings require the native client thread");
  }

  private static void shutdown(GameShuttingDownEvent event) {
    final List<Document> selected;
    final List<OwnerContext> selectedContexts;
    final ThreadPoolExecutor executor;
    synchronized (GATE) {
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
      for (var document : selected) {
        try {
          document.close().run();
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

  private ClientSettingsRuntime() {}
}
