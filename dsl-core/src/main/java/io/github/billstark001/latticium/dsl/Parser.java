package io.github.billstark001.latticium.dsl;

import static io.github.billstark001.latticium.dsl.Syntax.*;

import io.github.billstark001.latticium.dsl.Lexer.Token;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Small, bounded parser. Token positions refer to UTF-16 offsets in the supplied source. */
public final class Parser {
  private static final int MAX_NESTING = 128;

  private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");
  private static final Pattern POSITIVE_INTEGER = Pattern.compile("[1-9][0-9]*");
  private static final Pattern RESOURCE_PART = Pattern.compile("[a-z0-9_./-]+");
  private static final Pattern PROPERTY_VALUE = Pattern.compile("[0-9A-Za-z_.+-]+");
  private static final Pattern AXIS = Pattern.compile("[xyz]");
  private static final Pattern SET_PREFIX = Pattern.compile("[bsimf]");
  private static final Set<String> TERMINAL_KINDS = Set.of("query", "count", "exists");
  private static final Set<String> ORDER_KEYS = Set.of("x", "y", "z", "id", "distance2(player)");

  private static boolean matches(Pattern pattern, String value) {
    return pattern.matcher(value).matches();
  }

  private final List<Token> tokens;
  private int cursor;
  private int nesting;

  private Parser(String source) {
    tokens = Lexer.lex(source);
  }

  /** Parses exactly one expression; rejects trailing tokens and reports UTF-16 source spans. */
  public static Expr expression(String source) {
    var p = new Parser(source);
    var e = p.expr();
    p.end();
    return e;
  }

  /** Parses declarations, functions and terminals without binding names or registry IDs. */
  public static Document document(String source) {
    return new Parser(source).document();
  }

  private Token now() {
    return tokens.get(cursor);
  }

  private Token next() {
    return tokens.get(cursor + 1);
  }

  private boolean is(String s) {
    return now().value.equals(s);
  }

  private boolean take(String s) {
    if (is(s)) {
      cursor++;
      return true;
    }
    return false;
  }

  private Token expect(String s) {
    if (!is(s)) throw error("Expected '" + s + "'");
    return tokens.get(cursor++);
  }

  private Failure error(String message) {
    return new Failure(message, now().span());
  }

  private void end() {
    if (!is("<eof>")) throw error("Unexpected token");
  }

  private Token word() {
    if (!matches(NAME, now().value)) throw error("Expected name");
    return tokens.get(cursor++);
  }

  private int integer() {
    var t = now();
    if (!matches(INTEGER, t.value)) throw error("Expected integer");
    try {
      int v = Integer.parseInt(t.value);
      cursor++;
      return v;
    } catch (NumberFormatException e) {
      throw error("Expected integer");
    }
  }

  private String value() {
    var t = now();
    if (t.value.startsWith("\"")) {
      cursor++;
      return Lexer.unescape(t);
    }
    if (!matches(PROPERTY_VALUE, t.value)) throw error("Expected property value");
    cursor++;
    return t.value;
  }

  private Model.ResourceId id(boolean shortAllowed) {
    int start = now().start;
    String first = now().value;
    if (!matches(RESOURCE_PART, first)) throw error("Expected resource ID");
    Token firstToken = tokens.get(cursor++);
    String raw = first;
    if (take(":")) {
      Token separator = tokens.get(cursor - 1);
      String second = now().value;
      if (!matches(RESOURCE_PART, second)) throw error("Expected resource path");
      if (firstToken.end != separator.start || separator.end != now().start)
        throw new Failure("Resource ID must be contiguous", new Span(start, now().end));
      cursor++;
      raw += ":" + second;
    } else if (!shortAllowed)
      throw new Failure("Resource ID needs a namespace", new Span(start, now().start));
    try {
      return Model.ResourceId.parse(raw);
    } catch (IllegalArgumentException e) {
      throw new Failure(e.getMessage(), new Span(start, now().start));
    }
  }

