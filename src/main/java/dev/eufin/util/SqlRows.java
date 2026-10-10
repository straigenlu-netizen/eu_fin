package dev.eufin.util;

import java.util.*;

public final class SqlRows {
  private SqlRows() {}

  public static List<Map<String, Object>> normalized(List<Map<String, Object>> rows) {
    return rows.stream()
        .map(
            row -> {
              Map<String, Object> result = new LinkedHashMap<>();
              row.forEach((key, value) -> result.put(key.toLowerCase(Locale.ROOT), value));
              return result;
            })
        .toList();
  }
}
