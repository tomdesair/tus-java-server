package me.desair.tus.server.upload;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import me.desair.tus.server.util.Utils;
import org.junit.Before;
import org.junit.Test;

public class TimeBasedUploadIdFactoryTest {

  private UploadIdFactory idFactory;

  @Before
  public void setUp() {
    idFactory = new TimeBasedUploadIdFactory();
  }

  @Test(expected = NullPointerException.class)
  public void setUploadUriNull() throws Exception {
    idFactory.setUploadUri(null);
  }

  @Test
  public void setUploadUriNoTrailingSlash() throws Exception {
    idFactory.setUploadUri("/test/upload");
    assertThat(idFactory.getUploadUri(), is("/test/upload"));
  }

  @Test
  public void setUploadUriWithTrailingSlash() throws Exception {
    idFactory.setUploadUri("/test/upload/");
    assertThat(idFactory.getUploadUri(), is("/test/upload/"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void setUploadUriBlank() throws Exception {
    idFactory.setUploadUri(" ");
  }

  @Test(expected = IllegalArgumentException.class)
  public void setUploadUriNoStartingSlash() throws Exception {
    idFactory.setUploadUri("test/upload/");
  }

  @Test(expected = IllegalArgumentException.class)
  public void setUploadUriEndsWithDollar() throws Exception {
    idFactory.setUploadUri("/test/upload$");
  }

  @Test
  public void readUploadId() throws Exception {
    idFactory.setUploadUri("/test/upload");

    assertThat(idFactory.readUploadId("/test/upload/1546152320043"), hasToString("1546152320043"));
  }

  @Test
  public void readUploadIdRegex() throws Exception {
    idFactory.setUploadUri("/users/[0-9]+/files/upload");

    assertThat(
        idFactory.readUploadId("/users/1337/files/upload/1546152320043"),
        hasToString("1546152320043"));
  }

  @Test
  public void readUploadIdTrailingSlash() throws Exception {
    idFactory.setUploadUri("/test/upload/");

    assertThat(idFactory.readUploadId("/test/upload/1546152320043"), hasToString("1546152320043"));
  }

  @Test
  public void readUploadIdRegexTrailingSlash() throws Exception {
    idFactory.setUploadUri("/users/[0-9]+/files/upload/");

    assertThat(
        idFactory.readUploadId("/users/123456789/files/upload/1546152320043"),
        hasToString("1546152320043"));
  }

  @Test
  public void readUploadIdNoUuid() throws Exception {
    idFactory.setUploadUri("/test/upload");

    assertThat(idFactory.readUploadId("/test/upload/not-a-time-value"), is(nullValue()));
  }

  @Test
  public void readUploadIdRegexNoMatch() throws Exception {
    idFactory.setUploadUri("/users/[0-9]+/files/upload");

    assertThat(idFactory.readUploadId("/users/files/upload/1546152320043"), is(nullValue()));
  }

  @Test
  public void createId() throws Exception {
    UploadId id = idFactory.createId();
    assertThat(id, not(nullValue()));
    Utils.sleep(10);
    assertThat(
        Long.parseLong(id.getOriginalObject().toString()),
        greaterThan(System.currentTimeMillis() - 1000L));
    assertThat(
        Long.parseLong(id.getOriginalObject().toString()), lessThan(System.currentTimeMillis()));
  }
}
