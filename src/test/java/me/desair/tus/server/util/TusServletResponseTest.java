package me.desair.tus.server.util;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;

import java.util.Locale;
import java.util.TimeZone;

import org.apache.commons.lang3.time.FastDateFormat;
import org.apache.commons.lang3.time.TimeZones;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletResponse;

public class TusServletResponseTest {

    private static final FastDateFormat DATE_FORMAT = FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss",
            TimeZone.getTimeZone(TimeZones.GMT_ID), Locale.US);

    private TusServletResponse tusServletResponse;
    private MockHttpServletResponse servletResponse;

    @Before
    public void setUp() {
        servletResponse = new MockHttpServletResponse();
        tusServletResponse = new TusServletResponse(servletResponse);
    }

    @Test
    public void setDateHeader() throws Exception {
        tusServletResponse.setDateHeader("TEST", DATE_FORMAT.parse("2018-01-03 22:34:14").getTime());
        tusServletResponse.setDateHeader("TEST", DATE_FORMAT.parse("2018-01-03 22:38:14").getTime());

        assertThat(tusServletResponse.getHeader("TEST"),
                is("" + DATE_FORMAT.parse("2018-01-03 22:38:14").getTime()));
        assertThat(servletResponse.getHeaders("TEST"),
                contains("" + DATE_FORMAT.parse("2018-01-03 22:38:14").getTime()));
    }

    @Test
    public void addDateHeader() throws Exception {
        tusServletResponse.addDateHeader("TEST", DATE_FORMAT.parse("2018-01-03 22:34:12").getTime());
        tusServletResponse.addDateHeader("TEST", DATE_FORMAT.parse("2018-01-03 22:38:14").getTime());

        assertThat(tusServletResponse.getHeader("TEST"),
                is("" + DATE_FORMAT.parse("2018-01-03 22:34:12").getTime()));
        assertThat(servletResponse.getHeaders("TEST"), containsInAnyOrder(
                "" + DATE_FORMAT.parse("2018-01-03 22:34:12").getTime(),
                "" + DATE_FORMAT.parse("2018-01-03 22:38:14").getTime()));
    }

    @Test
    public void setHeader() throws Exception {
        tusServletResponse.setHeader("TEST", "foo");
        tusServletResponse.setHeader("TEST", "bar");

        assertThat(tusServletResponse.getHeader("TEST"), is("bar"));
        assertThat(servletResponse.getHeaders("TEST"), contains("bar"));
    }

    @Test
    public void addHeader() throws Exception {
        tusServletResponse.addHeader("TEST", "foo");
        tusServletResponse.addHeader("TEST", "bar");

        assertThat(tusServletResponse.getHeader("TEST"), is("foo"));
        assertThat(servletResponse.getHeaders("TEST"), containsInAnyOrder("foo", "bar"));
    }

    @Test
    public void setIntHeader() throws Exception {
        tusServletResponse.setIntHeader("TEST", 1);
        tusServletResponse.setIntHeader("TEST", 2);

        assertThat(tusServletResponse.getHeader("TEST"), is("2"));
        assertThat(servletResponse.getHeaders("TEST"), contains("2"));
    }

    @Test
    public void addIntHeader() throws Exception {
        tusServletResponse.addIntHeader("TEST", 1);
        tusServletResponse.addIntHeader("TEST", 2);

        assertThat(tusServletResponse.getHeader("TEST"), is("1"));
        assertThat(servletResponse.getHeaders("TEST"), contains("1", "2"));
    }

    @Test
    public void getHeaderNull() throws Exception {
        assertThat(tusServletResponse.getHeader("TEST"), is(nullValue()));
    }

}