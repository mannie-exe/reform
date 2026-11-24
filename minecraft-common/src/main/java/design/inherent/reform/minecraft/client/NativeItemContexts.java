package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.reference.ReferenceStamp;
import java.lang.ref.WeakReference;
import java.util.UUID;
import net.minecraft.client.Minecraft;

/** Actual native registry/model context, without retaining a disconnected level or connection. */
final class NativeItemContexts {
  private static WeakReference<Object> level = new WeakReference<>(null),
      connection = new WeakReference<>(null);
  private static boolean hadLevel, hadConnection;
  private static UUID registry = UUID.randomUUID();
  private static ReferenceStamp packs, combined;

  static UUID registry() {
    ClientSettingsRuntime.requireClientThread();
    var client = Minecraft.getInstance();
    Object actualLevel = client.level, actualConnection = client.getConnection();
    if (level.get() != actualLevel
        || connection.get() != actualConnection
        || hadLevel != (actualLevel != null)
        || hadConnection != (actualConnection != null)) {
      level = new WeakReference<>(actualLevel);
      connection = new WeakReference<>(actualConnection);
      hadLevel = actualLevel != null;
      hadConnection = actualConnection != null;
      registry = UUID.randomUUID();
      combined = null;
    }
    return registry;
  }

  static ReferenceStamp source() {
    registry();
    var current = LoadedGraphicResources.snapshot().stamp();
    if (combined == null || !current.equals(packs)) {
      packs = current;
      combined =
          new ReferenceStamp(
              current.context(),
              DocumentId.parse("reform:loaded_item_resources"),
              UUID.randomUUID(),
              current.revision());
    }
    return combined;
  }

  private NativeItemContexts() {}
}
