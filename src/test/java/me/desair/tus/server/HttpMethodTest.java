package me.desair.tus.server;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

public class HttpMethodTest {

    @Test
    public void forName() throws Exception {
        assertEquals(HttpMethod.DELETE, HttpMethod.forName("delete"));
        assertEquals(HttpMethod.GET, HttpMethod.forName("get"));
        assertEquals(HttpMethod.HEAD, HttpMethod.forName("head"));
        assertEquals(HttpMethod.PATCH, HttpMethod.forName("patch"));
        assertEquals(HttpMethod.POST, HttpMethod.forName("post"));
        assertEquals(HttpMethod.PUT, HttpMethod.forName("put"));
        assertEquals(HttpMethod.OPTIONS, HttpMethod.forName("options"));
        assertEquals(null, HttpMethod.forName("test"));
    }

    @Test
    public void getMethodNormal() throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setMethod("patch");

        assertEquals(HttpMethod.PATCH, HttpMethod.getMethod(servletRequest));
    }

    @Test
    public void getMethodOverridden() throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setMethod("post");
        servletRequest.addHeader(HttpHeader.METHOD_OVERRIDE, "patch");

        assertEquals(HttpMethod.PATCH, HttpMethod.getMethod(servletRequest));
    }

    @Test
    public void getMethodOverriddenDoesNotExist() throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setMethod("post");
        servletRequest.addHeader(HttpHeader.METHOD_OVERRIDE, "test");

        assertEquals(HttpMethod.POST, HttpMethod.getMethod(servletRequest));
    }

    @Test(expected = NullPointerException.class)
    public void getMethodNull() throws Exception {
        HttpMethod.getMethod(null);
    }
}