package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.reference.ReferenceStamp;
import java.util.UUID;
import net.minecraft.server.packs.resources.ResourceManager;

/** Actual client-resource reload observations. No polling, resource discovery, or game mutation. */
public final class LoadedGraphicResources {
  private static final DocumentId SOURCE = DocumentId.parse("reform:loaded_client_resources");
  private static Snapshot current = new Snapshot(null, stamp(UUID.randomUUID()), false, null);

  record Snapshot(
      ResourceManager manager, ReferenceStamp stamp, boolean ready, Throwable failure) {}

  private static ReferenceStamp stamp(UUID context) {
    return new ReferenceStamp(context, SOURCE, UUID.randomUUID(), "loaded-client-resources");
  }

  /** Native hook only; reload start invalidates old uses before any pack can be replaced. */
  public static synchronized ReferenceStamp begin(ResourceManager manager) {
    var context = current.manager() == manager ? current.stamp().context() : UUID.randomUUID();
    current = new Snapshot(manager, stamp(context), false, null);
    return current.stamp();
  }

  /** A late completion from a superseded reload cannot mark a new generation ready. */
  public static synchronized void complete(ReferenceStamp reload, Throwable failed) {
    if (current.stamp().equals(reload))
      current = new Snapshot(current.manager(), stamp(reload.context()), failed == null, failed);
  }

  /** Closing a resource owner invalidates its generation without closing anything on its behalf. */
  public static synchronized void closed(ResourceManager manager) {
    if (current.manager() == manager)
      current = new Snapshot(null, stamp(current.stamp().context()), false, null);
  }

  static synchronized Snapshot snapshot() {
    return current;
  }

  private LoadedGraphicResources() {}
}
