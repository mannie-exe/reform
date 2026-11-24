package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecipeLinkPolicyTest {
  @Test
  void preferenceNeverFallsThroughAndAlternateIsExplicit() {
    var automatic = RecipeLinkPolicy.standard();
    assertEquals(
        List.of(RecipeLinkPolicy.Provider.JEI, RecipeLinkPolicy.Provider.EMI),
        automatic.candidates());
    var configured = automatic.choosing(RecipeLinkPolicy.Provider.EMI);
    assertEquals(List.of(RecipeLinkPolicy.Provider.EMI), configured.candidates());
    assertEquals(
        List.of(RecipeLinkPolicy.Provider.JEI),
        configured.choosing(RecipeLinkPolicy.Provider.JEI).candidates());
    assertEquals(Optional.of(RecipeLinkPolicy.Provider.EMI), configured.preferred());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RecipeLinkPolicy(
                Set.of(RecipeLinkPolicy.Provider.JEI), Optional.of(RecipeLinkPolicy.Provider.EMI)));
    assertTrue(new RecipeLinkPolicy(Set.of(), Optional.empty()).candidates().isEmpty());
  }
}
