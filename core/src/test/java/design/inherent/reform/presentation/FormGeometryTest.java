package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class FormGeometryTest {
  @Test
  void themedRegionsAndControlFlowUseTheSameExplicitGaps() {
    var theme =
        ThemeScope.root(
                ThemePatch.empty()
                    .set(ThemeValues.Role.SPACE_SCREEN, 21)
                    .set(ThemeValues.Role.SPACE_CONTENT, 13)
                    .set(ThemeValues.Role.SPACE_INLINE, 17))
            .values();
    var frame =
        FormGeometry.screenFrame(
            500,
            400,
            20,
            10,
            12,
            List.of(new FormGeometry.Size(100, 24), new FormGeometry.Size(90, 24)),
            theme);
    assertEquals(21, frame.content().x());
    assertEquals(64, frame.content().y());
    assertEquals(117, frame.actionFlow().placements().get(1).x());
    assertEquals(
        13,
        frame.actions().orElseThrow().y()
            - (frame.status().orElseThrow().y() + frame.status().orElseThrow().height()));
    var tracks =
        FormGeometry.fieldTracks(
            200,
            0,
            List.of(new FormGeometry.Size(100, 20), new FormGeometry.Size(90, 20)),
            13,
            java.util.Set.of(),
            17);
    assertEquals(57, tracks.controls().height());
    assertEquals(37, tracks.controls().placements().get(1).y());
    assertEquals(12, FormGeometry.screenFrame(500, 400, 20, 10, 12, List.of()).content().x());
  }

  @Test
  void explicitControlClustersKeepStepperPairsTogetherAndRefuseInvalidJoins() {
    var sizes =
        List.of(
            new FormGeometry.Size(100, 20),
            new FormGeometry.Size(24, 20),
            new FormGeometry.Size(24, 20),
            new FormGeometry.Size(112, 20),
            new FormGeometry.Size(20, 20));
    var flow = FormGeometry.controls(sizes, 142, 4, java.util.Set.of(1));
    assertEquals(flow.placements().get(1).y(), flow.placements().get(2).y());
    assertTrue(flow.placements().get(1).y() > flow.placements().getFirst().y());
    assertEquals(68, flow.height());
    var tooNarrow = FormGeometry.controls(sizes, 30, 4, java.util.Set.of(1));
    assertTrue(
        tooNarrow.placements().stream().allMatch(at -> at.x() >= 0 && at.x() + at.width() <= 30));
    assertThrows(
        IllegalArgumentException.class,
        () -> FormGeometry.controls(sizes, 100, 4, java.util.Set.of(-1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> FormGeometry.controls(sizes, 100, 4, java.util.Set.of(4)));
    assertThrows(
        ArithmeticException.class,
        () ->
            FormGeometry.controls(
                List.of(new FormGeometry.Size(80, 20), new FormGeometry.Size(80, 20)),
                100,
                Integer.MAX_VALUE));
  }

  private static final FormGeometry.WorkspaceMetrics WORKSPACE =
      new FormGeometry.WorkspaceMetrics(112, 180, 180, 160, 220, 8);

  @Test
  void inlineFieldBoundaryUsesMeasuredContentAndEmptyLabelsCreateNoPhantomTrack() {
    var controls = List.of(new FormGeometry.Size(60, 20), new FormGeometry.Size(20, 20));
    assertTrue(FormGeometry.fieldTracks(167, 100, controls, 8).stacked());
    var wrapped = FormGeometry.fieldTracks(168, 100, controls, 8);
    assertFalse(wrapped.stacked());
    assertEquals(44, wrapped.controls().height());
    var inline = FormGeometry.fieldTracks(192, 100, controls, 8);
    assertFalse(inline.stacked());
    assertEquals(100, inline.textWidth());
    assertEquals(108, inline.controlsX());
    var flow = FormGeometry.fieldTracks(100, 0, controls, 8);
    assertFalse(flow.stacked());
    assertEquals(0, flow.textWidth());
    assertEquals(0, flow.controlsX());
    assertEquals(20, flow.controls().height());
    assertEquals(100, FormGeometry.fieldTracks(100, 50, List.of(), 8).textWidth());
  }

  @Test
  void relatedTracksPreserveOrderRemaindersAndRefusePartialColumnCredit() {
    assertTrue(FormGeometry.relatedTracks(List.of(160, 120), 331, 12).isEmpty());
    var tracks = FormGeometry.relatedTracks(List.of(160, 120), 333, 12);
    assertEquals(160, tracks.getFirst().width());
    assertEquals(172, tracks.get(1).x());
    assertEquals(161, tracks.get(1).width());
    assertEquals(333, tracks.getLast().x() + tracks.getLast().width());
    assertTrue(FormGeometry.relatedTracks(List.of(Integer.MAX_VALUE, 120), 1000, 12).isEmpty());
    var three = FormGeometry.relatedTracks(List.of(80, 100, 120), 385, 12);
    assertEquals(3, three.size());
    assertEquals(385, three.getLast().x() + three.getLast().width());
    assertThrows(
        IllegalArgumentException.class, () -> FormGeometry.relatedTracks(List.of(0, 10), 100, 4));
  }

  @Test
  void workspaceRetainsEditorAndUsesCompactDrawerOnlyBelowMeasuredMinimum() {
    var compact = FormGeometry.workspace(299, 40, 240, WORKSPACE, false, true);
    assertTrue(compact.compactNavigation());
    assertTrue(compact.navigation().isEmpty());
    assertTrue(compact.information().isEmpty());
    assertEquals(new FormGeometry.Placement(0, 40, 299, 200), compact.editor());
    var drawer = FormGeometry.workspace(299, 40, 240, WORKSPACE, true, true);
    assertTrue(drawer.navigationOverlay());
    assertEquals(compact.editor(), drawer.editor());
    assertEquals(new FormGeometry.Placement(0, 40, 160, 200), drawer.navigation().orElseThrow());
    var boundary = FormGeometry.workspace(300, 40, 240, WORKSPACE, false, true);
    assertFalse(boundary.compactNavigation());
    assertEquals(180, boundary.editor().width());
    assertEquals(112, boundary.navigation().orElseThrow().width());
    assertTrue(boundary.information().isEmpty());
  }

  @Test
  void informationBecomesAThirdColumnOnlyWhenAllThreeMeasuredMinimaFit() {
    assertTrue(
        FormGeometry.workspace(487, 40, 240, WORKSPACE, false, true).information().isEmpty());
    var boundary = FormGeometry.workspace(488, 40, 240, WORKSPACE, false, true);
    assertEquals(112, boundary.navigation().orElseThrow().width());
    assertEquals(180, boundary.editor().width());
    assertEquals(180, boundary.information().orElseThrow().width());
    var wide = FormGeometry.workspace(900, 40, 240, WORKSPACE, false, true);
    assertEquals(160, wide.navigation().orElseThrow().width());
    assertEquals(220, wide.information().orElseThrow().width());
    assertEquals(504, wide.editor().width());
    assertTrue(
        FormGeometry.workspace(900, 40, 240, WORKSPACE, false, false).information().isEmpty());
  }

  @Test
  void workspaceNeverClipsAnEditorOrOverflowsEvenWithExtremeFontMeasurements() {
    for (int width : List.of(1, 2, 100, 299, 300, 487, 488, 900, 16384)) {
      var layout = FormGeometry.workspace(width, 4, 80, WORKSPACE, true, true);
      var editor = layout.editor();
      assertTrue(
          editor.x() >= 0 && editor.width() >= 1 && (long) editor.x() + editor.width() <= width);
      for (var at : List.of(layout.navigation(), layout.information()))
        at.ifPresent(
            value ->
                assertTrue(
                    value.x() >= 0
                        && value.width() >= 1
                        && (long) value.x() + value.width() <= width));
      if (!layout.navigationOverlay())
        layout.navigation().ifPresent(value -> assertTrue(value.x() + value.width() <= editor.x()));
      layout.information().ifPresent(value -> assertTrue(editor.x() + editor.width() <= value.x()));
    }
    var huge =
        new FormGeometry.WorkspaceMetrics(
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE);
    assertTrue(FormGeometry.workspace(320, 4, 80, huge, true, true).compactNavigation());
    assertThrows(
        IllegalArgumentException.class,
        () -> FormGeometry.workspace(0, 4, 80, WORKSPACE, false, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> FormGeometry.workspace(320, 80, 80, WORKSPACE, false, false));
  }

  @Test
  void catalogColumnsRespectBothMinimaAndCompactBoundaryWithoutOverflow() {
    assertTrue(FormGeometry.columns(299, 112, 180, 150, 8).isEmpty());
    assertEquals(
        new FormGeometry.Columns(112, 120, 180),
        FormGeometry.columns(300, 112, 180, 150, 8).orElseThrow());
    assertEquals(
        new FormGeometry.Columns(200, 208, 392),
        FormGeometry.columns(600, 112, 180, 200, 8).orElseThrow());
    assertEquals(112, FormGeometry.columns(600, 112, 180, 50, 8).orElseThrow().firstWidth());
    assertTrue(FormGeometry.columns(600, Integer.MAX_VALUE, Integer.MAX_VALUE, 200, 8).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> FormGeometry.columns(0, 1, 1, 1, 0));
  }

  @Test
  void columnsRequireAnExplicitRelationshipAndBothMinimumWidths() {
    assertFalse(FormGeometry.pair(null, 170, null, 170, 1000, 12));
    assertFalse(FormGeometry.pair("limits", 170, "appearance", 170, 1000, 12));
    assertFalse(FormGeometry.pair("limits", 170, "limits", 240, 491, 12));
    assertTrue(FormGeometry.pair("limits", 170, "limits", 240, 492, 12));
    assertFalse(
        FormGeometry.pair("limits", Integer.MAX_VALUE, "limits", Integer.MAX_VALUE, 1000, 12));
  }

  @Test
  void narrowControlsWrapWithinTheirBoundsAndRetainAuthoredOrder() {
    var sizes =
        List.of(
            new FormGeometry.Size(80, 20),
            new FormGeometry.Size(18, 20),
            new FormGeometry.Size(18, 20),
            new FormGeometry.Size(42, 20));
    var narrow = FormGeometry.controls(sizes, 100, 4);
    assertEquals(44, narrow.height());
    for (var at : narrow.placements()) assertTrue(at.x() >= 0 && at.x() + at.width() <= 100);
    assertEquals(0, narrow.placements().getFirst().y());
    assertEquals(24, narrow.placements().get(1).y());
    var wide = FormGeometry.controls(sizes, 170, 4);
    assertEquals(20, wide.height());
    assertTrue(wide.placements().stream().allMatch(at -> at.y() == 0));
  }

  @Test
  void oversizedControlsClampWhileEmptyRowsHaveNoPhantomHeight() {
    var layout =
        FormGeometry.controls(
            List.of(new FormGeometry.Size(200, 30), new FormGeometry.Size(10, 12)), 70, 4);
    assertEquals(70, layout.placements().getFirst().width());
    assertEquals(46, layout.height());
    assertEquals(0, FormGeometry.controls(List.of(), 10, 4).height());
    assertThrows(IllegalArgumentException.class, () -> FormGeometry.controls(List.of(), 0, 4));
  }
}
