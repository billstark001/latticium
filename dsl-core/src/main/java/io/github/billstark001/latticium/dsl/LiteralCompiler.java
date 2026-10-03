package io.github.billstark001.latticium.dsl;

import static io.github.billstark001.latticium.dsl.Model.*;
import static io.github.billstark001.latticium.dsl.Syntax.*;

import io.github.billstark001.latticium.dsl.Compiler.Bound;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves registry literals once and indexes their members for repeated predicate evaluation. */
final class LiteralCompiler {
  private LiteralCompiler() {}

  static Bound bind(Literal literal, SetType expected, Registry registry, boolean symbolic) {
    SetType type = literal.type() == null ? expected : literal.type();
    if (type == null || type == SetType.POS)
      throw new Failure("Literal needs a registry set type", literal.span());
    if (expected != null && type != expected)
      throw new Failure("Expected " + expected + ", got " + type, literal.span());
    var ids = new HashSet<ResourceId>();
    var allStates = new HashSet<ResourceId>();
    var stateFilters = new HashMap<ResourceId, List<Map<String, String>>>();
    for (var member : literal.members()) {
      if (type != SetType.STATE && !member.properties().isEmpty())
        throw new Failure("Properties require StateSet", member.span());
      var status =
          member.tag()
              ? registry.resolveTag(type, member.id())
              : registry.resolve(type == SetType.STATE ? SetType.BLOCK : type, member.id());
      if (status != Registry.Resolution.FOUND)
        throw new Failure(status + " ID/tag: " + member.id(), member.span());
      var memberIds =
          member.tag() ? Set.copyOf(registry.tag(type, member.id())) : Set.of(member.id());
      if (!symbolic
          && type == SetType.STATE
          && !member.properties().isEmpty()
          && !memberIds.isEmpty()
          && memberIds.stream()
              .flatMap(id -> registry.states(id).stream())
              .noneMatch(state -> matches(state.properties(), member.properties())))
        throw new Failure("Invalid state properties", member.span());
      if (type != SetType.STATE) ids.addAll(memberIds);
      else if (member.properties().isEmpty()) allStates.addAll(memberIds);
      else
        for (var id : memberIds)
          stateFilters
              .computeIfAbsent(id, ignored -> new ArrayList<>())
              .add(Map.copyOf(member.properties()));
    }
    if (type != SetType.STATE) {
      var accepted = Set.copyOf(ids);
      return new Bound(
          type,
          (facts, pos, value) ->
              value instanceof ResourceId id && accepted.contains(id) ? Truth.TRUE : Truth.FALSE,
          0);
    }
    if (!symbolic) {
      var legal = new HashSet<BlockState>();
      for (var id : allStates) legal.addAll(registry.states(id));
      stateFilters.forEach(
          (id, filters) -> {
            for (var state : registry.states(id))
              for (var filter : filters)
                if (matches(state.properties(), filter)) {
                  legal.add(state);
                  break;
                }
          });
      var accepted = Set.copyOf(legal);
      return new Bound(
          type,
          (facts, pos, value) ->
              value instanceof BlockState state && accepted.contains(state)
                  ? Truth.TRUE
                  : Truth.FALSE,
          0);
    }
    var anyStateBlocks = Set.copyOf(allStates);
    stateFilters.replaceAll((ignored, filters) -> List.copyOf(filters));
    var filtersByBlock = Map.copyOf(stateFilters);
    return new Bound(
        type,
        (facts, pos, value) -> {
          if (!(value instanceof BlockState state)) return Truth.FALSE;
          if (anyStateBlocks.contains(state.block())) return Truth.TRUE;
          for (var filter : filtersByBlock.getOrDefault(state.block(), List.of()))
            if (matches(state.properties(), filter)) return Truth.TRUE;
          return Truth.FALSE;
        },
        0);
  }

  private static boolean matches(Map<String, String> actual, Map<String, String> wanted) {
    for (var entry : wanted.entrySet())
      if (!entry.getValue().equals(actual.get(entry.getKey()))) return false;
    return true;
  }
}
