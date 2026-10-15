import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Enumerate registrations from compiled ScalaTest suites without running tests.
 * The caller supplies SBT's actual discovered suite list and runtime classpath.
 * This produces an inventory, never behavioral qualification evidence.
 */
public final class Inventory {
  private static String quote(String value) {
    StringBuilder out = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\' || c == '"') out.append('\\').append(c);
      else if (c < 32) out.append(String.format("\\u%04x", (int)c));
      else out.append(c);
    }
    return out.append('"').toString();
  }
  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 2) throw new IllegalArgumentException("suite-list output-json");
    List<String> suites = Files.readAllLines(Paths.get(arguments[0]), StandardCharsets.UTF_8);
    Collections.sort(suites);
    Class<?> iteratorType = Class.forName("scala.collection.Iterator");
    Method hasNext = iteratorType.getMethod("hasNext");
    Method next = iteratorType.getMethod("next");
    StringBuilder json = new StringBuilder("{\n");
    for (int i = 0; i < suites.size(); i++) {
      String name = suites.get(i);
      Object suite = Class.forName(name).getConstructor().newInstance();
      Object registered = suite.getClass().getMethod("testNames").invoke(suite);
      Method iterator = registered.getClass().getMethod("iterator");
      iterator.setAccessible(true);
      Object cursor = iterator.invoke(registered);
      List<String> names = new ArrayList<>();
      while ((Boolean)hasNext.invoke(cursor)) names.add((String)next.invoke(cursor));
      Collections.sort(names);
      if (names.isEmpty()) throw new IllegalStateException("empty suite: " + name);
      json.append("  ").append(quote(name)).append(": [");
      for (int j = 0; j < names.size(); j++) {
        if (j > 0) json.append(", ");
        json.append(quote(names.get(j)));
      }
      json.append("]").append(i + 1 == suites.size() ? "\n" : ",\n");
    }
    json.append("}\n");
    Files.write(Paths.get(arguments[1]), json.toString().getBytes(StandardCharsets.UTF_8));
  }
}
