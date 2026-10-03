package io.github.billstark001.latticium.dsl;

import java.util.List;
import java.util.Map;

public final class Syntax {
  private Syntax() {}

  public record Span(int start, int end) {}

  public record Diagnostic(String message, Span span) {}

  public static final class Failure extends RuntimeException {
    private final Diagnostic diagnostic;

    public Failure(String message, Span span) {
      super(message + " at " + span.start());
      diagnostic = new Diagnostic(message, span);
    }

    public Diagnostic diagnostic() {
      return diagnostic;
    }
  }

  public sealed interface Expr permits Binary, Negate, Literal, Name, Call, Range, Atom, Pair {
    Span span();
  }

  public record Binary(char operator, Expr left, Expr right, Span span) implements Expr {}

  public record Negate(Expr inner, Span span) implements Expr {}

  public record Member(
      boolean tag, Model.ResourceId id, Map<String, String> properties, Span span) {}

  public record Literal(Model.SetType type, List<Member> members, Span span) implements Expr {}

  public record Name(String value, Span span) implements Expr {}

  public record Call(String name, List<Expr> args, Span span) implements Expr {}

  public record Range(char axis, Model.IntRange range, Span span) implements Expr {}

  public record Atom(Member member, boolean biome, Span span) implements Expr {}

  public record Pair(String key, String value, Span span) implements Expr {}

  public record Declaration(String name, Model.SetType type, Expr expression, Span span) {}

  public record Parameter(String name, Model.SetType type, boolean integer) {}

  public record Function(
      String name, List<Parameter> parameters, Model.SetType result, Expr body, Span span) {}

  public record Terminal(
      String kind, Expr expression, List<Order> order, Integer limit, boolean any, Span span) {}

  public record Order(String key, boolean descending) {}

  public record Document(
      List<Declaration> declarations, List<Function> functions, List<Terminal> terminals) {}
}
