package design.inherent.reform.presentation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Known-choice labels only; canonical input and owner validation are never replaced. */
public record ChoiceDisplay(List<Caption> captions) {
  public record Caption(String value, String translationKey, String fallback) {
    public Caption {
      if (Objects.requireNonNull(value).isBlank()
          || value.length() > 4096
          || Objects.requireNonNull(translationKey).isBlank()
          || translationKey.length() > 256
          || Objects.requireNonNull(fallback).isBlank()
          || fallback.length() > 256)
        throw new IllegalArgumentException("Choice captions need bounded values and labels");
    }
  }

  public ChoiceDisplay {
    captions = List.copyOf(captions);
    if (captions.size() > 256) throw new IllegalArgumentException("Too many choice captions");
    var seen = new HashSet<String>();
    for (var caption : captions)
      if (!seen.add(caption.value()))
        throw new IllegalArgumentException("Duplicate choice caption: " + caption.value());
  }

  public Optional<Caption> caption(String raw) {
    Objects.requireNonNull(raw);
    return captions.stream().filter(value -> value.value().equals(raw)).findFirst();
  }
}
