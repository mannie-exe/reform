package design.inherent.reform.importing;

import design.inherent.reform.definition.DocumentId;
import java.io.IOException;
import java.util.Objects;

/** Explicitly admitted source. Identity/labels never resolve arbitrary server paths or URLs. */
public interface ImportSource {
  @FunctionalInterface
  interface Capture {
    /** Runs on an admitted worker and returns owned bounded bytes, never a live native object. */
    ImportInput read() throws IOException;
  }

  DocumentId id();

  String label();

  int maximumBytes();

  /** Owner-thread source authority/lifetime check, independent of the document's READ right. */
  void requireCurrent();

  /** Owner-thread capture only; dispatch file IO and pure work through the returned reader. */
  Capture capture() throws IOException;

  /** Already admitted immutable content. Its lifetime is bounded by the attached import service. */
  static ImportSource snapshot(DocumentId id, ImportInput input) {
    Objects.requireNonNull(id);
    Objects.requireNonNull(input);
    return new ImportSource() {
      @Override
      public DocumentId id() {
        return id;
      }

      @Override
      public String label() {
        return input.label();
      }

      @Override
      public int maximumBytes() {
        return Math.max(1, input.size());
      }

      @Override
      public void requireCurrent() {}

      @Override
      public Capture capture() {
        return () -> input;
      }
    };
  }
}
