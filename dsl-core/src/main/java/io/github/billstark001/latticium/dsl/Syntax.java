package io.github.billstark001.latticium.dsl;

import java.util.List;
import java.util.Map;

/** Parsed DSL syntax before type checking or registry binding. */
public final class Syntax {
  private Syntax() {}

  /** Half-open UTF-16 offsets into the exact source string passed to {@link Parser}. */
  public record Span(int start, int end) {
    public Span {
      if (start < 0 || end < start) throw new IllegalArgumentException("Invalid source span");
    }
  }

  public record Diagnostic(String message, Span span) {}

  /** A syntax or binding failure with a source span suitable for editor diagnostics. */
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

  public sealed interface Expr
      permits Binary, Negate, Literal, Name, Text, Call, Range, Atom, Pair {
    Span span();
  }

  public record Binary(char operator, Expr left, Expr right, Span span) implements Expr {
    public Binary {
      if (operator != '&' && operator != '|')
        throw new IllegalArgumentException("Invalid set operator");
    }
  }

  public record Negate(Expr inner, Span span) implements Expr {}

  /** One registry member; {@code properties} is meaningful only for a StateSet member. */
  public record Member(
      boolean tag, Model.ResourceId id, Map<String, String> properties, Span span) {
    public Member {
      properties = Map.copyOf(properties);
    }
  }

  /** {@code type == null} denotes a literal whose type must come from its binding context. */
  public record Literal(Model.SetType type, List<Member> members, Span span) implements Expr {
    public Literal {
      members = List.copyOf(members);
    }
  }

  public record Name(String value, Span span) implements Expr {}

  public record Text(String value, Span span) implements Expr {}

  public record Call(String name, List<Expr> args, Span span) implements Expr {
    public Call {
      args = List.copyOf(args);
    }
  }

  /** {@code axis == '\0'} denotes a bare integer range passed to a built-in function. */
  public record Range(char axis, Model.IntRange range, Span span) implements Expr {}

  public record Atom(Member member, boolean biome, Span span) implements Expr {}

  public record Pair(String key, String value, Span span) implements Expr {}

  public record Declaration(String name, Model.SetType type, Expr expression, Span span) {}

  /** An integer parameter has {@code integer == true} and a null set type. */
  public record Parameter(String name, Model.SetType type, boolean integer) {
    public Parameter {
      if (integer == (type != null))
        throw new IllegalArgumentException("Parameter must have exactly one type");
    }
  }

  public record Function(
      String name, List<Parameter> parameters, Model.SetType result, Expr body, Span span) {
    public Function {
      parameters = List.copyOf(parameters);
    }
  }

  /** A read-only query, count or exists operation; {@code limit == null} means unbounded. */
  public record Terminal(
      String kind, Expr expression, List<Order> order, Integer limit, boolean any, Span span) {
    public Terminal {
      order = List.copyOf(order);
    }
  }

  public record Order(String key, boolean descending) {}

  public record Document(
      List<Declaration> declarations, List<Function> functions, List<Terminal> terminals) {
    public Document {
      declarations = List.copyOf(declarations);
      functions = List.copyOf(functions);
      terminals = List.copyOf(terminals);
    }
  }
}
