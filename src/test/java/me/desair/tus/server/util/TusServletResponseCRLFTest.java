package me.desair.tus.server.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletResponse;

public class TusServletResponseCRLFTest {
  private TusServletResponse tusServletResponse;
  private MockHttpServletResponse servletResponse;

  @Before
  public void setUp() {
    servletResponse = new MockHttpServletResponse();
    tusServletResponse = new TusServletResponse(servletResponse);
  }

  @Test
  public void testCRLFInjectionInSetHeader() {
    tusServletResponse.setHeader("TEST", "value\r\nInjected-Header: true");
    assertThat(tusServletResponse.getHeader("TEST"), is("valueInjected-Header: true"));
    assertThat(servletResponse.getHeader("TEST"), is("valueInjected-Header: true"));
  }

  @Test
  public void testCRLFInjectionInAddHeader() {
    tusServletResponse.addHeader("TEST", "value\r\nInjected-Header: true");
    assertThat(tusServletResponse.getHeader("TEST"), is("valueInjected-Header: true"));
    assertThat(servletResponse.getHeader("TEST"), is("valueInjected-Header: true"));
  }
}
