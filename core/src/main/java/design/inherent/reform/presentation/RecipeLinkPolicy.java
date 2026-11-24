package design.inherent.reform.presentation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Consumer declaration only; installing the library creates no preference file. */
public record RecipeLinkPolicy(Set<Provider> admitted, Optional<Provider> preferred) {
  public enum Provider {
    JEI,
    EMI
  }

  public RecipeLinkPolicy {
    admitted = Set.copyOf(admitted);
    Objects.requireNonNull(preferred);
    if (preferred.isPresent() && !admitted.contains(preferred.orElseThrow()))
      throw new IllegalArgumentException("The preferred recipe viewer must be admitted");
  }

  public static RecipeLinkPolicy standard() {
    return new RecipeLinkPolicy(Set.of(Provider.JEI, Provider.EMI), Optional.empty());
  }

  /** An explicit alternate activation, not a silent fallback or persisted preference change. */
  public RecipeLinkPolicy choosing(Provider provider) {
    return new RecipeLinkPolicy(admitted, Optional.of(provider));
  }

  /** Deterministic automatic order. A configured provider never silently falls through. */
  public List<Provider> candidates() {
    return preferred.map(List::of).orElseGet(() -> List.of(Provider.JEI, Provider.EMI)).stream()
        .filter(admitted::contains)
        .toList();
  }
}
