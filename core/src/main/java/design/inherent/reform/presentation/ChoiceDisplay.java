package design.inherent.reform.presentation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Known-choice labels only; canonical input and owner validation are never replaced. */
public final class ChoiceDisplay {
  public record Caption(String value, String translationKey, String fallback) {
    public Caption {
      if (Objects.requireNonNull(value).length() > 4096
          || Objects.requireNonNull(translationKey).isBlank()
          || translationKey.length() > 256
          || Objects.requireNonNull(fallback).isBlank()
          || fallback.length() > 256)
        throw new IllegalArgumentException("Choice captions need bounded values and labels");
    }
  }

  private final List<Caption> captions;
  private final Map<String, Caption> byValue;

  public ChoiceDisplay(List<Caption> captions) {
    this.captions = List.copyOf(captions);
    if (this.captions.size() > 256) throw new IllegalArgumentException("Too many choice captions");
    var index = new LinkedHashMap<String, Caption>();
    for (var caption : this.captions)
      if (index.putIfAbsent(caption.value(), caption) != null)
        throw new IllegalArgumentException("Duplicate choice caption: " + caption.value());
    byValue = Map.copyOf(index);
  }

  public List<Caption> captions() {
    return captions;
  }

  public Optional<Caption> caption(String raw) {
    return Optional.ofNullable(byValue.get(Objects.requireNonNull(raw)));
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ChoiceDisplay display && captions.equals(display.captions);
  }

  @Override
  public int hashCode() {
    return captions.hashCode();
  }

  @Override
  public String toString() {
    return "ChoiceDisplay[captions=" + captions + "]";
  }
}
