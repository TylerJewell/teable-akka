package io.akka.teable.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The order a table's fields are computed in.
 *
 * <p>Kahn's algorithm over dependency to dependent edges, with the ready set kept sorted by
 * declaration index. That tie-break is the whole point: it makes the answer a function of the
 * field list and of nothing else, where an ordering that walks edges answers differently
 * depending on the order the edges were discovered.
 *
 * <p>A cycle is not an error here. Fields Kahn's cannot place are appended after the ones it
 * can, so the result always names every field, and each distinct cycle is reported as its own
 * path for the caller to refuse on.
 */
public final class FieldOrder {

  public record Node(String id, List<String> dependencies) {}

  public record Result(List<String> order, List<List<String>> cycles) {}

  private FieldOrder() {}

  public static Result sort(List<Node> nodes) {
    Map<String, Integer> declarationIndex = new LinkedHashMap<>();
    Map<String, List<String>> dependenciesById = new LinkedHashMap<>();
    for (int i = 0; i < nodes.size(); i++) {
      var node = nodes.get(i);
      declarationIndex.put(node.id(), i);
      dependenciesById.put(node.id(), node.dependencies());
    }

    Map<String, Set<String>> dependents = new HashMap<>();
    Map<String, Integer> inDegree = new LinkedHashMap<>();
    for (var node : nodes) {
      inDegree.put(node.id(), 0);
    }
    for (var node : nodes) {
      for (var dependency : dependenciesById.get(node.id())) {
        // An edge to a field outside the set is dropped rather than stalling either end.
        if (!declarationIndex.containsKey(dependency)) {
          continue;
        }
        var targets = dependents.computeIfAbsent(dependency, key -> new LinkedHashSet<>());
        if (targets.add(node.id())) {
          inDegree.merge(node.id(), 1, Integer::sum);
        }
      }
    }

    // Kept sorted by declaration index rather than re-sorted on every insertion: the tie-break
    // is the whole point of this ordering, and a set that carries it costs a log rather than a
    // scan per field becoming ready.
    var ready = new TreeSet<String>(Comparator.comparingInt(declarationIndex::get));
    for (var entry : inDegree.entrySet()) {
      if (entry.getValue() == 0) {
        ready.add(entry.getKey());
      }
    }

    List<String> placed = new ArrayList<>();
    Set<String> placedIds = new HashSet<>();
    while (!ready.isEmpty()) {
      var current = ready.pollFirst();
      placed.add(current);
      placedIds.add(current);
      var targets = dependents.get(current);
      if (targets == null) {
        continue;
      }
      for (var target : targets) {
        var remaining = inDegree.merge(target, -1, Integer::sum);
        if (remaining == 0) {
          ready.add(target);
        }
      }
    }

    List<String> unplaced = new ArrayList<>();
    for (var node : nodes) {
      if (!placedIds.contains(node.id())) {
        unplaced.add(node.id());
      }
    }
    unplaced.sort((a, b) -> Integer.compare(declarationIndex.get(a), declarationIndex.get(b)));

    var order = new ArrayList<>(placed);
    order.addAll(unplaced);
    return new Result(List.copyOf(order), findCycles(unplaced, dependenciesById));
  }

  private static List<List<String>> findCycles(
      List<String> unplaced, Map<String, List<String>> dependenciesById) {
    Set<String> remaining = new HashSet<>(unplaced);
    Set<String> visited = new HashSet<>();
    Set<String> onPath = new LinkedHashSet<>();
    List<String> path = new ArrayList<>();
    Map<String, Integer> positionOnPath = new HashMap<>();
    List<List<String>> cycles = new ArrayList<>();
    Set<String> seenCycles = new LinkedHashSet<>();

    for (var id : unplaced) {
      if (!visited.contains(id)) {
        walk(id, remaining, visited, onPath, path, positionOnPath, cycles, seenCycles,
            dependenciesById);
      }
    }
    return List.copyOf(cycles);
  }

  private static void walk(
      String id,
      Set<String> remaining,
      Set<String> visited,
      Set<String> onPath,
      List<String> path,
      Map<String, Integer> positionOnPath,
      List<List<String>> cycles,
      Set<String> seenCycles,
      Map<String, List<String>> dependenciesById) {
    visited.add(id);
    onPath.add(id);
    positionOnPath.put(id, path.size());
    path.add(id);

    for (var dependency : dependenciesById.getOrDefault(id, List.of())) {
      if (!remaining.contains(dependency)) {
        continue;
      }
      if (!visited.contains(dependency)) {
        walk(dependency, remaining, visited, onPath, path, positionOnPath, cycles, seenCycles,
            dependenciesById);
        continue;
      }
      if (onPath.contains(dependency)) {
        var cycle = List.copyOf(path.subList(positionOnPath.get(dependency), path.size()));
        if (seenCycles.add(String.join(">", cycle))) {
          cycles.add(cycle);
        }
      }
    }

    onPath.remove(id);
    positionOnPath.remove(id);
    path.remove(path.size() - 1);
  }
}
