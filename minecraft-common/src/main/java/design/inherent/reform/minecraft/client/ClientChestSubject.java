package design.inherent.reform.minecraft.client;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.util.Objects;

/**
 * An isolated single chest reference pose, without inventory, world, position, or arbitrary NBT.
 */
public record ClientChestSubject(Kind kind, float lidProgress) {
  public enum Kind {
    NORMAL,
    TRAPPED
  }

  private static final Codec<Kind> KIND =
      Codec.STRING.comapFlatMap(
          value ->
              switch (value) {
                case "normal" -> DataResult.success(Kind.NORMAL);
                case "trapped" -> DataResult.success(Kind.TRAPPED);
                default -> DataResult.error(() -> "Unsupported isolated chest kind");
              },
          value -> value == Kind.NORMAL ? "normal" : "trapped");
  private static final Codec<Float> LID =
      Codec.FLOAT.validate(
          value ->
              Float.isFinite(value) && value >= 0 && value <= 1
                  ? DataResult.success(value)
                  : DataResult.error(
                      () -> "Chest lid progress must be finite and between zero and one"));
  private static final ValueType<ClientChestSubject> TYPE =
      ValueType.of(
          RecordCodecBuilder.create(
              instance ->
                  instance
                      .group(
                          KIND.fieldOf("kind").forGetter(ClientChestSubject::kind),
                          LID.fieldOf("lidProgress").forGetter(ClientChestSubject::lidProgress))
                      .apply(instance, ClientChestSubject::new)),
          new ValueShape.Custom("reform:isolated_chest"),
          SnapshotPolicy.immutable());

  public ClientChestSubject {
    Objects.requireNonNull(kind);
    if (!Float.isFinite(lidProgress) || lidProgress < 0 || lidProgress > 1)
      throw new IllegalArgumentException(
          "Chest lid progress must be finite and between zero and one");
  }

  public static ValueType<ClientChestSubject> type() {
    return TYPE;
  }
}
