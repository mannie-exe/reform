package design.inherent.reform.minecraft.client;

import design.inherent.reform.owner.ApplicationEffects;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/** Shared display of captured review facts; truncation is an explicit inline preview only. */
final class ReviewText {
  static final int SUMMARY_CHARACTERS = 512;

  private ReviewText() {}

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.review." + key, fallback);
  }

  static String preview(String value) {
    return value.length() > SUMMARY_CHARACTERS
        ? value.substring(0, SUMMARY_CHARACTERS) + "…"
        : value;
  }

  static Component application(ApplicationEffects.Rule rule) {
    return Component.literal(
            rule.timings().stream()
                .sorted()
                .map(value -> timing(value).getString())
                .collect(java.util.stream.Collectors.joining(", ")))
        .append("\n")
        .append(rule.explanation());
  }

  private static Component timing(ApplicationEffects.Timing value) {
    String fallback =
        switch (value) {
          case OWNER_PUBLICATION -> "Owner value publication";
          case EXPLICIT_RELOAD -> "Explicit reload";
          case REOPEN_VIEW -> "Reopen the view";
          case NEXT_OPERATION -> "Next operation";
          case NEXT_JOIN -> "Next join";
          case WORLD_RESTART -> "World or server restart";
          case GAME_RESTART -> "Game restart";
          case NEW_WORLD -> "New world only";
          case NEW_CHUNKS -> "Newly generated chunks";
          case INCOMPATIBLE -> "Incompatible with this context";
          case OWNER_MANAGED -> "Application managed by the owner";
        };
    return text("timing." + value.name().toLowerCase(Locale.ROOT), fallback);
  }
}
