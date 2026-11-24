package design.inherent.reform.minecraft.client;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.util.Objects;

/** A curated native animal reference, without a live entity, equipment, or arbitrary NBT. */
public record ClientEntitySubject(Kind kind, boolean baby) {
  public enum Kind {
    COW,
    PIG
  }

  private static final Codec<Kind> KIND =
      Codec.STRING.comapFlatMap(
          value ->
              switch (value) {
                case "cow" -> DataResult.success(Kind.COW);
                case "pig" -> DataResult.success(Kind.PIG);
                default -> DataResult.error(() -> "Unsupported curated entity kind");
              },
          value -> value == Kind.COW ? "cow" : "pig");
  private static final ValueType<ClientEntitySubject> TYPE =
      ValueType.of(
          RecordCodecBuilder.create(
              instance ->
                  instance
                      .group(
                          KIND.fieldOf("kind").forGetter(ClientEntitySubject::kind),
                          Codec.BOOL.fieldOf("baby").forGetter(ClientEntitySubject::baby))
                      .apply(instance, ClientEntitySubject::new)),
          new ValueShape.Custom("reform:curated_entity"),
          SnapshotPolicy.immutable());

  public ClientEntitySubject {
    Objects.requireNonNull(kind);
  }

  public static ValueType<ClientEntitySubject> type() {
    return TYPE;
  }
}
