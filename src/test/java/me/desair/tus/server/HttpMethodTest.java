package me.desair.tus.server;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

public class HttpMethodTest {

    @Test
    public void forName() throws Exception {
        assertEquals(HttpMethod.forName("delete"), HttpMethod.DELETE);
        assertEquals(HttpMethod.forName("get"), HttpMethod.GET);
        assertEquals(HttpMethod.forName("head"), HttpMethod.HEAD);
        assertEquals(HttpMethod.forName("patch"), HttpMethod.PATCH);
        assertEquals(HttpMethod.forName("post"), HttpMethod.POST);
        assertEquals(HttpMethod.forName("put"), HttpMethod.PUT);
        assertEquals(HttpMethod.forName("options"), HttpMethod.OPTIONS);
        assertEquals(HttpMethod.forName("test"), null);
    }

    @Test
    public void getMethodNormal() throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setMethod("patch");

        assertEquals(HttpMethod.getMethod(servletRequest), HttpMethod.PATCH);
    }

    @Test
    public void getMethodOverridden() throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setMethod("post");
        servletRequest.addHeader(HttpHeader.METHOD_OVERRIDE, "patch");

        assertEquals(HttpMethod.getMethod(servletRequest), HttpMethod.PATCH);
    }

    @Test
    public void getMethodOverriddenDoesNotExist() throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setMethod("post");
        servletRequest.addHeader(HttpHeader.METHOD_OVERRIDE, "test");

        assertEquals(HttpMethod.getMethod(servletRequest), HttpMethod.POST);
    }

    @Test(expected = NullPointerException.class)
    public void getMethodNull() throws Exception {
        HttpMethod.getMethod(null);
    }
}