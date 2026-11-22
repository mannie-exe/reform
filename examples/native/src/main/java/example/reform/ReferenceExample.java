package example.reform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.minecraft.client.ClientFileSettings;
import design.inherent.reform.minecraft.reference.NativeReferences;
import design.inherent.reform.neoforge.client.NeoForgeClientRegistration;
import design.inherent.reform.reference.ReferenceActions;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

/**
 * Explicit native source preparation; ordinary painting/editing performs no resource enumeration.
 */
public final class ReferenceExample {
  public record Profile(String block, String resource, String optional, String unbound) {}

  public static final DocumentId BLOCKS = DocumentId.parse(NativeExample.ID + ":block_choices");
  public static final DocumentId RESOURCES =
      DocumentId.parse(NativeExample.ID + ":resource_choices");
  public static final DocumentId OPTIONAL =
      DocumentId.parse(NativeExample.ID + ":optional_choices");
  public static final DocumentId UNBOUND = DocumentId.parse(NativeExample.ID + ":unbound_choices");
  private static final NativeReferences.Limits LIMITS = NativeReferences.Limits.STANDARD;
  private static final FieldKey<String> BLOCK =
      FieldKey.of("block", NativeReferences.type(BLOCKS, true, 32));
  private static final FieldKey<String> RESOURCE =
      FieldKey.of("resource", NativeReferences.type(RESOURCES, false, 512));
  private static final FieldKey<String> OPTIONAL_FIELD =
      FieldKey.of("optional", NativeReferences.type(OPTIONAL, true, 512));
  private static final FieldKey<String> UNBOUND_FIELD =
      FieldKey.of("unbound", NativeReferences.type(UNBOUND, false, 512));
  public static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse(NativeExample.ID + ":references"),
          1,
          RecordType.<Profile>builder(
                  v ->
                      new Profile(
                          v.get(BLOCK),
                          v.get(RESOURCE),
                          v.get(OPTIONAL_FIELD),
                          v.get(UNBOUND_FIELD)))
              .field(BLOCK, Profile::block)
              .field(RESOURCE, Profile::resource)
              .field(OPTIONAL_FIELD, Profile::optional)
              .field(UNBOUND_FIELD, Profile::unbound)
              .build(),
          () ->
              new Profile(
                  "stone", "minecraft:missing_resource", "optional:missing", "minecraft:manual"));
  private static ClientFileSettings<Profile> settings;
  private static Map<DocumentId, ReferenceActions<String, List<String>>> sources;

  static void register(IEventBus bus, ModContainer container) {
    settings =
        ClientFileSettings.clientJson(NeoForgeClientRegistration.of(bus, container), DEFINITION);
  }

  public static ClientFileSettings<Profile> settings() {
    return java.util.Objects.requireNonNull(settings);
  }

  public static Map<DocumentId, ReferenceActions<String, List<String>>> sources() {
    if (!Minecraft.getInstance().isSameThread())
      throw new IllegalStateException("Client owner required");
    if (sources == null) {
      sources =
          Map.of(
              BLOCKS,
                  settings.references(
                      BLOCKS,
                      ValueTypes.text(512),
                      NativeReferences.valueType(LIMITS).snapshotPolicy(),
                      LIMITS.index()),
              RESOURCES,
                  settings.references(
                      RESOURCES,
                      ValueTypes.text(512),
                      NativeReferences.valueType(LIMITS).snapshotPolicy(),
                      LIMITS.index()),
              OPTIONAL,
                  settings.references(
                      OPTIONAL,
                      ValueTypes.text(512),
                      NativeReferences.valueType(LIMITS).snapshotPolicy(),
                      LIMITS.index()));
    }
    return sources;
  }

  /**
   * An explicit preparation/reload operation. Callers retain this completion; no background retry.
   */
  public static CompletionStage<Void> prepareSources(String revision) {
    var bindings = sources();
    var blocks =
        bindings
            .get(BLOCKS)
            .bindCaptured(
                revision,
                (owner, stamp) ->
                    NativeReferences.captureRegistry(
                        owner, stamp, BuiltInRegistries.BLOCK, true, LIMITS),
                NativeReferences.Capture::build);
    var resources =
        bindings
            .get(RESOURCES)
            .bindCaptured(
                revision,
                (owner, stamp) ->
                    NativeReferences.captureResources(
                        owner,
                        stamp,
                        Minecraft.getInstance()
                            .getResourceManager()
                            .listResources("lang", id -> id.getPath().endsWith(".json"))
                            .keySet()
                            .stream()
                            .map(Object::toString)
                            .toList(),
                        LIMITS),
                NativeReferences.Capture::build);
    var optional =
        bindings
            .get(OPTIONAL)
            .bindCaptured(
                revision,
                (owner, stamp) ->
                    NativeReferences.captureOptionalRegistry(
                        owner,
                        stamp,
                        false,
                        () -> {
                          throw new AssertionError("Absent optional supplier was invoked");
                        },
                        true,
                        LIMITS),
                NativeReferences.Capture::build);
    return CompletableFuture.allOf(
            blocks.toCompletableFuture(),
            resources.toCompletableFuture(),
            optional.toCompletableFuture())
        .minimalCompletionStage();
  }

  /** Open after explicit preparation; stale resource sources require another deliberate prepare. */
  public static Screen screen(Screen parent) {
    return settings.screen(parent, Component.literal("Native reference choices"), sources());
  }

  private ReferenceExample() {}
}
