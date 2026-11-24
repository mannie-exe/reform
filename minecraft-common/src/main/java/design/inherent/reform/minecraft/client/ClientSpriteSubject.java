package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.minecraft.reference.NativeReferences;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.Objects;

/** A static physical frame from a known loaded atlas, never its live animation position. */
public record ClientSpriteSubject(Atlas atlas, String sprite, int sourceFrame) {
  public enum Atlas {
    GUI,
    BLOCKS
  }

  private static final FieldKey<Atlas> ATLAS =
      FieldKey.of("atlas", ValueTypes.enumeration(Atlas.class));
  private static final FieldKey<String> SPRITE =
      FieldKey.of(
          "sprite", NativeReferences.type(DocumentId.parse("reform:loaded_sprite"), false, 512));
  private static final FieldKey<Integer> FRAME =
      FieldKey.of("sourceFrame", ValueTypes.integer(0, Integer.MAX_VALUE));
  private static final ValueType<ClientSpriteSubject> TYPE =
      RecordType.<ClientSpriteSubject>builder(
              values ->
                  new ClientSpriteSubject(values.get(ATLAS), values.get(SPRITE), values.get(FRAME)))
          .field(ATLAS, ClientSpriteSubject::atlas)
          .field(SPRITE, ClientSpriteSubject::sprite)
          .field(FRAME, ClientSpriteSubject::sourceFrame)
          .build();

  public ClientSpriteSubject {
    Objects.requireNonNull(atlas);
    if (Objects.requireNonNull(sprite).length() > 512 || sourceFrame < 0)
      throw new IllegalArgumentException(
          "A loaded sprite needs a bounded identity and nonnegative source frame");
    DocumentId.parse(sprite);
  }

  public static ValueType<ClientSpriteSubject> type() {
    return TYPE;
  }
}