  private Member member(boolean shortAllowed) {
    int start = now().start;
    boolean tag = take("#");
    if (tag && tokens.get(cursor - 1).end != now().start)
      throw new Failure("Tag ID must be contiguous", new Span(start, now().end));
    var id = id(shortAllowed);
    var props = new LinkedHashMap<String, String>();
    if (take("[")) {
      do {
        String k = word().value;
        expect("=");
        if (props.putIfAbsent(k, value()) != null) throw error("Duplicate property");
      } while (take(","));
      expect("]");
    }
    return new Member(tag, id, props, new Span(start, tokens.get(cursor - 1).end));
  }

  private Model.IntRange range() {
    Integer min = null, max = null;
    if (!is("..")) min = integer();
    if (take("..")) {
      if (matches(INTEGER, now().value)) max = integer();
    } else max = min;
    try {
      return new Model.IntRange(min, max);
    } catch (IllegalArgumentException e) {
      throw error(e.getMessage());
    }
  }

  private Expr expr() {
    if (++nesting > MAX_NESTING) throw error("Nesting limit exceeded");
    try {
      return union();
    } finally {
      nesting--;
    }
  }

  private Expr union() {
    Expr e = intersection();
    while (take("|")) {
      Expr right = intersection();
      e = new Binary('|', e, right, new Span(e.span().start(), right.span().end()));
    }
    return e;
  }

  private Expr intersection() {
    Expr e = unary();
    while (take("&")) {
      Expr right = unary();
      e = new Binary('&', e, right, new Span(e.span().start(), right.span().end()));
    }
    return e;
  }

  private Expr unary() {
    if (take("!")) {
      int start = tokens.get(cursor - 1).start;
      if (++nesting > MAX_NESTING) throw error("Nesting limit exceeded");
      try {
        Expr e = unary();
        return new Negate(e, new Span(start, e.span().end()));
      } finally {
        nesting--;
      }
    }
    return primary();
  }

  private Expr primary() {
    int start = now().start;
    if (matches(NAME, now().value) && next().value.equals("=") && !matches(AXIS, now().value)) {
      String key = word().value;
      expect("=");
      String val = value();
      return new Pair(key, val, new Span(start, tokens.get(cursor - 1).end));
    }
    if (matches(RESOURCE_PART, now().value) && next().value.equals(":"))
      return new Atom(member(false), false, new Span(start, tokens.get(cursor - 1).end));
    if (is("..")) {
      var r = range();
      return new Range('\0', r, new Span(start, tokens.get(cursor - 1).end));
    }
    if (take("(")) {
      Expr e = expr();
      expect(")");
      return e;
    }
    if (take("$")) {
      if (tokens.get(cursor - 1).end != now().start)
        throw new Failure("Biome ID must be contiguous", new Span(start, now().end));
      return new Atom(member(false), true, new Span(start, tokens.get(cursor - 1).end));
    }
    if (take("#")) {
      cursor--;
      return new Atom(member(false), false, new Span(start, tokens.get(cursor - 1).end));
    }
    if (is("{") || (matches(SET_PREFIX, now().value) && next().value.equals("{"))) {
      Model.SetType type = null;
      if (!is("{"))
        type =
            switch (tokens.get(cursor++).value) {
              case "b" -> Model.SetType.BLOCK;
              case "s" -> Model.SetType.STATE;
              case "i" -> Model.SetType.ITEM;
              case "m" -> Model.SetType.BIOME;
              default -> Model.SetType.FLUID;
            };
      expect("{");
      var members = new ArrayList<Member>();
      if (!is("}")) {
        do {
          members.add(member(true));
        } while (take(","));
      }
      expect("}");
      return new Literal(type, List.copyOf(members), new Span(start, tokens.get(cursor - 1).end));
    }
    if (matches(AXIS, now().value) && next().value.equals("=")) {
      char axis = now().value.charAt(0);
      cursor += 2;
      var r = range();
      return new Range(axis, r, new Span(start, tokens.get(cursor - 1).end));
    }
    if (matches(INTEGER, now().value)) {
      if (next().value.equals("..")) {
        var r = range();
        return new Range('\0', r, new Span(start, tokens.get(cursor - 1).end));
      }
      cursor++;
      return new Name(tokens.get(cursor - 1).value, new Span(start, tokens.get(cursor - 1).end));
    }
    if (now().value.startsWith("\"")) {
      cursor++;
      return new Text(
          Lexer.unescape(tokens.get(cursor - 1)), new Span(start, tokens.get(cursor - 1).end));
    }
    if (matches(NAME, now().value)) {
      String name = now().value;
      cursor++;
      if (take("(")) {
        var args = new ArrayList<Expr>();
        if (!is(")")) {
          do {
            if (args.isEmpty() && (name.equals("property") || name.equals("state"))) {
              int pairStart = now().start;
              String key = word().value;
              expect("=");
              String val = value();
              args.add(new Pair(key, val, new Span(pairStart, tokens.get(cursor - 1).end)));
            } else args.add(expr());
          } while (take(","));
        }
        expect(")");
        return new Call(name, List.copyOf(args), new Span(start, tokens.get(cursor - 1).end));
      }
      return new Name(name, new Span(start, tokens.get(cursor - 1).end));
    }
    throw error("Expected expression");
  }

