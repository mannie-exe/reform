package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.FormCatalog;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/** One projection for translated purpose and separately owned, already resolved context facts. */
final class CatalogHelp {
  static boolean present(FormCatalog.Section section) {
    return !section.help().isBlank()
        || !section.supplementalHelp().isBlank()
        || Language.getInstance().has(section.translationKey() + ".help");
  }

  static Component describe(FormCatalog.Section section) {
    var text =
        Component.translatableWithFallback(section.translationKey() + ".help", section.help());
    if (!section.supplementalHelp().isBlank()) {
      if (!text.getString().isEmpty()) text.append("\n");
      text.append(Component.literal(section.supplementalHelp()));
    }
    return text;
  }

  private CatalogHelp() {}
}
