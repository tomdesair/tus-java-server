package me.desair.tus.server.util;

import java.util.Map;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

/** Custom Matcher class used in tests */
public class MapMatcher {

  private MapMatcher() {}

  public static <K, V> Matcher<Map<? extends K, ? extends V>> hasSize(final int size) {
    return new TypeSafeMatcher<Map<? extends K, ? extends V>>() {
      @Override
      public boolean matchesSafely(Map<? extends K, ? extends V> kvMap) {
        return kvMap.size() == size;
      }

      @Override
      public void describeTo(Description description) {
        description.appendText(" has ").appendValue(size).appendText(" key/value pairs");
      }
    };
  }
}
