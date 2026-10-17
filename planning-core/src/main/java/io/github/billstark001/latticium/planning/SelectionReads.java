package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Parser;
import io.github.billstark001.latticium.dsl.Syntax.*;
import java.util.HashSet;
import java.util.Set;

/** Named selection dependencies for the native declaration-free profile expression surface. */
public final class SelectionReads {
  private SelectionReads() {}

  public static Set<String> in(Profile profile) {
    var names = new HashSet<String>();
    if (profile.activation() != null) visit(Parser.expression(profile.activation().where()), names);
    visit(Parser.expression(profile.scope()), names);
    visit(Parser.expression(profile.select().where()), names);
    if (profile.target() instanceof Profile.Items items) {
      visit(Parser.expression(items.expression()), names);
      if (items.states() != null) visit(Parser.expression(items.states()), names);
    } else if (profile.target() instanceof Profile.Source source && source.using() != null)
      visit(Parser.expression(source.using()), names);
    return Set.copyOf(names);
  }

  private static void visit(Expr expression, Set<String> names) {
    if (expression instanceof Call call) {
      if (call.name().equals("selection") && !call.args().isEmpty()) {
        var argument = call.args().getFirst();
        if (argument instanceof Text text) names.add(text.value());
        else if (argument instanceof Name name) names.add(name.value());
      }
      for (var argument : call.args()) visit(argument, names);
    } else if (expression instanceof Binary binary) {
      visit(binary.left(), names);
      visit(binary.right(), names);
    } else if (expression instanceof Negate negate) visit(negate.inner(), names);
  }
}
