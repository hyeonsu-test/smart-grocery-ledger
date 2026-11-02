package sgl;

import java.math.BigDecimal;
import java.util.*;

/** Small strict JSON codec used to keep the application free of external libraries. */
public final class Json {
  private Json() {}

  /**
   * Decodes one JSON value. Duplicate keys, trailing data and excessive nesting are rejected.
   *
   * @param text UTF-8 text already decoded by the caller
   * @return maps, lists, strings, BigDecimal numbers, booleans or null
   * @throws IllegalArgumentException if JSON is malformed
   */
  public static Object parse(String text) {
    Parser p = new Parser(text);
    Object value = p.value(0);
    p.ws();
    if (p.i != text.length()) p.bad();
    return value;
  }

  /**
   * Encodes a supported JSON tree.
   *
   * @param value JSON-compatible value
   * @return JSON text
   * @throws IllegalArgumentException for unsupported values
   */
  public static String stringify(Object value) {
    if (value == null) return "null";
    if (value instanceof String s) {
      StringBuilder b = new StringBuilder("\"");
      for (char c : s.toCharArray())
        switch (c) {
          case '"' -> b.append("\\\"");
          case '\\' -> b.append("\\\\");
          case '\n' -> b.append("\\n");
          case '\r' -> b.append("\\r");
          case '\t' -> b.append("\\t");
          default -> {
            if (c < 32) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
          }
        }
      return b.append('"').toString();
    }
    if (value instanceof Boolean
        || value instanceof BigDecimal
        || value instanceof Integer
        || value instanceof Long) return value.toString();
    if (value instanceof Map<?, ?> m) {
      StringJoiner j = new StringJoiner(",", "{", "}");
      for (var e : m.entrySet()) {
        if (!(e.getKey() instanceof String))
          throw new IllegalArgumentException("JSON key must be text");
        j.add(stringify(e.getKey()) + ":" + stringify(e.getValue()));
      }
      return j.toString();
    }
    if (value instanceof Iterable<?> a) {
      StringJoiner j = new StringJoiner(",", "[", "]");
      for (Object x : a) j.add(stringify(x));
      return j.toString();
    }
    throw new IllegalArgumentException(
        "Unsupported JSON type: " + value.getClass().getSimpleName());
  }

  /**
   * Deep-copies a JSON value.
   *
   * @param value JSON tree
   * @return independent copy
   */
  public static Object copy(Object value) {
    return parse(stringify(value));
  }

  private static final class Parser {
    final String s;
    int i;

    Parser(String s) {
      this.s = Objects.requireNonNull(s);
    }

    void bad() {
      throw new IllegalArgumentException("잘못된 JSON (위치 " + i + ")");
    }

    void ws() {
      while (i < s.length() && " \n\r\t".indexOf(s.charAt(i)) >= 0) i++;
    }

    char take() {
      if (i >= s.length()) bad();
      return s.charAt(i++);
    }

    Object value(int depth) {
      if (depth > 100) bad();
      ws();
      if (i >= s.length()) bad();
      char c = s.charAt(i);
      if (c == '"') return string();
      if (c == '{') {
        i++;
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (i < s.length() && s.charAt(i) == '}') {
          i++;
          return m;
        }
        while (true) {
          ws();
          if (i >= s.length() || s.charAt(i) != '"') bad();
          String k = string();
          ws();
          if (take() != ':') bad();
          if (m.containsKey(k)) bad();
          m.put(k, value(depth + 1));
          ws();
          char end = take();
          if (end == '}') return m;
          if (end != ',') bad();
        }
      }
      if (c == '[') {
        i++;
        List<Object> a = new ArrayList<>();
        ws();
        if (i < s.length() && s.charAt(i) == ']') {
          i++;
          return a;
        }
        while (true) {
          a.add(value(depth + 1));
          ws();
          char end = take();
          if (end == ']') return a;
          if (end != ',') bad();
        }
      }
      for (String literal : List.of("true", "false", "null"))
        if (s.startsWith(literal, i)) {
          i += literal.length();
          return literal.equals("null") ? null : literal.equals("true");
        }
      int start = i;
      while (i < s.length() && "-+0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
      String n = s.substring(start, i);
      if (!n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?") || n.length() > 200) bad();
      try {
        BigDecimal d = new BigDecimal(n);
        if (Math.abs(d.scale()) > 1000) bad();
        return d;
      } catch (NumberFormatException e) {
        bad();
        return null;
      }
    }

    String string() {
      if (take() != '"') bad();
      StringBuilder b = new StringBuilder();
      while (i < s.length()) {
        char c = take();
        if (c == '"') return b.toString();
        if (c < 32) bad();
        if (c == '\\') {
          c = take();
          switch (c) {
            case '"', '\\', '/' -> b.append(c);
            case 'b' -> b.append('\b');
            case 'f' -> b.append('\f');
            case 'n' -> b.append('\n');
            case 'r' -> b.append('\r');
            case 't' -> b.append('\t');
            case 'u' -> {
              if (i + 4 > s.length()) bad();
              try {
                b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
              } catch (NumberFormatException e) {
                bad();
              }
              i += 4;
            }
            default -> bad();
          }
        } else b.append(c);
      }
      bad();
      return null;
    }
  }
}
