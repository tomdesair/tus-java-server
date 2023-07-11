package me.desair.tus.server.upload;

import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import org.junit.Before;
import org.junit.Test;

public class UUIDUploadIdFactoryTest {

  private UploadIdFactory idFactory;

  @Before
  public void setUp() {
    idFactory = new UUIDUploadIdFactory();
  }

  @Test(expected = NullPointerException.class)
  public void setUploadURINull() throws Exception {
    idFactory.setUploadURI(null);
  }

  @Test
  public void setUploadURINoTrailingSlash() throws Exception {
    idFactory.setUploadURI("/test/upload");
    assertThat(idFactory.getUploadURI(), is("/test/upload"));
  }

  @Test
  public void setUploadURIWithTrailingSlash() throws Exception {
    idFactory.setUploadURI("/test/upload/");
    assertThat(idFactory.getUploadURI(), is("/test/upload/"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void setUploadURIBlank() throws Exception {
    idFactory.setUploadURI(" ");
  }

  @Test(expected = IllegalArgumentException.class)
  public void setUploadURINoStartingSlash() throws Exception {
    idFactory.setUploadURI("test/upload/");
  }

  @Test(expected = IllegalArgumentException.class)
  public void setUploadURIEndsWithDollar() throws Exception {
    idFactory.setUploadURI("/test/upload$");
  }

  @Test
  public void readUploadId() throws Exception {
    idFactory.setUploadURI("/test/upload");

    assertThat(
        idFactory.readUploadId("/test/upload/1911e8a4-6939-490c-b58b-a5d70f8d91fb"),
        hasToString("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));
  }

  @Test
  public void readUploadIdRegex() throws Exception {
    idFactory.setUploadURI("/users/[0-9]+/files/upload");

    assertThat(
        idFactory.readUploadId("/users/1337/files/upload/1911e8a4-6939-490c-b58b-a5d70f8d91fb"),
        hasToString("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));
  }

  @Test
  public void readUploadIdTrailingSlash() throws Exception {
    idFactory.setUploadURI("/test/upload/");

    assertThat(
        idFactory.readUploadId("/test/upload/1911e8a4-6939-490c-b58b-a5d70f8d91fb"),
        hasToString("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));
  }

  @Test
  public void readUploadIdRegexTrailingSlash() throws Exception {
    idFactory.setUploadURI("/users/[0-9]+/files/upload/");

    assertThat(
        idFactory.readUploadId(
            "/users/123456789/files/upload/1911e8a4-6939-490c-b58b-a5d70f8d91fb"),
        hasToString("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));
  }

  @Test
  public void readUploadIdNoUUID() throws Exception {
    idFactory.setUploadURI("/test/upload");

    assertThat(idFactory.readUploadId("/test/upload/not-a-uuid-value"), is(nullValue()));
  }

  @Test
  public void readUploadIdRegexNoMatch() throws Exception {
    idFactory.setUploadURI("/users/[0-9]+/files/upload");

    assertThat(
        idFactory.readUploadId("/users/files/upload/1911e8a4-6939-490c-b58b-a5d70f8d91fb"),
        is(nullValue()));
  }

  @Test
  public void createId() throws Exception {
    assertThat(idFactory.createId(), not(nullValue()));
  }
}
