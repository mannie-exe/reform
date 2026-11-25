package design.inherent.reform.minecraft.client;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.DraftRevision;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The real input observation key must account for its complete revision payload. */
class ClientTextInputTest {
  @Test
  void smallTextCannotHideAnOverBudgetRevisionStamp() {
    var key = ClientTextInput.readKey(1024);
    var shortRevision = state("qualified source");
    assertEquals(shortRevision, key.capture(shortRevision).value());
    assertThrows(IllegalArgumentException.class, () -> key.capture(state("x".repeat(4096))));
    var admitted = ClientTextInput.readKey(16384).capture(state("x".repeat(4096)));
    assertTrue(admitted.retainedBytes() >= 8192);
  }

  private static ClientTextInput.State state(String source) {
    var revision =
        new DraftRevision(
            DocumentId.parse("example:input"), UUID.randomUUID(), UUID.randomUUID(), 1, 1, source);
    return new ClientTextInput.State(
        new ClientTextInput.Stamp(new Object(), new Object(), revision, 1, "x", 1, 1),
        "x",
        1,
        1,
        32,
        true);
  }
}
