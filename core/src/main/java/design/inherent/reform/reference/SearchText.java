package design.inherent.reform.reference;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/** Search-only accent/case folding. Never use this representation as an authored key or cursor. */
public final class SearchText {
  public static String fold(String value) {
    String decomposed =
        Normalizer.normalize(
            Objects.requireNonNull(value).toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
    var result = new StringBuilder(decomposed.length());
    decomposed
        .codePoints()
        .forEach(
            point -> {
              int type = Character.getType(point);
              if (type != Character.NON_SPACING_MARK
                  && type != Character.COMBINING_SPACING_MARK
                  && type != Character.ENCLOSING_MARK) result.appendCodePoint(point);
            });
    return result.toString();
  }

  private SearchText() {}
}
