package design.inherent.reform.minecraft.client;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Native row identity does not constrain or normalize a source's exact UTF-16 key. */
final class CompletionIdentity {
  private CompletionIdentity() {}

  static String key(String raw) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (int i = 0; i < raw.length(); i++) {
        char c = raw.charAt(i);
        digest.update((byte) (c >>> 8));
        digest.update((byte) c);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("The Java runtime must provide SHA-256", impossible);
    }
  }
}
