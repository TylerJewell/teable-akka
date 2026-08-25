package io.akka.teable.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** R5–R10: the order the fields are computed in, and what a cycle does to it. */
class FieldOrderTest {

  private static FieldOrder.Node node(String id, String... dependencies) {
    return new FieldOrder.Node(id, List.of(dependencies));
  }

  private static List<String> order(List<FieldOrder.Node> nodes) {
    return FieldOrder.sort(nodes).order();
  }

  @Test
  void everyFieldAppearsExactlyOnce() {
    var result = FieldOrder.sort(List.of(node("a"), node("b", "a"), node("c", "b")));
    assertThat(result.order()).containsExactly("a", "b", "c");
    assertThat(result.cycles()).isEmpty();
  }

  @Test
  void aChainDeclaredBackwardsStillComesOutInDependencyOrder() {
    assertThat(order(List.of(node("c", "b"), node("b", "a"), node("a"))))
        .containsExactly("a", "b", "c");
  }

  @Test
  void anEmptyTableOrdersToNothing() {
    assertThat(order(List.of())).isEmpty();
  }

  @Test
  void fieldsWithNoDependenciesKeepTheirDeclarationOrder() {
    assertThat(order(List.of(node("a"), node("b"), node("c")))).containsExactly("a", "b", "c");
  }

  @Test
  void tiesBreakOnDeclarationIndexNotEdgeOrder() {
    // Both `b` and `c` become ready the moment `a` is placed. R6 says the lower declaration
    // index wins, so the answer is a function of the field list and of nothing else.
    var declaredBFirst = order(List.of(node("a"), node("b", "a"), node("c", "a"), node("d", "b", "c")));
    var declaredCFirst = order(List.of(node("a"), node("c", "a"), node("b", "a"), node("d", "b", "c")));

    assertThat(declaredBFirst).containsExactly("a", "b", "c", "d");
    assertThat(declaredCFirst).containsExactly("a", "c", "b", "d");

    // `q` becomes ready only once `p` is placed, by which time `r` is already waiting with a
    // higher declaration index. A ready set that queues by arrival answers p, r, q; one that
    // keeps itself in declaration order answers p, q, r. The pair above cannot tell the two
    // apart, because there both candidates become ready at the same moment.
    assertThat(order(List.of(node("p"), node("q", "p"), node("r"))))
        .containsExactly("p", "q", "r");
  }

  @Test
  void theOrderIsAFunctionOfTheDeclarationAloneAcrossEveryDependencyPermutation() {
    // The same four fields, with each field's own dependency list shuffled every way it can
    // be. The source's older sort answers differently when the edges arrive in a different
    // order; this one must not.
    var answers = new LinkedHashSet<List<String>>();
    for (List<String> dependenciesOfD : permutations(List.of("b", "c"))) {
      var nodes =
          List.of(
              node("a"),
              node("b", "a"),
              node("c", "a"),
              new FieldOrder.Node("d", dependenciesOfD));
      answers.add(order(nodes));
    }
    assertThat(answers).hasSize(1);
    assertThat(answers.iterator().next()).containsExactly("a", "b", "c", "d");
  }

  @Test
  void anEdgeToAFieldOutsideTheSetIsDropped() {
    var result = FieldOrder.sort(List.of(node("a", "ghost"), node("b", "a")));
    assertThat(result.order()).containsExactly("a", "b");
    assertThat(result.cycles()).isEmpty();

    // `c` is declared first, so a run that leaves `a` unplaceable -- which is what keeping
    // the ghost edge does -- appends all three in declaration order and puts `c` first.
    // Without this second case both versions answer `a, b` and the rule is invisible.
    var withADependentDeclaredFirst =
        FieldOrder.sort(List.of(node("c", "b"), node("a", "ghost"), node("b", "a")));
    assertThat(withADependentDeclaredFirst.order()).containsExactly("a", "b", "c");
    assertThat(withADependentDeclaredFirst.cycles()).isEmpty();
  }

  @Test
  void aDependencyNamedTwiceCountsOnce() {
    var result = FieldOrder.sort(List.of(node("a"), node("b", "a", "a")));
    assertThat(result.order()).containsExactly("a", "b");
    assertThat(result.cycles()).isEmpty();
  }

  @Test
  void aSelfReferenceIsAOneElementCycleAndTheFieldStillAppears() {
    var result = FieldOrder.sort(List.of(node("a", "a")));
    assertThat(result.order()).containsExactly("a");
    assertThat(result.cycles()).containsExactly(List.of("a"));
  }

  @Test
  void aTwoElementCycleIsReportedAsOnePath() {
    var result = FieldOrder.sort(List.of(node("a", "b"), node("b", "a")));
    assertThat(result.order()).containsExactly("a", "b");
    assertThat(result.cycles()).containsExactly(List.of("a", "b"));
  }

  @Test
  void aThreeElementCycleIsReportedAlongItsDependencyDirection() {
    var result = FieldOrder.sort(List.of(node("a", "c"), node("b", "a"), node("c", "b")));
    assertThat(result.order()).containsExactly("a", "b", "c");
    assertThat(result.cycles()).containsExactly(List.of("a", "c", "b"));
  }

  @Test
  void placeableFieldsComeFirstAndUnplaceableOnesAreAppendedInDeclarationOrder() {
    var result =
        FieldOrder.sort(List.of(node("a", "c"), node("b", "a"), node("c", "b"), node("z")));
    assertThat(result.order()).containsExactly("z", "a", "b", "c");
    assertThat(result.cycles()).containsExactly(List.of("a", "c", "b"));
  }

  @Test
  void aCleanFieldFedByACycleIsUnplaceableToo() {
    var result = FieldOrder.sort(List.of(node("a", "b"), node("b", "a"), node("z", "a")));
    assertThat(result.order()).containsExactly("a", "b", "z");
    assertThat(result.cycles()).containsExactly(List.of("a", "b"));
  }

  @Test
  void twoDisjointCyclesAreReportedAsTwoPaths() {
    var result =
        FieldOrder.sort(List.of(node("a", "b"), node("b", "a"), node("x", "y"), node("y", "x")));
    assertThat(result.order()).containsExactly("a", "b", "x", "y");
    assertThat(result.cycles()).containsExactly(List.of("a", "b"), List.of("x", "y"));
  }

  private static <T> List<List<T>> permutations(List<T> items) {
    if (items.size() <= 1) {
      return List.of(items);
    }
    List<List<T>> result = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      var rest = new ArrayList<>(items);
      var head = rest.remove(i);
      for (var tail : permutations(rest)) {
        var one = new ArrayList<T>();
        one.add(head);
        one.addAll(tail);
        result.add(one);
      }
    }
    return result;
  }

  @Test
  void permutationsHelperCoversEveryOrdering() {
    Set<List<String>> seen = new LinkedHashSet<>(permutations(List.of("a", "b", "c")));
    assertThat(seen).hasSize(6);
  }
}
