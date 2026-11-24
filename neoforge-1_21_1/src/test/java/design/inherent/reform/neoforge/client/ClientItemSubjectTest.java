package design.inherent.reform.minecraft.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientItemSubjectTest {
  @Test
  void wireAdmissionPreservesRegistryIdentityWithoutEnteringNativeRegistryCode() {
    String registry = UUID.randomUUID().toString();
    var input =
        JsonParser.parseString(
            "{\"registry\":\"" + registry + "\",\"stack\":{\"id\":\"test:unknown\",\"count\":2}}");
    var type = ClientItemSubject.type();
    var subject = type.codec().parse(JsonOps.INSTANCE, input).result().orElseThrow();
    var encoded = type.codec().encodeStart(JsonOps.INSTANCE, subject).result().orElseThrow();
    assertEquals(input, encoded);
    assertEquals(encoded.toString().getBytes(StandardCharsets.UTF_8).length, subject.inputBytes());
    assertEquals(subject, type.freeze(subject));
    var other = input.deepCopy().getAsJsonObject();
    other.addProperty("registry", UUID.randomUUID().toString());
    assertNotEquals(subject, type.codec().parse(JsonOps.INSTANCE, other).result().orElseThrow());
    // Shape admission is deliberately not proof that an unknown item resolves natively.
  }

  @Test
  void bareOrMalformedContextCannotSilentlyRelabelAnEarlierSnapshot() {
    var type = ClientItemSubject.type();
    for (String value :
        new String[] {
          "{}",
          "{\"id\":\"minecraft:stone\"}",
          "{\"registry\":\"1-1-1-1-1\",\"stack\":{}}",
          "{\"registry\":12,\"stack\":{}}",
          "{\"registry\":\"" + UUID.randomUUID() + "\",\"stack\":{},\"extra\":true}"
        })
      assertTrue(
          type.codec().parse(JsonOps.INSTANCE, JsonParser.parseString(value)).error().isPresent(),
          value);
  }
}
