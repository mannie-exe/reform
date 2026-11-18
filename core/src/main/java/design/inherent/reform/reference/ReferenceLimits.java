package design.inherent.reform.reference;

/** Local index/presentation admission; not a wire or universal domain-value limit. */
public record ReferenceLimits(
    int maximumEntries,
    int maximumTextCharacters,
    int maximumPageSize,
    int maximumQueryLength,
    int maximumScan) {
  public static final ReferenceLimits STANDARD = new ReferenceLimits(16384, 1048576, 64, 128, 4096);

  public ReferenceLimits {
    if (maximumEntries < 1
        || maximumTextCharacters < 1
        || maximumPageSize < 1
        || maximumQueryLength < 1
        || maximumScan < 1) throw new IllegalArgumentException("Reference limits must be positive");
  }

  void query(ReferenceQuery query) {
    if (query.filter().length() > maximumQueryLength
        || query.limit() > maximumPageSize
        || query.cursor().map(ReferenceQuery.Cursor::offset).orElse(0) > maximumEntries)
      throw new IllegalArgumentException("Reference query exceeds its declared limits");
  }

  void text(String label, java.util.Map<String, String> preview) {
    long size = label.length();
    for (var entry : preview.entrySet())
      size += (long) entry.getKey().length() + entry.getValue().length();
    if (size > maximumTextCharacters)
      throw new IllegalArgumentException("Reference presentation exceeds its declared text limit");
  }
}
