package io.akka.teable.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** R12–R14: what one edit changes, and how far it travels. */
class RecalculationTest {

  private static final String QTY = "fldQTYQTYQTYQTYQTY0";
  private static final String PRICE = "fldPRICEPRICEPRICE0";
  private static final String TOTAL = "fldTOTALTOTALTOTAL0";
  private static final String TAXED = "fldTAXEDTAXEDTAXED0";
  private static final String LABEL = "fldLABELLABELLABEL0";

  private static Table chainTable() {
    return Table.empty("tbl1")
        .withField(FieldDef.value(QTY, "qty", ValueType.NUMBER))
        .withField(FieldDef.value(PRICE, "price", ValueType.NUMBER))
        .withField(FieldDef.formula(TOTAL, "total", "{" + QTY + "} * {" + PRICE + "}"))
        .withField(FieldDef.formula(TAXED, "taxed", "{" + TOTAL + "} * 1.1"))
        .withField(
            FieldDef.formula(
                LABEL, "label", "CONCATENATE(\"n=\", {" + TOTAL + "}, \"/\", {" + TAXED + "})"));
  }

  @Test
  void definingAFormulaComputesItForEveryRecordAlreadyThere() {
    var table =
        Table.empty("tbl1")
            .withField(FieldDef.value(QTY, "qty", ValueType.NUMBER))
            .withField(FieldDef.value(PRICE, "price", ValueType.NUMBER))
            .withRecord("rec1", Map.of(QTY, CellValue.number(2), PRICE, CellValue.number(10)))
            .withRecord("rec2", Map.of(QTY, CellValue.number(3), PRICE, CellValue.number(5)))
            .withField(FieldDef.formula(TOTAL, "total", "{" + QTY + "} * {" + PRICE + "}"));

    assertThat(table.cell("rec1", TOTAL)).isEqualTo(CellValue.number(20));
    assertThat(table.cell("rec2", TOTAL)).isEqualTo(CellValue.number(15));
  }

  @Test
  void aChainOfAnyDepthSettlesInOnePass() {
    var table =
        chainTable()
            .withRecord("rec1", Map.of(QTY, CellValue.number(2), PRICE, CellValue.number(10)));

    assertThat(table.cell("rec1", TOTAL)).isEqualTo(CellValue.number(20));
    assertThat(table.cell("rec1", TAXED)).isEqualTo(CellValue.number(22));
    assertThat(table.cell("rec1", LABEL)).isEqualTo(CellValue.text("n=20/22"));
  }

  @Test
  void oneEditTravelsTheWholeChain() {
    var table =
        chainTable()
            .withRecord("rec1", Map.of(QTY, CellValue.number(2), PRICE, CellValue.number(10)))
            .withCell("rec1", QTY, CellValue.number(7));

    assertThat(table.cell("rec1", TOTAL)).isEqualTo(CellValue.number(70));
    assertThat(table.cell("rec1", TAXED)).isEqualTo(CellValue.number(77));
    assertThat(table.cell("rec1", LABEL)).isEqualTo(CellValue.text("n=70/77"));
  }

  @Test
  void editingOneRecordLeavesEveryOtherRecordAlone() {
    var table =
        chainTable()
            .withRecord("rec1", Map.of(QTY, CellValue.number(2), PRICE, CellValue.number(10)))
            .withRecord("rec2", Map.of(QTY, CellValue.number(3), PRICE, CellValue.number(5)))
            .withCell("rec1", QTY, CellValue.number(7));

    assertThat(table.cell("rec2", TOTAL)).isEqualTo(CellValue.number(15));
    assertThat(table.cell("rec2", TAXED)).isEqualTo(CellValue.number(16.5));
    assertThat(table.cell("rec2", LABEL)).isEqualTo(CellValue.text("n=15/16.5"));
  }

  @Test
  void aFormulaReadsWhatEarlierFieldsInTheSamePassProduced() {
    // If `taxed` were computed from the pre-edit `total`, this record would settle at the
    // old value and only reach the new one on a second edit. The expected value carries the
    // full double product rather than 110: no rounding is applied anywhere, and the original
    // renders the same digits into text.
    var table =
        chainTable()
            .withRecord("rec1", Map.of(QTY, CellValue.number(1), PRICE, CellValue.number(1)))
            .withCell("rec1", PRICE, CellValue.number(100));

    assertThat(table.cell("rec1", TOTAL)).isEqualTo(CellValue.number(100));
    assertThat(table.cell("rec1", TAXED)).isEqualTo(CellValue.number(110.00000000000001));
  }

  @Test
  void theOrderTheTableReportsIsTheOrderItComputesIn() {
    assertThat(chainTable().computationOrder()).containsExactly(QTY, PRICE, TOTAL, TAXED, LABEL);
  }

  @Test
  void aCycleIsRefusedAndTheTableIsLeftAsItWas() {
    var before = chainTable();
    var refusal =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            () -> before.withField(FieldDef.formula(TOTAL, "total", "{" + LABEL + "}")),
            TableException.class);

