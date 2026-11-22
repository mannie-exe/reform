package example.reform.platform;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

final class NativeWorldProbe {
  static void start() {
    var client = Minecraft.getInstance();
    if (Files.exists(Path.of("saves", PlatformWorldChecks.WORLD))) {
      client
          .createWorldOpenFlows()
          .openWorld(PlatformWorldChecks.WORLD, () -> client.setScreen(new TitleScreen()));
    } else {
      var rules = new GameRules();
      rules.getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, null);
      client
          .createWorldOpenFlows()
          .createFreshLevel(
              PlatformWorldChecks.WORLD,
              new LevelSettings(
                  "re:form native fixture",
                  GameType.CREATIVE,
                  false,
                  Difficulty.PEACEFUL,
                  true,
                  rules,
                  WorldDataConfiguration.DEFAULT),
              new WorldOptions(42, false, false),
              registries ->
                  registries
                      .registryOrThrow(Registries.WORLD_PRESET)
                      .getHolderOrThrow(WorldPresets.FLAT)
                      .value()
                      .createWorldDimensions(),
              client.screen);
    }
  }

  static Component link(String command) {
    return Component.literal("re:form fixture link")
        .withStyle(
            Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
  }

  static void click(Style style) {
    var client = Minecraft.getInstance();
    var chat = new ChatScreen("");
    client.setScreen(chat);
    chat.handleComponentClicked(style);
  }

  static void typed(String command) {
    var client = Minecraft.getInstance();
    var chat = new ChatScreen(command);
    client.setScreen(chat);
    chat.keyPressed(InputConstants.KEY_RETURN, 0, 0);
  }

  static void leave() {
    var client = Minecraft.getInstance();
    client.level.disconnect();
    client.disconnect(new TitleScreen());
  }

  static Runnable revokePermission(ServerPlayer player) {
    var operators = player.server.getPlayerList().getOps();
    var profile = player.getGameProfile();
    var previous = operators.get(profile);
    operators.add(new ServerOpListEntry(profile, 0, false));
    return () -> {
      if (previous == null) operators.remove(profile);
      else operators.add(previous);
    };
  }

  private NativeWorldProbe() {}
}
