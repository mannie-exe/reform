package example.reform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordTypes;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.neoforge.client.ClientFileSettings;
import design.inherent.reform.owner.ApplicationEffects;
import design.inherent.reform.owner.LiveApplication;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.storage.TextStore;
import design.inherent.reform.value.ValueTypes;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLPaths;

/** Ordinary format helpers and an independently implemented, explicit owned-text adapter. */
public final class FileFormatsExample {
  public record Choices(boolean enabled, int size, String title) {}

  public record Counter(int count) {}

  public static final AtomicInteger DEFAULT_CALLS = new AtomicInteger();
  public static final DocumentDefinition<Choices> CHOICES =
      new DocumentDefinition<>(
          DocumentId.parse(NativeExample.ID + ":legacy_choices"),
          1,
          RecordTypes.record(Choices.class)
              .field(
                  "enabled",
                  FieldKey.of("enabled", ValueTypes.defaultedField(ValueTypes.bool(), true)))
              .field("size", FieldKey.of("size", ValueTypes.integer(1, 32)))
              .field("title", FieldKey.of("title", ValueTypes.text(128)))
              .build(),
          () -> {
            DEFAULT_CALLS.incrementAndGet();
            return new Choices(true, 4, "before");
          });
  public static final DocumentDefinition<Counter> BARE = counter("legacy_bare");
  public static final DocumentDefinition<Counter> CUSTOM = counter("legacy_custom");
  public static final DocumentDefinition<Choices> BROKEN =
      new DocumentDefinition<>(
          DocumentId.parse(NativeExample.ID + ":legacy_broken"),
          1,
          CHOICES.type(),
          () -> {
            DEFAULT_CALLS.incrementAndGet();
            return new Choices(true, 4, "before");
          });
  public static final int VALIDATED_DEFAULTS = DEFAULT_CALLS.get();
  private static ClientFileSettings<Choices> properties, broken;
  private static ClientFileSettings<Counter> bare, custom;
  private static volatile Counter effectiveCounter;

  private static DocumentDefinition<Counter> counter(String key) {
    return new DocumentDefinition<>(
        DocumentId.parse(NativeExample.ID + ":" + key),
        1,
        RecordTypes.record(Counter.class)
            .field("count", FieldKey.of("count", ValueTypes.integer(1, 32)))
            .build(),
        () -> {
          DEFAULT_CALLS.incrementAndGet();
          return new Counter(4);
        });
  }

  static void register(IEventBus bus, ModContainer container) {
    properties =
        ClientFileSettings.properties(
            bus, container, CHOICES, "legacy-tools", "choices.properties");
    bare =
        ClientFileSettings.existingJson(
            bus, container, BARE, JsonStore.Format.EXISTING_BARE, "legacy-tools", "bare.json");
    broken =
        ClientFileSettings.properties(bus, container, BROKEN, "legacy-tools", "broken.properties");
    custom =
        ClientFileSettings.clientFile(
            bus,
            container,
            new CounterStore(),
            ApplicationEffects.OWNER_PUBLICATION,
            new LiveApplication<>(
                candidate -> {
                  if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException();
                  return candidate.equals(effectiveCounter);
                },
                candidate -> {
                  if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException();
                  effectiveCounter = candidate;
                }));
    properties.registerCommands("reformproperties");
  }

  public static ClientFileSettings<Choices> properties() {
    return Objects.requireNonNull(properties);
  }

  public static ClientFileSettings<Choices> broken() {
    return Objects.requireNonNull(broken);
  }

  public static ClientFileSettings<Counter> bare() {
    return Objects.requireNonNull(bare);
  }

  public static ClientFileSettings<Counter> custom() {
    return Objects.requireNonNull(custom);
  }

  public static Counter effectiveCounter() {
    return Objects.requireNonNull(effectiveCounter);
  }

  private static final class CounterStore extends TextStore<Counter> {
    CounterStore() {
      super(
          CUSTOM,
          new OwnedFile(
              StorageBinding.relative(
                  FMLPaths.CONFIGDIR.get().toAbsolutePath(), "legacy-tools", "counter.txt"),
              4096),
          new JsonLimits(4096, 8, 64, 128, 16, 128),
          DraftLimits.CONFIGURATION,
          "example-counter",
          "Explicit legacy counter syntax");
    }

    @Override
    protected DraftTree decode(String text) {
      if (!text.matches("count=[0-9]{1,2}\\r?\\n"))
        throw new IllegalArgumentException("Expected one bounded counter assignment");
      return DraftTree.from(
          definition.type(), new Counter(Integer.parseInt(text.substring(6).strip())), draftLimits);
    }

    @Override
    protected String encode(Read<Counter> baseline, Candidate<Counter> candidate) {
      return "count=" + candidate.value().count() + "\n";
    }

    @Override
    protected void validateEncoded(String text) {
      decode(text);
    }
  }

  private FileFormatsExample() {}
}
