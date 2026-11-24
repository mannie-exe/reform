package design.inherent.reform.minecraft.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.owner.PolicyRefusal;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

/** The actual native stack codec and available registry context, never a synthesized world. */
final class NativeItemSnapshots {
  private static RegistryOps<JsonElement> ops() {
    ClientSettingsRuntime.requireClientThread();
    var level = Minecraft.getInstance().level;
    var registries =
        level == null
            ? RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
            : level.registryAccess();
    return RegistryOps.create(JsonOps.INSTANCE, registries);
  }

  static JsonElement encode(ItemStack stack) {
    var result = ItemStack.OPTIONAL_CODEC.encodeStart(ops(), stack);
    if (result.error().isPresent())
      throw new NativeGraphicUnavailable(
          "preview.item_context", "This item needs a registry context that is not available here");
    return result.result().orElseThrow();
  }

  static ItemStack decode(ClientItemSubject subject) {
    var result = ItemStack.OPTIONAL_CODEC.parse(ops(), JsonParser.parseString(subject.json()));
    if (result.error().isPresent())
      throw new PolicyRefusal(
          "preview.item_input",
          "The copied item cannot be decoded in the current registry context");
    return result.result().orElseThrow();
  }

  private NativeItemSnapshots() {}
}
