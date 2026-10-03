package io.github.billstark001.latticium.dsl;

import io.github.billstark001.latticium.dsl.Syntax.Failure;
import io.github.billstark001.latticium.dsl.Syntax.Span;
import java.util.ArrayList;
import java.util.List;

/** Bounded tokenization with UTF-16 source offsets. */
final class Lexer {
  private static final int MAX_SOURCE_LENGTH = 65_536;
  private static final int MAX_STRING_LENGTH = 8_192;
  private static final int MAX_TOKENS = 4_096;

  static final class Token {
    final String value;
    final int start;
    final int end;

    Token(String value, int start, int end) {
      this.value = value;
      this.start = start;
      this.end = end;
    }

    Span span() {
      return new Span(start, end);
    }
  }

  private Lexer() {}

  static List<Token> lex(String source) {
    if (source.length() > MAX_SOURCE_LENGTH)
      throw new Failure("Source too large", new Span(0, source.length()));
    var tokens = new ArrayList<Token>();
    for (int i = 0; i < source.length(); ) {
      char c = source.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
        while (i < source.length() && !lineBreak(source.charAt(i))) i++;
        continue;
      }
      int start = i;
      if (c == '"') {
        i++;
        while (i < source.length() && source.charAt(i) != '"') {
          if (source.charAt(i) == '\\') {
            i++;
            if (i == source.length()) break;
          }
          i++;
        }
        if (i - start - 1 > MAX_STRING_LENGTH)
          throw new Failure("String too long", new Span(start, i));
        if (i >= source.length()) throw new Failure("Unclosed string", new Span(start, i));
        i++;
      } else if (c == '.' && i + 1 < source.length() && source.charAt(i + 1) == '.') i += 2;
      else if ("{}[](),:;=!&|#$".indexOf(c) >= 0) i++;
      else if (Character.isLetterOrDigit(c)
          || c == '_'
          || c == '-'
          || c == '/'
          || c == '.'
          || c == '+') {
        i++;
        while (i < source.length()) {
          char n = source.charAt(i);
          if (n == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') break;
          if (n == '.' && i + 1 < source.length() && source.charAt(i + 1) == '.') break;
          if (!(Character.isLetterOrDigit(n)
              || n == '_'
              || n == '-'
              || n == '/'
              || n == '.'
              || n == '+')) break;
          i++;
        }
      } else throw new Failure("Unexpected character", new Span(i, i + 1));
      tokens.add(new Token(source.substring(start, i), start, i));
      if (tokens.size() >= MAX_TOKENS)
        throw new Failure("Source too large", new Span(0, source.length()));
    }
    tokens.add(new Token("<eof>", source.length(), source.length()));
    return List.copyOf(tokens);
  }

  private static boolean lineBreak(char c) {
    return c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029';
  }

  static String unescape(Token token) {
    var raw = token.value;
    var decoded = new StringBuilder();
    for (int i = 1; i < raw.length() - 1; i++) {
      char c = raw.charAt(i);
      if (c == '\\') {
        if (++i >= raw.length() - 1 || raw.charAt(i) != '\\' && raw.charAt(i) != '"')
          throw new Failure("Invalid escape", token.span());
        c = raw.charAt(i);
      }
      decoded.append(c);
    }
    return decoded.toString();
  }
}
