package io.akka.teable.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R1–R4: what a formula says it depends on, and which expressions are refused outright. */
class FormulaReferencesTest {

  private static final String A = "fldAAAAAAAAAAAAAAAA";
  private static final String B = "fldBBBBBBBBBBBBBBBB";

  @Test
  void anExpressionWithNoReferencesDependsOnNothing() {
    assertThat(Formula.parse("1 + 2").references()).isEmpty();
  }

  @Test
  void oneReferenceIsFound() {
    assertThat(Formula.parse("{" + A + "} + 1").references()).containsExactly(A);
  }

  @Test
  void referencesInsideAFunctionCallAreFound() {
    assertThat(Formula.parse("SUM({" + A + "}, {" + B + "})").references()).containsExactly(A, B);
  }

  @Test
  void aRepeatedReferenceIsListedOnce() {
    assertThat(Formula.parse("{" + A + "} + {" + A + "}").references()).containsExactly(A);
  }

  @Test
  void referencesKeepFirstOccurrenceOrder() {
    assertThat(Formula.parse("{" + B + "} + {" + A + "}").references()).containsExactly(B, A);
  }

  @Test
  void anIdentifierThatIsNotAFieldIdIsRefused() {
    assertThatThrownBy(() -> Formula.parse("{qty} * 2"))
        .isInstanceOf(FormulaException.class)
        .hasMessageContaining("Formula references not found: qty");
  }

  @Test
  void anExpressionThatDoesNotParseIsRefused() {
    assertThatThrownBy(() -> Formula.parse("{" + A + "} +"))
        .isInstanceOf(FormulaException.class)
        .hasMessageContaining("parse error");
  }

  @Test
  void anEmptyExpressionIsRefused() {
    assertThatThrownBy(() -> Formula.parse("")).isInstanceOf(FormulaException.class);
  }

  @Test
  void anUnclosedCallIsRefused() {
    assertThatThrownBy(() -> Formula.parse("SUM(1, 2")).isInstanceOf(FormulaException.class);
  }

  @Test
  void anUnknownFunctionIsRefused() {
    assertThatThrownBy(() -> Formula.parse("NOPE(1)"))
        .isInstanceOf(FormulaException.class)
        .hasMessageContaining("NOPE");
  }
}
