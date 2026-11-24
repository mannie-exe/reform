package example.reform.platform;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/** Disposable fixture setup and read-only native inventory/known-recipe observations. */
final class NativeRecipeFixture {
  static CompletableFuture<Void> survival() {
    var client = Minecraft.getInstance();
    var server = client.getSingleplayerServer();
    var id = client.player.getUUID();
    var done = new CompletableFuture<Void>();
    server.execute(
        () -> {
          try {
            var player = server.getPlayerList().getPlayer(id);
            if (player == null) throw new IllegalStateException("Owned fixture player is absent");
            player.setGameMode(GameType.SURVIVAL);
            done.complete(null);
          } catch (RuntimeException failed) {
            done.completeExceptionally(failed);
          }
        });
    return done;
  }

  static List<ItemStack> inventory() {
    var inventory = Minecraft.getInstance().player.getInventory();
    var result = new ArrayList<ItemStack>();
    for (int i = 0; i < inventory.getContainerSize(); i++) result.add(inventory.getItem(i).copy());
    return List.copyOf(result);
  }

  static boolean unchanged(List<ItemStack> before) {
    var after = inventory();
    if (before.size() != after.size()) return false;
    for (int i = 0; i < before.size(); i++)
      if (!ItemStack.matches(before.get(i), after.get(i))) return false;
    return true;
  }

  static List<String> unlocked() {
    var client = Minecraft.getInstance();
    return client.player.getRecipeBook().getCollections().stream()
        .flatMap(c -> c.getRecipes().stream())
        .map(r -> r.id().toString())
        .distinct()
        .sorted()
        .toList();
  }
}
