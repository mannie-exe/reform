package example.reform.platform;

import com.google.gson.JsonObject;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.reference.NativeReferences;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.reference.ReferenceStamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;

/** Small native-owner supplement; the full portable reference scenes retain their own evidence. */
final class PlatformRegisteredReferences {
  static CompletionStage<Void> verify(
      MinecraftServer server, OwnerContext context, JsonObject report) {
    context.requireOwner();
    int chunks = server.overworld().getChunkSource().getLoadedChunksCount();
    var stamp =
        new ReferenceStamp(
            context.id(),
            DocumentId.parse(PlatformServer.ID + ":owner_registry"),
            UUID.randomUUID(),
            "native");
    var capture =
        NativeReferences.captureRegistry(
            context, stamp, BuiltInRegistries.BLOCK, true, NativeReferences.Limits.STANDARD);
    if (chunks != server.overworld().getChunkSource().getLoadedChunksCount())
      throw new AssertionError("Owner registry supplement loaded chunks");
    return context.onWorker(
        () -> {
          if (!capture
              .build()
              .resolve("stone")
              .value()
              .orElseThrow()
              .equals(List.of("minecraft:stone")))
            throw new AssertionError("Owner registry supplement lost native namespace resolution");
          var result = new JsonObject();
          result.addProperty("status", "PASS");
          result.addProperty(
              "scope",
              "Representative native block capture and namespace resolution; full references qualified separately");
          report.add("references", result);
          return (Void) null;
        });
  }

  private PlatformRegisteredReferences() {}
}
