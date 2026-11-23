package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentId;
import org.junit.jupiter.api.Test;

class DestinationIdTest {
  @Test
  void navigationIdentityPreservesNamespacedSyntaxButDoesNotBecomeADocumentIdentity() {
    var owner = DocumentId.parse("example:settings/display");
    var destination = new DestinationId(owner);
    assertEquals(owner, destination.value());
    assertEquals("example:settings/display", destination.toString());
    assertEquals(destination, DestinationId.parse(destination.toString()));
    assertNotEquals(owner, (Object) destination);
    assertNotEquals(destination, DestinationId.parse("other:settings/display"));
    assertEquals("design.inherent.reform.presentation", destination.getClass().getPackageName());
  }

  @Test
  void invalidAndOverlongIdentitiesRefuseWithoutIntroducingANewNormalization() {
    for (var invalid :
        new String[] {"missing_namespace", ":path", "ns:", "NS:path", "ns:bad id", "ns:other:part"})
      assertThrows(IllegalArgumentException.class, () -> DestinationId.parse(invalid));
    assertThrows(NullPointerException.class, () -> new DestinationId(null));
    assertThrows(NullPointerException.class, () -> DestinationId.parse(null));
    var atLimit = "a:" + "b".repeat(254);
    assertEquals(atLimit, DestinationId.parse(atLimit).toString());
    assertThrows(IllegalArgumentException.class, () -> DestinationId.parse(atLimit + "b"));
  }
}
