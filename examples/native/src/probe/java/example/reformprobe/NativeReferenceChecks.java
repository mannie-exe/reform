package example.reformprobe;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.reference.NativeReferences;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.reference.ReferenceActions;
import design.inherent.reform.reference.ReferenceLimits;
import design.inherent.reform.reference.ReferenceQuery;
import design.inherent.reform.reference.ReferenceResolution;
import design.inherent.reform.reference.ReferenceStamp;
import design.inherent.reform.value.ValueTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Blocks;

/** Real common-side reference services against the exact public packaged library. */
final class NativeReferenceChecks {
  static CompletionStage<Void> verify(
      MinecraftServer server, OwnerContext context, JsonObject parent) {
    context.requireOwner();
    var checks = java.util.Collections.synchronizedList(new ArrayList<String>());
    BiConsumer<String, Boolean> check =
        (name, valid) -> {
          if (!valid) throw new AssertionError(name);
          checks.add(name);
        };
    var report = new JsonObject();
    report.addProperty("status", "FAIL");
    parent.add("references", report);
    var limits = NativeReferences.Limits.STANDARD;
    var id = DocumentId.parse("reform_native_example_probe:blocks");
    var access = context.access(() -> true, () -> false);
    var actions =
        new ReferenceActions<>(
            context,
            access,
            id,
            ValueTypes.text(limits.maximumKeyLength()),
            NativeReferences.valueType(limits).snapshotPolicy(),
            limits.index());
    var nativeCaptureNanos = new AtomicLong();
    var workerBuildNanos = new AtomicLong();
    var worker = new AtomicBoolean();
    int initialChunks = server.overworld().getChunkSource().getLoadedChunksCount();
    var binding =
        actions.bindAsync(
            "native-blocks",
            stamp -> {
              long start = System.nanoTime();
              var captured =
                  NativeReferences.captureRegistry(
                      context, stamp, BuiltInRegistries.BLOCK, true, limits);
              nativeCaptureNanos.set(System.nanoTime() - start);
              check.accept(
                  "native registry capture does not change loaded chunk count",
                  initialChunks == server.overworld().getChunkSource().getLoadedChunksCount());
              return context.onWorker(
                  () -> {
                    worker.set(!server.isSameThread());
                    long build = System.nanoTime();
                    var index = captured.build();
                    workerBuildNanos.set(System.nanoTime() - build);
                    return index;
                  });
            });
    CompletionStage<Void> work =
        binding.thenCompose(
            stamp -> {
              check.accept("registry index compiled off native thread", worker.get());
              check.accept(
                  "native lookup stamp retains actual owner", stamp.context().equals(context.id()));
              return resolve(context, actions, "stone")
                  .thenApply(
                      result -> {
                        check.accept(
                            "native default namespace lookup resolves registered stone",
                            result.status() == ReferenceResolution.Status.RESOLVED
                                && result.value().orElseThrow().equals(List.of("minecraft:stone")));
                        check.accept(
                            "native registry lookup has captured registry preview",
                            result.preview().get("registry").equals("minecraft:block"));
                        return null;
                      });
            });
    work =
        work.thenCompose(
            ignored ->
                resolve(context, actions, "minecraft:reform_missing")
                    .thenApply(
                        result -> {
                          check.accept(
                              "unknown native ID does not become default air",
                              result.status() == ReferenceResolution.Status.UNKNOWN_REFERENCE);
                          check.accept(
                              "actual native defaulted lookup control returns air",
                              BuiltInRegistries.BLOCK.get(
                                      ResourceLocation.parse("minecraft:reform_missing"))
                                  == Blocks.AIR);
                          return null;
                        }));
    for (String invalid : List.of("", "#", "minecraft:", "Bad ID", "#minecraft:")) {
      work =
          work.thenCompose(
              ignored ->
                  resolve(context, actions, invalid)
                      .thenApply(
                          result -> {
                            check.accept(
                                "native malformed reference " + invalid,
                                result.status() == ReferenceResolution.Status.MALFORMED);
                            return null;
                          }));
    }
    work =
        work.thenCompose(
            ignored ->
                resolve(context, actions, "#minecraft:mineable/pickaxe")
                    .thenApply(
                        result -> {
                          check.accept(
                              "actual native block tag resolves membership",
                              result.status() == ReferenceResolution.Status.RESOLVED
                                  && result.value().orElseThrow().contains("minecraft:stone"));
                          check.accept(
                              "actual native tag preview identifies membership",
                              result.preview().get("kind").equals("tag")
                                  && Integer.parseInt(result.preview().get("members"))
                                      == result.value().orElseThrow().size());
                          return null;
                        }));
    work =
        work.thenCompose(
            ignored ->
                context
                    .onOwner(
                        () -> actions.search(EntryPoint.COMMAND, new ReferenceQuery("stone", 4)))
                    .thenCompose(handle -> handle.completion())
                    .thenApply(
                        result -> {
                          check.accept(
                              "native search uses bounded pages",
                              result.state() == OperationSnapshot.State.COMPLETED
                                  && result.result().orElseThrow().choices().size() <= 4
                                  && result.result().orElseThrow().examined()
                                      <= limits.index().maximumScan());
                          return null;
                        }));
    var supplierCalls = new AtomicInteger();
    work =
        work.thenCompose(
                ignored ->
                    context.onOwner(
                        () ->
                            actions.bind(
                                "absent",
                                stamp ->
                                    NativeReferences.optionalRegistry(
                                        context,
                                        stamp,
                                        false,
                                        () -> {
                                          supplierCalls.incrementAndGet();
                                          throw new AssertionError(
                                              "Absent optional supplier invoked");
                                        },
                                        true,
                                        limits))))
            .thenCompose(stamp -> resolve(context, actions, "minecraft:stone"))
            .thenApply(
                result -> {
                  check.accept(
                      "absent optional source does not invoke native supplier",
                      supplierCalls.get() == 0);
                  check.accept(
                      "absent dependency is distinct from unknown ID",
                      result.status() == ReferenceResolution.Status.MISSING_DEPENDENCY);
                  return null;
                });
    work =
        work.thenCompose(
                ignored ->
                    context
                        .onOwner(
                            () -> {
                              var fixtureKey =
                                  ResourceKey.<String>createRegistryKey(
                                      ResourceLocation.parse(
                                          "reform_native_example_probe:reference_fixture"));
                              var registry =
                                  new MappedRegistry<String>(fixtureKey, Lifecycle.stable());
                              var one =
                                  Registry.registerForHolder(
                                      registry,
                                      ResourceLocation.parse("resource_namespace:one"),
                                      "one");
                              var two =
                                  Registry.registerForHolder(
                                      registry,
                                      ResourceLocation.parse("resource_namespace:two"),
                                      "two");
                              registry.freeze();
                              var empty =
                                  TagKey.create(
                                      fixtureKey,
                                      ResourceLocation.parse("resource_namespace:empty"));
                              var group =
                                  TagKey.create(
                                      fixtureKey,
                                      ResourceLocation.parse("resource_namespace:group"));
                              registry.bindTags(
                                  Map.<TagKey<String>, List<Holder<String>>>of(
                                      empty, List.of(), group, List.of(one, two)));
                              var sourceStamp =
                                  new ReferenceStamp(
                                      context.id(), id, UUID.randomUUID(), "bounded-control");
                              var tiny =
                                  new NativeReferences.Limits(
                                      new ReferenceLimits(1, 8192, 1, 32, 1),
                                      512,
                                      4096,
                                      65536,
                                      limits.snapshots());
                              boolean refused = false;
                              try {
                                NativeReferences.captureRegistry(
                                    context, sourceStamp, registry, true, tiny);
                              } catch (IllegalArgumentException expected) {
                                refused = true;
                              }
                              check.accept(
                                  "native registry producer exhaustion refuses whole capture",
                                  refused);
                              refused = false;
                              try {
                                NativeReferences.captureRegistry(
                                    context,
                                    sourceStamp,
                                    registry,
                                    true,
                                    new NativeReferences.Limits(
                                        limits.index(), 512, 1, 65536, limits.snapshots()));
                              } catch (IllegalArgumentException expected) {
                                refused = true;
                              }
                              check.accept(
                                  "native tag membership producer exhaustion is explicit", refused);
                              for (var malformed :
                                  List.of("bad namespace:item", "#minecraft:stone")) {
                                refused = false;
                                try {
                                  NativeReferences.captureResources(
                                      context, sourceStamp, List.of(malformed), limits);
                                } catch (IllegalArgumentException expected) {
                                  refused = true;
                                }
                                check.accept(
                                    "malformed native resource name refuses complete capture: "
                                        + malformed,
                                    refused);
                              }
                              refused = false;
                              try {
                                NativeReferences.captureResources(
                                    context, sourceStamp, List.of("a".repeat(513)), limits);
                              } catch (IllegalArgumentException expected) {
                                refused = true;
                              }
                              check.accept(
                                  "oversized native resource name refuses before parsing", refused);
                              var storage = NativeReferences.type(id, true, 512);
                              check.accept(
                                  "native reference storage retains authored spelling",
                                  storage
                                      .codec()
                                      .encodeStart(JsonOps.INSTANCE, "stone")
                                      .getOrThrow()
                                      .getAsString()
                                      .equals("stone"));
                              check.accept(
                                  "native reference storage admits unknown syntactically valid ID",
                                  storage
                                      .codec()
                                      .parse(
                                          JsonOps.INSTANCE,
                                          new com.google.gson.JsonPrimitive("other:unknown"))
                                      .result()
                                      .isPresent());
                              check.accept(
                                  "native reference storage rejects malformed ID",
                                  storage
                                      .codec()
                                      .parse(
                                          JsonOps.INSTANCE,
                                          new com.google.gson.JsonPrimitive("Bad ID"))
                                      .error()
                                      .isPresent());
                              var foreign =
                                  new ReferenceStamp(
                                      UUID.randomUUID(), id, UUID.randomUUID(), "foreign");
                              refused = false;
                              try {
                                NativeReferences.captureRegistry(
                                    context, foreign, registry, true, limits);
                              } catch (IllegalArgumentException expected) {
                                refused = true;
                              }
                              check.accept("native capture rejects a foreign owner stamp", refused);
                              return actions.bindAsync(
                                  "fixture",
                                  stamp -> {
                                    var capture =
                                        NativeReferences.captureRegistry(
                                            context, stamp, registry, true, limits);
                                    return context.onWorker(capture::build);
                                  });
                            })
                        .thenCompose(stage -> stage))
            .thenCompose(stamp -> resolve(context, actions, "#resource_namespace:empty"))
            .thenApply(
                result -> {
                  check.accept(
                      "real native named empty tag resolves as an empty value",
                      result.status() == ReferenceResolution.Status.RESOLVED
                          && result.value().orElseThrow().isEmpty());
                  return null;
                });
    work =
        work.thenCompose(
            ignored ->
                resolve(context, actions, "resource_namespace:one")
                    .thenApply(
                        result -> {
                          check.accept(
                              "resource namespace does not imply a missing mod",
                              result.status() == ReferenceResolution.Status.RESOLVED);
                          return null;
                        }));
    var resources = new AtomicInteger();
    work =
        work.thenCompose(
                ignored ->
                    context
                        .onOwner(
                            () -> {
                              var names =
                                  server
                                      .getResourceManager()
                                      .listResources(
                                          "tags", name -> name.getPath().endsWith(".json"))
                                      .keySet();
                              check.accept(
                                  "actual server resource names are available", !names.isEmpty());
                              resources.set(names.size());
                              String first =
                                  names.stream()
                                      .map(ResourceLocation::toString)
                                      .sorted()
                                      .findFirst()
                                      .orElseThrow();
                              return actions
                                  .bindAsync(
                                      "resources",
                                      stamp -> {
                                        var captured =
                                            NativeReferences.captureResources(
                                                context,
                                                stamp,
                                                names.stream()
                                                    .map(ResourceLocation::toString)
                                                    .toList(),
                                                limits);
                                        return context.onWorker(captured::build);
                                      })
                                  .thenApply(stamp -> first);
                            })
                        .thenCompose(stage -> stage))
            .thenCompose(first -> resolve(context, actions, first))
            .thenApply(
                result -> {
                  check.accept(
                      "admitted native resource name resolves without reading content",
                      result.status() == ReferenceResolution.Status.RESOLVED
                          && result.preview().get("kind").equals("resource"));
                  return null;
                });
    return work.handle((ignored, failure) -> failure)
        .thenCompose(
            failure ->
                context.onOwner(
                    () -> {
                      try {
                        report.addProperty("blockKeys", BuiltInRegistries.BLOCK.keySet().size());
                        report.addProperty(
                            "blockTags", BuiltInRegistries.BLOCK.getTagNames().count());
                        report.addProperty("resources", resources.get());
                        report.addProperty("captureNanos", nativeCaptureNanos.get());
                        report.addProperty("workerBuildNanos", workerBuildNanos.get());
                        report.add("checks", new Gson().toJsonTree(checks));
                        if (failure != null)
                          throw new java.util.concurrent.CompletionException(failure);
                        report.addProperty("status", "PASS");
                        return null;
                      } finally {
                        actions.close();
                        access.close();
                      }
                    }));
  }

  private static <V> CompletionStage<ReferenceResolution<V>> resolve(
      OwnerContext context, ReferenceActions<String, V> actions, String key) {
    return context
        .onOwner(() -> actions.resolve(EntryPoint.INTERNAL, key))
        .thenCompose(handle -> handle.completion())
        .thenApply(
            result -> {
              if (result.state() != OperationSnapshot.State.COMPLETED)
                throw new AssertionError("Reference service did not complete: " + result.report());
              return result.result().orElseThrow();
            });
  }

  private NativeReferenceChecks() {}
}
