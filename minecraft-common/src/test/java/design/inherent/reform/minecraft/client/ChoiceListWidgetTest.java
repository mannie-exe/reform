package design.inherent.reform.minecraft.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.platform.InputConstants;
import design.inherent.reform.minecraft.client.ui.ChoiceListData;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/** Native-classpath state/dispatch checks; actual native hosts are separately qualified. */
class ChoiceListWidgetTest {
  private static ChoiceListData data(String generation, int size) {
    var items = new ArrayList<ChoiceListData.Item>();
    for (int i = 0; i < size; i++)
      items.add(new ChoiceListData.Item("id:" + i, "Title " + i, "", i != 1));
    return new ChoiceListData(generation, items, OptionalLong.empty());
  }

  @Test
  void boundedWindowKeepsNavigationSeparateFromAcceptanceAndRetainsAnchorByKey() {
    var widget = new ChoiceListWidget(Component.literal("Choices"));
    var active = new AtomicReference<String>();
    var accepted = new AtomicReference<String>();
    var data = data("source1", 1000);
    widget.prepare(
        data, Optional.empty(), Set.of(), 4, 2, ClientTheme.standard(), active::set, accepted::set);
    widget.setFocused(true);
    assertEquals(6, widget.preparedRowCount());
    assertTrue(widget.listKey(InputConstants.KEY_DOWN));
    assertEquals("id:0", active.get());
    assertNull(accepted.get());
    widget.prepare(
        data,
        Optional.of(active.get()),
        Set.of(),
        4,
        2,
        ClientTheme.standard(),
        active::set,
        accepted::set);
    widget.listKey(InputConstants.KEY_DOWN);
    assertEquals("id:2", active.get());
    assertNull(accepted.get());
    widget.prepare(
        data,
        Optional.of(active.get()),
        Set.of(),
        4,
        2,
        ClientTheme.standard(),
        active::set,
        accepted::set);
    widget.listKey(InputConstants.KEY_RETURN);
    assertEquals("id:2", accepted.get());
    widget.listKey(InputConstants.KEY_END);
    assertEquals("id:999", active.get());
    assertEquals(996, widget.firstVisibleIndex());
    assertTrue(widget.preparedRowCount() <= 8);
    var reorder = new ArrayList<>(data.items());
    Collections.swap(reorder, 995, 996);
    widget.prepare(
        new ChoiceListData("source1", reorder, OptionalLong.empty()),
        Optional.of(active.get()),
        Set.of(),
        4,
        2,
        ClientTheme.standard(),
        active::set,
        accepted::set);
    assertEquals(995, widget.firstVisibleIndex());
    widget.prepare(
        data("source2", 1000),
        Optional.empty(),
        Set.of(),
        4,
        2,
        ClientTheme.standard(),
        active::set,
        accepted::set);
    assertEquals(0, widget.firstVisibleIndex());
    assertFalse(widget.listNarration().getString().contains("of 1000"));
    widget.detach();
    active.set(null);
    widget.listKey(InputConstants.KEY_DOWN);
    assertNull(active.get());
    widget.clear();
    assertEquals(0, widget.preparedRowCount());
    assertFalse(widget.listNarration().getString().contains("Title"));
  }

  @Test
  void metadataRejectsFalseTotalsDuplicateKeysAndUnboundedWindows() {
    var item = new ChoiceListData.Item("a", "Title", "Detail", true);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChoiceListData("source", List.of(item, item), OptionalLong.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChoiceListData("source", List.of(item), OptionalLong.of(0)));
    assertThrows(IllegalArgumentException.class, () -> data("source", 4097));
    var widget = new ChoiceListWidget(Component.literal("Choices"));
    widget.prepare(
        new ChoiceListData("page", List.of(item), OptionalLong.of(200)),
        Optional.of("a"),
        Set.of("a"),
        4,
        2,
        ClientTheme.standard(),
        ignored -> {},
        ignored -> {});
    var narration = widget.listNarration().getString();
    assertTrue(narration.contains("200 choices"));
    assertTrue(narration.contains("Selected"));
    assertFalse(narration.contains("1 of 200"));
  }
}
