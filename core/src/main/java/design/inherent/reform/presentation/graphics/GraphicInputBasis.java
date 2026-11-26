package design.inherent.reform.presentation.graphics;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.DraftTarget;
import design.inherent.reform.reference.ReferenceStamp;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Source-kind and exact audition transformation, separate from the actual base draft stamp. */
public sealed interface GraphicInputBasis {
  GraphicSourceStamp.Kind kind();

  record Draft() implements GraphicInputBasis {
    public GraphicSourceStamp.Kind kind() {
      return GraphicSourceStamp.Kind.DRAFT;
    }
  }

  record Content(long bytes, String sha256) {
    public Content {
      if (bytes < 0 || !Objects.requireNonNull(sha256).matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException(
            "Content requires its exact UTF-8 byte length and SHA-256");
    }

    /** Stream UTF-8 without allocating another candidate-sized byte array. */
    public static Content of(String text) {
      Objects.requireNonNull(text);
      final MessageDigest digest;
      try {
        digest = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException impossible) {
        throw new IllegalStateException(impossible);
      }
      long bytes = 0;
      for (int i = 0; i < text.length(); i++) {
        int c = text.charAt(i);
        if (Character.isHighSurrogate((char) c)
            && i + 1 < text.length()
            && Character.isLowSurrogate(text.charAt(i + 1))) {
          c = Character.toCodePoint((char) c, text.charAt(++i));
        } else if (Character.isSurrogate((char) c)) c = '?';
        if (c < 0x80) {
          digest.update((byte) c);
          bytes++;
        } else if (c < 0x800) {
          digest.update((byte) (0xc0 | c >> 6));
          digest.update((byte) (0x80 | c & 63));
          bytes += 2;
        } else if (c < 0x10000) {
          digest.update((byte) (0xe0 | c >> 12));
          digest.update((byte) (0x80 | c >> 6 & 63));
          digest.update((byte) (0x80 | c & 63));
          bytes += 3;
        } else {
          digest.update((byte) (0xf0 | c >> 18));
          digest.update((byte) (0x80 | c >> 12 & 63));
          digest.update((byte) (0x80 | c >> 6 & 63));
          digest.update((byte) (0x80 | c & 63));
          bytes += 4;
        }
      }
      return new Content(bytes, HexFormat.of().formatHex(digest.digest()));
    }
  }

  record Audition(
      DraftTarget target,
      ReferenceStamp reference,
      String role,
      String key,
      DocumentId transformation,
      int version,
      Content content)
      implements GraphicInputBasis {
    public Audition {
      Objects.requireNonNull(target);
      Objects.requireNonNull(reference);
      Objects.requireNonNull(transformation);
      Objects.requireNonNull(content);
      if (target.version().isEmpty()
          || Objects.requireNonNull(role).isBlank()
          || role.length() > 128
          || Objects.requireNonNull(key).length() > 32768
          || version < 1)
        throw new IllegalArgumentException(
            "An audition needs an exact target and bounded transformation metadata");
    }

    public GraphicSourceStamp.Kind kind() {
      return GraphicSourceStamp.Kind.AUDITION;
    }
  }
}
