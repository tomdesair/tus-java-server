package me.desair.tus.server.util;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class StructuredHeaderUtilTest {

  @Test
  public void testParseBoolean() {
    assertThat(StructuredHeaderUtil.parseBoolean("?1"), is(Boolean.TRUE));
    assertThat(StructuredHeaderUtil.parseBoolean("?0"), is(Boolean.FALSE));
    assertThat(StructuredHeaderUtil.parseBoolean(" ?1 "), is(Boolean.TRUE));
    assertThat(StructuredHeaderUtil.parseBoolean("true"), nullValue());
    assertThat(StructuredHeaderUtil.parseBoolean(""), nullValue());
    assertThat(StructuredHeaderUtil.parseBoolean(null), nullValue());
  }

  @Test
  public void testFormatBoolean() {
    assertThat(StructuredHeaderUtil.formatBoolean(true), is("?1"));
    assertThat(StructuredHeaderUtil.formatBoolean(false), is("?0"));
  }

  @Test
  public void testParseInteger() {
    assertThat(StructuredHeaderUtil.parseInteger("12345"), is(12345L));
    assertThat(StructuredHeaderUtil.parseInteger(" 0 "), is(0L));
    assertThat(StructuredHeaderUtil.parseInteger("abc"), nullValue());
    assertThat(StructuredHeaderUtil.parseInteger(""), nullValue());
    assertThat(StructuredHeaderUtil.parseInteger(null), nullValue());
  }

  @Test
  public void testParseDictionary() {
    Map<String, Object> dict =
        StructuredHeaderUtil.parseDictionary("max-size=100, max-age=3600, active");
    assertThat(dict.get("max-size"), is(100L));
    assertThat(dict.get("max-age"), is(3600L));
    assertThat(dict.get("active"), is(Boolean.TRUE));
  }

  @Test
  public void testFormatDictionary() {
    Map<String, Object> dict = new LinkedHashMap<>();
    dict.put("max-size", 100000L);
    dict.put("completed", true);
    assertThat(StructuredHeaderUtil.formatDictionary(dict), is("max-size=100000, completed=?1"));
  }

  @Test
  public void testFormatDictionaryEdgeCases() {
    assertThat(StructuredHeaderUtil.formatDictionary(null), is(""));
    assertThat(StructuredHeaderUtil.formatDictionary(new LinkedHashMap<>()), is(""));

    Map<String, Object> dict = new LinkedHashMap<>();
    dict.put("null-val", null);
    dict.put("max-size", 100L);
    assertThat(StructuredHeaderUtil.formatDictionary(dict), is("max-size=100"));
  }

  @Test
  public void testParseDictionaryEdgeCases() {
    assertThat(StructuredHeaderUtil.parseDictionary(null).isEmpty(), is(true));
    assertThat(StructuredHeaderUtil.parseDictionary("").isEmpty(), is(true));
    assertThat(StructuredHeaderUtil.parseDictionary("   ").isEmpty(), is(true));

    Map<String, Object> dict1 = StructuredHeaderUtil.parseDictionary(",,max-size=100");
    assertThat(dict1.get("max-size"), is(100L));

    Map<String, Object> dict2 =
        StructuredHeaderUtil.parseDictionary("active=?0, valid=?1, name=value");
    assertThat(dict2.get("active"), is(Boolean.FALSE));
    assertThat(dict2.get("valid"), is(Boolean.TRUE));
    assertThat(dict2.get("name"), is("value"));
  }

  @Test
  public void testSanitizeHeaderValue() {
    assertThat(StructuredHeaderUtil.sanitizeHeaderValue("valid\r\nvalue"), is("validvalue"));
    assertThat(StructuredHeaderUtil.sanitizeHeaderValue("test\0null"), is("testnull"));
    assertThat(StructuredHeaderUtil.sanitizeHeaderValue(null), nullValue());
  }
}
