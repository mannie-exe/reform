package design.inherent.reform.minecraft.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ClientBlockSubjectTest {
  @Test
  void wireSnapshotIsDetachedAndDoesNotResolveNativeRegistries() {
    var json =
        JsonParser.parseString("{\"Name\":\"test:unknown\",\"Properties\":{\"facing\":\"north\"}}");
    var type = ClientBlockSubject.type();
    var value = type.codec().parse(JsonOps.INSTANCE, json).result().orElseThrow();
    json.getAsJsonObject().addProperty("Name", "test:changed");
    var encoded = type.codec().encodeStart(JsonOps.INSTANCE, value).result().orElseThrow();
    assertEquals("test:unknown", encoded.getAsJsonObject().get("Name").getAsString());
    assertEquals(encoded.toString().getBytes(StandardCharsets.UTF_8).length, value.inputBytes());
    assertEquals(value, type.freeze(value));
    assertNotEquals(value, type.codec().parse(JsonOps.INSTANCE, json).result().orElseThrow());
  }

  @Test
  void blockIdentityCannotSmuggleBlockEntityPayloadOrNonStringProperties() {
    for (String value :
        new String[] {
          "[]",
          "{}",
          "{\"Name\":12}",
          "{\"Name\":true}",
          "{\"Name\":\"minecraft:chest\",\"nbt\":{}}",
          "{\"Name\":\"minecraft:stone\",\"Properties\":[]}",
          "{\"Name\":\"minecraft:stone\",\"Properties\":{\"facing\":4}}"
        })
      assertTrue(
          ClientBlockSubject.type()
              .codec()
              .parse(JsonOps.INSTANCE, JsonParser.parseString(value))
              .error()
              .isPresent(),
          value);
  }
}
