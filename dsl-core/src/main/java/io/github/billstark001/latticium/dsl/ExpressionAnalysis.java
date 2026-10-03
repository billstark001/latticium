package io.github.billstark001.latticium.dsl;

import static io.github.billstark001.latticium.dsl.Syntax.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Small AST analyses used before and during function expansion. */
final class ExpressionAnalysis {
  private ExpressionAnalysis() {}

  static Expr substitute(Expr expression, Map<String, Expr> integers) {
    if (integers.isEmpty()) return expression;
    if (expression instanceof Name name) return integers.getOrDefault(name.value(), name);
    if (expression instanceof Binary binary)
      return new Binary(
          binary.operator(),
          substitute(binary.left(), integers),
          substitute(binary.right(), integers),
          binary.span());
    if (expression instanceof Negate negate)
      return new Negate(substitute(negate.inner(), integers), negate.span());
    if (expression instanceof Call call)
      return new Call(
          call.name(),
          call.args().stream().map(argument -> substitute(argument, integers)).toList(),
          call.span());
    return expression;
  }

  static boolean hasUncontextualizedAtom(Expr expression) {
    if (expression instanceof Atom) return true;
    if (expression instanceof Binary binary)
      return hasUncontextualizedAtom(binary.left()) || hasUncontextualizedAtom(binary.right());
    return expression instanceof Negate negate && hasUncontextualizedAtom(negate.inner());
  }

  static boolean needsTypeFromRight(Expr expression) {
    if (expression instanceof Literal literal) return literal.type() == null;
    if (expression instanceof Binary binary) return needsTypeFromRight(binary.left());
    return expression instanceof Negate negate && needsTypeFromRight(negate.inner());
  }

  static void validateFunctionCycles(Map<String, Function> functions) {
    var longestPaths = new HashMap<String, Integer>();
    var visiting = new HashSet<String>();
    for (var name : functions.keySet()) visitFunction(name, functions, visiting, longestPaths, 0);
  }

  private static int visitFunction(
      String name,
      Map<String, Function> functions,
      Set<String> visiting,
      Map<String, Integer> longestPaths,
      int depth) {
    var function = functions.get(name);
    if (depth > Compiler.MAX_EXPANSION_DEPTH)
      throw new Failure("Function dependency depth exceeded", function.span());
    var known = longestPaths.get(name);
    if (known != null) {
      if (depth + known > Compiler.MAX_EXPANSION_DEPTH)
        throw new Failure("Function dependency depth exceeded", function.span());
      return known;
    }
    if (!visiting.add(name)) throw new Failure("Recursive function: " + name, function.span());
    try {
      int longest = visitCalls(function.body(), functions, visiting, longestPaths, depth, 0);
      longestPaths.put(name, longest);
      return longest;
    } finally {
      visiting.remove(name);
    }
  }

  private static int visitCalls(
      Expr expression,
      Map<String, Function> functions,
      Set<String> visiting,
      Map<String, Integer> longestPaths,
      int depth,
      int syntaxDepth) {
    if (syntaxDepth > Compiler.MAX_EXPANSION_DEPTH)
      throw new Failure("Expression depth exceeded", expression.span());
    int longest = 0;
    if (expression instanceof Call call) {
      if (functions.containsKey(call.name()))
        longest = 1 + visitFunction(call.name(), functions, visiting, longestPaths, depth + 1);
      for (var argument : call.args())
        longest =
            Math.max(
                longest,
                visitCalls(argument, functions, visiting, longestPaths, depth, syntaxDepth + 1));
    } else if (expression instanceof Binary binary) {
      longest =
          Math.max(
              visitCalls(binary.left(), functions, visiting, longestPaths, depth, syntaxDepth + 1),
              visitCalls(
                  binary.right(), functions, visiting, longestPaths, depth, syntaxDepth + 1));
    } else if (expression instanceof Negate negate)
      longest =
          visitCalls(negate.inner(), functions, visiting, longestPaths, depth, syntaxDepth + 1);
    return longest;
  }
}