    assertThat(refusal).hasMessageContaining("Formula field dependency cycle detected");
    assertThat(refusal.getMessage()).contains(TOTAL).contains(LABEL);
    assertThat(before.computationOrder()).containsExactly(QTY, PRICE, TOTAL, TAXED, LABEL);
  }

  @Test
  void aSelfReferenceIsRefusedWithAOneFieldCyclePath() {
    var refusal =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            () -> chainTable().withField(FieldDef.formula(TOTAL, "total", "{" + TOTAL + "} + 1")),
            TableException.class);

    assertThat(refusal).hasMessageContaining("Formula field dependency cycle detected: " + TOTAL);
  }

  @Test
  void aFormulaReferencingAFieldThatIsNotInTheTableIsRefused() {
    var refusal =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            () ->
                chainTable()
                    .withField(FieldDef.formula("fldXXXXXXXXXXXXXXXX", "x", "{fldZZZZZZZZZZZZZZZZ}")),
            TableException.class);

    assertThat(refusal).hasMessageContaining("Formula field references not found");
  }

  @Test
  void aFormulaThatRaisesOnAnExistingRecordIsRefused() {
    var table =
        Table.empty("tbl1")
            .withField(FieldDef.value(QTY, "qty", ValueType.NUMBER))
            .withRecord("rec1", Map.of(QTY, CellValue.number(4)))
            .withRecord("rec2", Map.of(QTY, CellValue.number(-2.5)));

    var refusal =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            () -> table.withField(FieldDef.formula(TOTAL, "root", "SQRT({" + QTY + "})")),
            TableException.class);

    assertThat(refusal).hasMessageContaining("Failed to backfill computed fields");
    assertThat(table.fieldIds()).doesNotContain(TOTAL);
  }

  @Test
  void aFormulaThatRaisesOnNoExistingRecordIsAccepted() {
    var table =
        Table.empty("tbl1")
            .withField(FieldDef.value(QTY, "qty", ValueType.NUMBER))
            .withRecord("rec1", Map.of(QTY, CellValue.number(4)))
            .withField(FieldDef.formula(TOTAL, "root", "SQRT({" + QTY + "})"));

    assertThat(table.cell("rec1", TOTAL)).isEqualTo(CellValue.number(2));
    assertThat(table.fieldIds()).contains(TOTAL);
  }

  @Test
  void aRecordAddedLaterIsComputedTheSameWay() {
    var table =
        chainTable().withRecord("rec9", Map.of(QTY, CellValue.number(4), PRICE, CellValue.number(3)));
    assertThat(table.cell("rec9", TOTAL)).isEqualTo(CellValue.number(12));
    assertThat(table.cell("rec9", LABEL)).isEqualTo(CellValue.text("n=12/13.200000000000001"));
  }

  @Test
  void recordsKeepTheOrderTheyWereAddedIn() {
    var table =
        chainTable()
            .withRecord("recB", Map.of(QTY, CellValue.number(1)))
            .withRecord("recA", Map.of(QTY, CellValue.number(2)))
            .withRecord("recC", Map.of(QTY, CellValue.number(3)))
            .withCell("recA", QTY, CellValue.number(9));

    assertThat(table.records().keySet()).containsExactly("recB", "recA", "recC");
  }

  @Test
  void aCheckboxCellSetToFalseIsStoredBlank() {
    var flagField = "fldFLAGFLAGFLAGFLAG";
    var table =
        Table.empty("tbl1")
            .withField(FieldDef.value(flagField, "p", ValueType.CHECKBOX))
            .withField(FieldDef.formula(TOTAL, "notp", "NOT({" + flagField + "})"))
            .withRecord("recOn", Map.of(flagField, CellValue.bool(true)))
            .withRecord("recOff", Map.of(flagField, CellValue.bool(false)))
            .withRecord("recUnset", Map.of());

    assertThat(table.cell("recOn", flagField)).isEqualTo(CellValue.bool(true));
    assertThat(table.cell("recOff", flagField)).isEqualTo(CellValue.blank());
    assertThat(table.cell("recUnset", flagField)).isEqualTo(CellValue.blank());
    assertThat(table.cell("recOff", TOTAL)).isEqualTo(CellValue.bool(true));

    var afterEdit = table.withCell("recOn", flagField, CellValue.bool(false));
    assertThat(afterEdit.cell("recOn", flagField)).isEqualTo(CellValue.blank());
  }

  @Test
  void aFieldWithNoDependenciesIsStillComputed() {
    var table =
        Table.empty("tbl1")
            .withField(FieldDef.formula(TOTAL, "two", "1 + 1"))
            .withRecord("rec1", Map.of());
    assertThat(table.cell("rec1", TOTAL)).isEqualTo(CellValue.number(2));
  }

  @Test
  void theOrderTheChainIsDeclaredInDoesNotChangeTheAnswers() {
    var declaredLast =
        Table.empty("tbl1")
            .withField(FieldDef.value(QTY, "qty", ValueType.NUMBER))
            .withField(FieldDef.value(PRICE, "price", ValueType.NUMBER))
            .withField(FieldDef.formula(TOTAL, "total", "{" + QTY + "} * {" + PRICE + "}"))
            .withField(FieldDef.formula(TAXED, "taxed", "{" + TOTAL + "} * 1.1"))
            .withRecord("rec1", Map.of(QTY, CellValue.number(2), PRICE, CellValue.number(10)));

    assertThat(declaredLast.cell("rec1", TAXED)).isEqualTo(CellValue.number(22));
    assertThat(declaredLast.computationOrder()).isEqualTo(List.of(QTY, PRICE, TOTAL, TAXED));
  }
}
