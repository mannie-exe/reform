package example.reform.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.minecraft.command.SettingsCommands;
import design.inherent.reform.operation.DocumentReview;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

/** The actual loader command tree and ChatScreen Enter path; no manufactured command source. */
final class PlatformReviewCommands implements PlatformFormProbe.Scene {
  private static PlatformReviewCommands active;
  private final Minecraft client = Minecraft.getInstance();
  private final List<SettingsCommands.Reply> replies = new ArrayList<>();
  private final JsonArray checks = new JsonArray();
  private final JsonObject report = new JsonObject();
  private boolean wrongThread;
  private int stage;

  static void register(ClientRegistration registration) {
    registration.registerCommands(
        "platformreview",
        new ClientRegistration.CommandTree() {
          @Override
          public <S> LiteralArgumentBuilder<S> bind(ClientRegistration.CommandSource<S> source) {
            return PlatformCatalogExample.settings()
                .commandBranch(
                    "platformreview",
                    source,
                    reply -> {
                      if (active != null) active.receive(reply);
                    });
          }
        });
  }

  static java.util.concurrent.CompletionStage<JsonObject> run(ClientRegistration registration) {
    return PlatformFormProbe.run(
        registration,
        "REVIEW_COMMAND",
        driver -> {
          active = new PlatformReviewCommands();
          return active;
        });
  }

  private PlatformReviewCommands() {
    report.addProperty("status", "RUNNING");
    report.add("checks", checks);
  }

  private void receive(SettingsCommands.Reply reply) {
    if (!client.isSameThread()) {
      wrongThread = true;
      return;
    }
    if (replies.size() >= 32) throw new AssertionError("Unbounded native review feedback");
    replies.add(reply);
  }

  private void check(String label, boolean valid) {
    if (!valid) throw new AssertionError(label);
    checks.add(label);
  }

  public String diagnostic() {
    return "native review command stage=" + stage;
  }

  public JsonObject report() {
    return report;
  }

  public boolean tick() throws Exception {
    if (wrongThread) throw new AssertionError("Native review feedback left client owner thread");
    switch (stage) {
      case 0 -> {
        check("GUI editor closes before real chat navigation", PlatformReviewChecks.guiClosed());
        NativeWorldProbe.start();
        stage = 1;
      }
      case 1 -> {
        if (client.player == null || client.level == null || NativeScreens.current(client) != null)
          return false;
        check(
            "native review uses actual integrated play connection",
            client.getSingleplayerServer() != null
                && client.getConnection().getConnection().isMemoryConnection());
        NativeWorldProbe.typed("/platformreview review");
        stage = 2;
      }
      case 2 -> {
        var error =
            replies.stream()
                .filter(reply -> reply.kind() == SettingsCommands.Kind.ERROR)
                .findFirst();
        if (error.isPresent()) throw new AssertionError(error.orElseThrow().message().getString());
        var completed =
            replies.stream()
                .filter(reply -> reply.kind() == SettingsCommands.Kind.COMPLETED)
                .findFirst();
        if (completed.isEmpty()) return false;
        var message = completed.orElseThrow();
        var review = (DocumentReview) message.operation().orElseThrow().result().orElseThrow();
        var gui = PlatformReviewChecks.guiRevision();
        check(
            "typed native command review has independent actual actor draft",
            !review.target().session().equals(gui.session())
                && review.target().document().equals(gui.document())
                && !review.comparison().authoredChanges());
        check(
            "native feedback identifies observed source and bounded result",
            message.message().getString().contains("observed source=json")
                && message.message().getString().length() <= 8192);
        check(
            "native command preserves source and retired GUI lifetime",
            PlatformReviewChecks.sourceUnchanged() && PlatformReviewChecks.guiClosed());
        NativeWorldProbe.leave();
        stage = 3;
      }
      case 3 -> {
        if (client.getSingleplayerServer() != null
            || client.player != null
            || client.level != null
            || !(NativeScreens.current(client) instanceof TitleScreen)) return false;
        check("native review world closes before returning to client workflow", true);
        report.addProperty("status", "PASS");
        return true;
      }
      default -> throw new AssertionError("Unknown native review command stage");
    }
    return false;
  }

  @Override
  public void close() {
    active = null;
  }
}
