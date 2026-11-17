package design.inherent.reform.storage;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Only live readers/writers retain a key; different bindings to one path coordinate. */
final class PathLocks {
  @FunctionalInterface
  interface Operation<T> {
    T run() throws IOException;
  }

  <T> T withLock(String key, Operation<T> operation) throws IOException {
    var lease = acquire(key);
    try {
      return operation.run();
    } finally {
      lease.close();
    }
  }

  private static final class Entry {
    final ReentrantLock lock = new ReentrantLock();
    int references;
  }

  final class Lease implements AutoCloseable {
    private final String key;
    private final Entry entry;
    private boolean closed;

    private Lease(String key, Entry entry) {
      this.key = key;
      this.entry = entry;
    }

    @Override
    public void close() {
      if (closed) throw new IllegalStateException("Path lease already closed");
      entry.lock.unlock();
      entries.compute(key, (ignored, current) -> --current.references == 0 ? null : current);
      closed = true;
    }
  }

  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

  Lease acquire(String key) {
    var entry =
        entries.compute(
            key,
            (ignored, current) -> {
              if (current == null) current = new Entry();
              current.references++;
              return current;
            });
    entry.lock.lock();
    return new Lease(key, entry);
  }

  int retainedKeys() {
    return entries.size();
  }
}
