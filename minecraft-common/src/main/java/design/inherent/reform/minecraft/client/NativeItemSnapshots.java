package design.inherent.reform.minecraft.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.owner.PolicyRefusal;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

/** The actual native stack codec and available registry context, never a synthesized world. */
final class NativeItemSnapshots {
  private static RegistryOps<JsonElement> ops(boolean empty) {
    ClientSettingsRuntime.requireClientThread();
    return RegistryOps.create(JsonOps.INSTANCE, NativeItemRegistries.actual(empty));
  }

  static JsonElement encode(ItemStack stack) {
    var ops = ops(stack.isEmpty());
    var result = ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack.copy());
    if (result.error().isPresent())
      throw new NativeGraphicUnavailable(
          "preview.item_context", "This item needs a registry context that is not available here");
    return result.result().orElseThrow();
  }

  static ItemStack decode(ClientItemSubject subject) {
    if (!subject.registry().equals(NativeItemContexts.registry()))
      throw new PolicyRefusal(
          "preview.stale",
          "The copied item belongs to an earlier native registry context; copy it again");
    var json = JsonParser.parseString(subject.json()).getAsJsonObject();
    var result = ItemStack.OPTIONAL_CODEC.parse(ops(json.entrySet().isEmpty()), json);
    if (result.error().isPresent())
      throw new PolicyRefusal(
          "preview.item_input",
          "The copied item cannot be decoded in the current registry context");
    return result.result().orElseThrow();
  }

  private NativeItemSnapshots() {}
}
