package design.inherent.reform.edit;

import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordTypes;
import design.inherent.reform.value.TaggedType;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.Optional;
import java.util.UUID;

/** The same exact record/variant codecs as document values; no private patch protocol. */
final class DraftEditTypes {
  private static final ValueType<Optional<UUID>> ROW =
      ValueTypes.optional(ValueTypes.uuid(), ValueShape.OptionalEncoding.SINGLETON_LIST);
  private static final ValueType<Optional<Long>> VERSION =
      ValueTypes.optional(
          ValueTypes.longInteger(0, Long.MAX_VALUE), ValueShape.OptionalEncoding.SINGLETON_LIST);
  private static final ValueType<FieldPath.Segment> SEGMENT =
      TaggedType.<FieldPath.Segment>builder("kind", "value")
          .variant(
              "field",
              FieldPath.Field.class,
              RecordTypes.record(FieldPath.Field.class)
                  .field("id", FieldKey.of("id", ValueTypes.text(4096)))
                  .build())
          .variant(
              "row",
              FieldPath.Row.class,
              RecordTypes.record(FieldPath.Row.class)
                  .register(UUID.class, ValueTypes.uuid())
                  .build())
          .variant(
              "variant",
              FieldPath.Variant.class,
              RecordTypes.record(FieldPath.Variant.class)
                  .field("id", FieldKey.of("id", ValueTypes.text(4096)))
                  .build())
          .variant("part", FieldPath.Part.class, ValueTypes.enumeration(FieldPath.Part.class))
          .build();
  private static final ValueType<FieldPath> PATH =
      RecordTypes.record(FieldPath.class)
          .field("segments", FieldKey.of("segments", ValueTypes.list(SEGMENT, 128)))
          .build();
  private static final ValueType<DraftTarget> TARGET =
      RecordTypes.record(DraftTarget.class)
          .register(UUID.class, ValueTypes.uuid())
          .register(FieldPath.class, PATH)
          .field("generation", FieldKey.of("generation", ValueTypes.longInteger(0, Long.MAX_VALUE)))
          .field("version", FieldKey.of("version", VERSION))
          .build();
  private static final ValueType<DraftEdit.Action> ACTION =
      TaggedType.<DraftEdit.Action>builder("kind", "value")
          .variant(
              "raw",
              DraftEdit.SetRaw.class,
              RecordTypes.record(DraftEdit.SetRaw.class)
                  .field("text", FieldKey.of("text", ValueTypes.text(32768)))
                  .build())
          .variant(
              "unit_raw",
              DraftEdit.SetUnitRaw.class,
              RecordTypes.record(DraftEdit.SetUnitRaw.class)
                  .field("unit", FieldKey.of("unit", ValueTypes.text(64)))
                  .field("text", FieldKey.of("text", ValueTypes.text(32768)))
                  .build())
          .variant(
              "insert",
              DraftEdit.InsertRow.class,
              RecordTypes.record(DraftEdit.InsertRow.class)
                  .field("before", FieldKey.of("before", ROW))
                  .build())
          .variant(
              "scalar_rows",
              DraftEdit.ChangeScalarRows.class,
              RecordTypes.record(DraftEdit.ChangeScalarRows.class)
                  .field(
                      "append", FieldKey.of("append", ValueTypes.list(ValueTypes.text(32768), 256)))
                  .field("remove", FieldKey.of("remove", ValueTypes.set(ValueTypes.uuid(), 256)))
                  .build())
          .variant(
              "duplicate",
              DraftEdit.DuplicateRow.class,
              RecordTypes.record(DraftEdit.DuplicateRow.class)
                  .register(UUID.class, ValueTypes.uuid())
                  .field("before", FieldKey.of("before", ROW))
                  .build())
          .variant(
              "remove",
              DraftEdit.RemoveRow.class,
              RecordTypes.record(DraftEdit.RemoveRow.class)
                  .register(UUID.class, ValueTypes.uuid())
                  .build())
          .variant(
              "move",
              DraftEdit.MoveRow.class,
              RecordTypes.record(DraftEdit.MoveRow.class)
                  .register(UUID.class, ValueTypes.uuid())
                  .field("before", FieldKey.of("before", ROW))
                  .build())
          .variant(
              "presence",
              DraftEdit.Presence.class,
              RecordTypes.record(DraftEdit.Presence.class).build())
          .variant(
              "override",
              DraftEdit.Override.class,
              RecordTypes.record(DraftEdit.Override.class).build())
          .variant(
              "variant",
              DraftEdit.SelectVariant.class,
              RecordTypes.record(DraftEdit.SelectVariant.class)
                  .field("id", FieldKey.of("id", ValueTypes.text(4096)))
                  .build())
          .variant(
              "discard_branch",
              DraftEdit.DiscardBranch.class,
              RecordTypes.record(DraftEdit.DiscardBranch.class)
                  .field("id", FieldKey.of("id", ValueTypes.text(4096)))
                  .build())
          .variant(
              "restore_override",
              DraftEdit.RestoreOverride.class,
              RecordTypes.record(DraftEdit.RestoreOverride.class)
                  .register(UUID.class, ValueTypes.uuid())
                  .build())
          .variant(
              "discard_override",
              DraftEdit.DiscardOverride.class,
              RecordTypes.record(DraftEdit.DiscardOverride.class)
                  .register(UUID.class, ValueTypes.uuid())
                  .build())
          .variant(
              "remove_unknown",
              DraftEdit.RemoveUnknown.class,
              RecordTypes.record(DraftEdit.RemoveUnknown.class)
                  .field("field", FieldKey.of("field", ValueTypes.text(4096)))
                  .build())
          .variant(
              "repair", DraftEdit.Repair.class, RecordTypes.record(DraftEdit.Repair.class).build())
          .build();

  static final ValueType<DraftEdit> TYPE =
      RecordTypes.record(DraftEdit.class)
          .register(DraftTarget.class, TARGET)
          .register(DraftEdit.Action.class, ACTION)
          .build();

  private DraftEditTypes() {}
}
