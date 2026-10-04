package io.github.billstark001.latticium.dsl;

import io.github.billstark001.latticium.dsl.Syntax.*;
import java.util.Set;

/** Finds direct target-view reads in a parsed expression. */
public final class TargetReads {
  private static final Set<String> TARGET_BUILTINS =
      Set.of("target", "has_target", "matches_target", "changed", "same", "compare");

  private TargetReads() {}

  /** Checks the AST; callers with user functions must expand them before using this result. */
  public static boolean in(Expr expression) {
    if (expression instanceof Call call) {
      if (TARGET_BUILTINS.contains(call.name())) return true;
      return call.args().stream().anyMatch(TargetReads::in);
    }
    if (expression instanceof Binary binary) return in(binary.left()) || in(binary.right());
    return expression instanceof Negate negate && in(negate.inner());
  }
}
