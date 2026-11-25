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
  @Test
  void nativePrimaryPointerSelectsRowsAndDragsScrollbar() {
    var widget = new ChoiceListWidget(Component.literal("Choices"));
    var accepted = new AtomicReference<String>();
    widget.prepare(
        data("native", 100),
        Optional.empty(),
        Set.of(),
        4,
        2,
        ClientTheme.standard(),
        ignored -> {},
        accepted::set);
    widget.setX(10);
    widget.setY(10);
    widget.setWidth(160);
    assertFalse(widget.listClick(30, 20, InputConstants.MOUSE_BUTTON_RIGHT));
    assertNull(accepted.get());
    assertTrue(widget.listClick(30, 20, InputConstants.MOUSE_BUTTON_LEFT));
    assertEquals("id:0", accepted.get());
    assertTrue(widget.listClick(168, 12, InputConstants.MOUSE_BUTTON_LEFT));
    assertFalse(widget.listDrag(168, 80, InputConstants.MOUSE_BUTTON_RIGHT));
    assertTrue(widget.listDrag(168, 80, InputConstants.MOUSE_BUTTON_LEFT));
    assertTrue(widget.firstVisibleIndex() > 0);
    assertTrue(widget.listRelease());
    assertFalse(widget.listDrag(168, 12, InputConstants.MOUSE_BUTTON_LEFT));
  }

  @Test
  void consecutiveNativeEventsUseAuthoritativeReadbackWithoutAParentRebuild() {
    var widget = new ChoiceListWidget(Component.literal("Choices"));
    var data = data("current", 8);
    var active = new AtomicReference<Optional<String>>(Optional.empty());
    var accepted = new AtomicReference<String>();
    widget.prepare(
        data,
        active.get(),
        Set.of(),
        4,
        2,
        ClientTheme.standard(),
        key -> {
          active.set(Optional.of(key));
          widget.observe(data, active.get(), Set.of());
        },
        accepted::set);
    widget.setFocused(true);
    widget.listKey(InputConstants.KEY_DOWN);
    widget.listKey(InputConstants.KEY_DOWN);
    widget.listKey(InputConstants.KEY_RETURN);
    assertEquals("id:2", accepted.get()); // id:1 is disabled in this fixture.
    assertEquals(Optional.of("id:2"), widget.observedActive());
    // A refused command's readback stays authoritative; no optimistic local selection.
    widget.prepare(
        data,
        active.get(),
        Set.of(),
        4,
        2,
        ClientTheme.standard(),
        key -> widget.observe(data, active.get(), Set.of()),
        accepted::set);
    widget.listKey(InputConstants.KEY_DOWN);
    assertEquals(Optional.of("id:2"), widget.observedActive());
    widget.detach();
    widget.listKey(InputConstants.KEY_END);
    widget.listKey(InputConstants.KEY_RETURN);
    assertEquals("id:2", accepted.get());
  }

  private static ChoiceListData data(String generation, int size) {
    var items = new ArrayList<ChoiceListData.Item>();
    for (int i = 0; i < size; i++)
      items.add(new ChoiceListData.Item("id:" + i, "Title " + i, "", i != 1));
    return new ChoiceListData(generation, items, OptionalLong.empty());
  }

  @Test
  void retainedItemCheckAllowsPagingButRefusesChangedGenerationValueOrEligibility() {
    var initial = data("same-source-query", 4);
    assertTrue(data("same-source-query", 8).retains(initial, "id:3"));
    assertFalse(data("different-query", 8).retains(initial, "id:3"));
    assertFalse(data("same-source-query", 2).retains(initial, "id:3"));
    var changed = new ArrayList<>(initial.items());
    changed.set(3, new ChoiceListData.Item("id:3", "New meaning", "", true));
    assertFalse(
        new ChoiceListData("same-source-query", changed, OptionalLong.empty())
            .retains(initial, "id:3"));
    changed.set(3, new ChoiceListData.Item("id:3", "Title 3", "", false));
    assertFalse(
        new ChoiceListData("same-source-query", changed, OptionalLong.empty())
            .retains(initial, "id:3"));
    assertFalse(initial.retains(initial, "missing"));
  }

  @Test
  void pagingDemandFollowsRealNavigationAndIsClearedOnGenerationChangeOrHide() {
    var widget = new ChoiceListWidget(Component.literal("Choices"));
    var base = data("page", 8);
    var active = new AtomicReference<String>();
    widget.prepare(
        new ChoiceListData("page", base.items(), OptionalLong.empty(), true, true),
        Optional.empty(),
        Set.of(),
        4,
        2,
        ClientTheme.standard(),
        active::set,
        ignored -> fail("Navigation must not accept"));
    widget.setFocused(true);
    assertTrue(widget.takeBoundary().isEmpty());
    widget.listKey(InputConstants.KEY_END);
    assertEquals("id:7", active.get());
    assertEquals(
        Optional.of(design.inherent.reform.minecraft.client.ui.ChoiceListParts.Boundary.AFTER),
        widget.takeBoundary());
    assertTrue(widget.takeBoundary().isEmpty());
    widget.listKey(InputConstants.KEY_HOME);
    assertEquals(
        Optional.of(design.inherent.reform.minecraft.client.ui.ChoiceListParts.Boundary.BEFORE),
        widget.takeBoundary());
    widget.listKey(InputConstants.KEY_END);
    widget.detach();
    assertTrue(widget.takeBoundary().isEmpty());
    widget.prepare(
        data("new-query", 8),
        Optional.empty(),
        Set.of(),
        4,
        2,
        ClientTheme.standard(),
        active::set,
        ignored -> {});
    assertTrue(widget.takeBoundary().isEmpty());
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
