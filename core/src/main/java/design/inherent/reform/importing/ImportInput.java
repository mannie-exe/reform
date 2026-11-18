package design.inherent.reform.importing;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Owned admitted bytes. Its human label is provenance, never a path or read/write capability. */
public final class ImportInput {
  private final String label;
  private final String sha256;
  private final byte[] bytes;

  public ImportInput(String label, byte[] bytes, int maximumBytes) {
    Objects.requireNonNull(label);
    Objects.requireNonNull(bytes);
    if (label.isBlank() || label.length() > 256 || maximumBytes < 1 || bytes.length > maximumBytes)
      throw new IllegalArgumentException("Import source exceeds its admission policy");
    this.label = label;
    this.bytes = bytes.clone();
    try {
      sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(this.bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }

  public String label() {
    return label;
  }

  public String sha256() {
    return sha256;
  }

  public int size() {
    return bytes.length;
  }

  public byte[] bytes() {
    return bytes.clone();
  }
}
