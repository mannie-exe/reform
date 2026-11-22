package example.reform.platform;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

final class NativeWorldProbe extends Screen {
  static void start() {
    var client = Minecraft.getInstance();
    if (Files.exists(Path.of("saves", PlatformWorldChecks.WORLD))) {
      client
          .createWorldOpenFlows()
          .openWorld(PlatformWorldChecks.WORLD, () -> client.gui.setScreen(new TitleScreen()));
    } else {
      client
          .createWorldOpenFlows()
          .createFreshLevel(
              PlatformWorldChecks.WORLD,
              new LevelSettings(
                  "re:form native fixture",
                  GameType.CREATIVE,
                  new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                  true,
                  WorldDataConfiguration.DEFAULT),
              new WorldOptions(42, false, false),
              registries ->
                  registries
                      .lookupOrThrow(Registries.WORLD_PRESET)
                      .getOrThrow(WorldPresets.FLAT)
                      .value()
                      .createWorldDimensions(),
              client.gui.screen());
    }
  }

  static Component link(String command) {
    return Component.literal("re:form fixture link")
        .withStyle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand(command)));
  }

  static void click(Style style) {
    var client = Minecraft.getInstance();
    var chat = new ChatScreen("", false);
    client.gui.setScreen(chat);
    defaultHandleGameClickEvent(style.getClickEvent(), client, chat);
  }

  static void typed(String command) {
    var client = Minecraft.getInstance();
    var chat = new ChatScreen(command, false);
    client.gui.setScreen(chat);
    chat.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
  }

  static void leave() {
    Minecraft.getInstance().disconnect(new TitleScreen(), false);
  }

  static Runnable revokePermission(ServerPlayer player) {
    var server = player.level().getServer();
    var operators = server.getPlayerList().getOps();
    var profile = player.nameAndId();
    var previous = operators.get(profile);
    var commands = server.getWorldData().isAllowCommands();
    operators.remove(profile);
    server.getWorldData().setAllowCommands(false);
    return () -> {
      if (previous == null) operators.remove(profile);
      else operators.add(previous);
      server.getWorldData().setAllowCommands(commands);
    };
  }

  private NativeWorldProbe() {
    super(Component.literal("Native click exposure"));
  }
}
