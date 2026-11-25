package design.inherent.reform.presentation;

import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.SnapshotContext;
import design.inherent.reform.value.ValueType;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A view-local data contract. Bookmarks retain bounded encoded data, never the supplied instance.
 */
public final class ViewStateKey<S> {
  public static final JsonLimits STANDARD = new JsonLimits(16_384, 32, 4096, 8192, 256, 1024);
  private final DocumentId id;
  private final ValueType<S> type;
  private final JsonLimits limits;

  public ViewStateKey(DocumentId id, ValueType<S> type) {
    this(id, type, STANDARD);
  }

  /** Custom bounds may narrow the library envelope, not create unbounded bookmark storage. */
  public ViewStateKey(DocumentId id, ValueType<S> type, JsonLimits limits) {
    this.id = Objects.requireNonNull(id);
    this.type = Objects.requireNonNull(type);
    this.limits = Objects.requireNonNull(limits);
    if (limits.maximumBytes() > STANDARD.maximumBytes()
        || limits.maximumDepth() > STANDARD.maximumDepth()
        || limits.maximumNodes() > STANDARD.maximumNodes()
        || limits.maximumStringCharacters() > STANDARD.maximumStringCharacters()
        || limits.maximumNumberCharacters() > STANDARD.maximumNumberCharacters()
        || limits.maximumAbsoluteScale() > STANDARD.maximumAbsoluteScale())
      throw new IllegalArgumentException("View state exceeds the library bookmark envelope");
  }

  public DocumentId id() {
    return id;
  }

  public JsonLimits limits() {
    return limits;
  }

  private S freeze(S value) {
    return type.freeze(
        value,
        new SnapshotContext(
            new SnapshotContext.Limits(limits.maximumDepth(), limits.maximumNodes())));
  }

  public Snapshot<S> capture(S value) {
    var encoded = type.codec().encodeStart(JsonOps.INSTANCE, freeze(Objects.requireNonNull(value)));
    if (encoded.error().isPresent())
      throw new IllegalArgumentException(
          "View state cannot be encoded: " + encoded.error().orElseThrow().message());
    String json = encoded.result().orElseThrow().toString();
    // Validate both directions before replacing a previously usable bookmark.
    decode(json);
    return new Snapshot<>(this, json);
  }

  private S decode(String json) {
    try {
      var decoded = JsonInput.read(json, limits).decode(type.codec());
      if (decoded.error().isPresent())
        throw new IllegalArgumentException(
            "View state cannot be restored: " + decoded.error().orElseThrow().message());
      return freeze(decoded.result().orElseThrow());
    } catch (design.inherent.reform.serialization.JsonInputException invalid) {
      throw new IllegalArgumentException(
          "View state exceeds its declared data contract: " + invalid.code(), invalid);
    }
  }

  public static final class Snapshot<S> {
    private final ViewStateKey<S> key;
    private final String json;

    private Snapshot(ViewStateKey<S> key, String json) {
      this.key = key;
      this.json = json;
    }

    public ViewStateKey<S> key() {
      return key;
    }

    /** A fresh owned value on every read; mutable codec values cannot mutate this bookmark. */
    public S value() {
      return key.decode(json);
    }

    public int encodedBytes() {
      return json.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Exact retained data identity; it does not compare captions or another key's schema. */
    public boolean sameData(Snapshot<?> other) {
      return other != null && key == other.key && json.equals(other.json);
    }
  }
}
