package me.desair.tus.server.rufh;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import me.desair.tus.server.ProtocolVersion;
import org.junit.Test;

/** Tests for {@link ResumableUploadsForHttpProtocol} and its interim response strategies. */
public class ResumableUploadsForHttpProtocolTest {

  @Test
  public void testProtocolExtension() {
    ResumableUploadsForHttpProtocol protocol = new ResumableUploadsForHttpProtocol();
    assertThat(protocol.getName(), is(ResumableUploadsForHttpProtocol.EXTENSION_NAME));

    // Test mustReprocessOnError coverage
    assertThat(
        protocol.mustReprocessOnError(me.desair.tus.server.HttpMethod.POST, ProtocolVersion.RUFH),
        is(true));
    assertThat(
        protocol.mustReprocessOnError(
            me.desair.tus.server.HttpMethod.POST, ProtocolVersion.TUS_1_0_0),
        is(false));
  }
}
