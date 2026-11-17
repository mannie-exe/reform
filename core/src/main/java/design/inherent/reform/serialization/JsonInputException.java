package design.inherent.reform.serialization;

import java.io.IOException;

/** Stable admission code and source location; the location is not an editor row identity. */
public final class JsonInputException extends IOException {
  private static final long serialVersionUID = 1L;
  private final String code;
  private final String location;

  public JsonInputException(String code, String location, String message) {
    super(message);
    this.code = code;
    this.location = location;
  }

  public String code() {
    return code;
  }

  public String location() {
    return location;
  }
}
