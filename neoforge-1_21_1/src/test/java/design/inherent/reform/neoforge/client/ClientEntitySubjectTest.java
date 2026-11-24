package design.inherent.reform.minecraft.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

class ClientEntitySubjectTest {
  @Test
  void curatedReferencesRoundTripWithoutLiveEntitiesOrRegistryInitialization() {
    for (var kind : ClientEntitySubject.Kind.values())
      for (boolean baby : new boolean[] {false, true}) {
        var value = new ClientEntitySubject(kind, baby);
        var type = ClientEntitySubject.type();
        var encoded = type.codec().encodeStart(JsonOps.INSTANCE, value).getOrThrow();
        assertEquals(value, type.codec().parse(JsonOps.INSTANCE, encoded).getOrThrow());
        assertSame(value, type.freeze(value));
        assertEquals(2, encoded.getAsJsonObject().size());
      }
    assertTrue(
        ClientEntitySubject.type()
            .codec()
            .parse(JsonOps.INSTANCE, JsonParser.parseString("{\"kind\":\"player\",\"baby\":false}"))
            .error()
            .isPresent());
    assertTrue(
        ClientEntitySubject.type()
            .codec()
            .parse(JsonOps.INSTANCE, JsonParser.parseString("{\"kind\":\"cow\"}"))
            .error()
            .isPresent());
  }
}