  private Model.SetType type() {
    return switch (word().value) {
      case "PosSet" -> Model.SetType.POS;
      case "BlockSet" -> Model.SetType.BLOCK;
      case "StateSet" -> Model.SetType.STATE;
      case "ItemSet" -> Model.SetType.ITEM;
      case "BiomeSet" -> Model.SetType.BIOME;
      case "FluidSet" -> Model.SetType.FLUID;
      default -> throw error("Unknown set type");
    };
  }

  private Document document() {
    var declarations = new ArrayList<Declaration>();
    var functions = new ArrayList<Function>();
    var terminals = new ArrayList<Terminal>();
    while (!is("<eof>")) {
      int start = now().start;
      if (is("def") && matches(NAME, next().value)) {
        cursor++;
        String name = word().value;
        expect("(");
        var ps = new ArrayList<Parameter>();
        if (!is(")")) {
          do {
            String n = word().value;
            expect(":");
            if (is("Int")) {
              cursor++;
              ps.add(new Parameter(n, null, true));
            } else ps.add(new Parameter(n, type(), false));
          } while (take(","));
        }
        expect(")");
        expect(":");
        var result = type();
        expect("=");
        var body = expr();
        expect(";");
        functions.add(
            new Function(
                name, List.copyOf(ps), result, body, new Span(start, tokens.get(cursor - 1).end)));
      } else if (next().value.equals(":")) {
        String name = word().value;
        expect(":");
        Model.SetType t = is("=") ? null : type();
        expect("=");
        var e = expr();
        expect(";");
        declarations.add(new Declaration(name, t, e, new Span(start, tokens.get(cursor - 1).end)));
      } else {
        String kind = word().value;
        if (!TERMINAL_KINDS.contains(kind)) throw error("Expected terminal");
        var e = expr();
        if (!kind.equals("query") && (is("order") || is("limit")))
          throw error("Only query supports order and limit");
        var order = new ArrayList<Order>();
        Integer limit = null;
        boolean any = false;
        if (take("order")) {
          expect("by");
          do {
            String key = word().value;
            if (key.equals("distance2")) {
              expect("(");
              expect("player");
              expect(")");
              key = "distance2(player)";
            }
            if (!ORDER_KEYS.contains(key)) throw error("Invalid order key");
            boolean desc = take("desc");
            if (!desc) take("asc");
            order.add(new Order(key, desc));
          } while (take(","));
        }
        if (take("limit")) {
          any = take("any");
          if (!matches(POSITIVE_INTEGER, now().value)) throw error("Expected positive limit");
          limit = integer();
          if (limit <= 0 || order.isEmpty() && !any || !order.isEmpty() && any)
            throw error("Limit needs either ordering or 'any', and must be positive");
        }
        expect(";");
        terminals.add(
            new Terminal(
                kind,
                e,
                List.copyOf(order),
                limit,
                any,
                new Span(start, tokens.get(cursor - 1).end)));
      }
    }
    return new Document(List.copyOf(declarations), List.copyOf(functions), List.copyOf(terminals));
  }
}
