package ${package};

import ${package}.common.RequestTraceFilter;
import ${package}.common.ApiErrors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

/** 关联编号、无效输入与线程上下文恢复。 */
class RequestTraceFilterTest {
    @Test void validCorrelationIdIsReturnedAndUsedDuringTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/private");
        request.addHeader(RequestTraceFilter.HEADER, "trace_123-abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inside = new AtomicReference<String>();
        new RequestTraceFilter().doFilter(request, response, (a, b) -> inside.set(MDC.get("requestId")));
        assertEquals("trace_123-abc", response.getHeader(RequestTraceFilter.HEADER));
        assertEquals("trace_123-abc", inside.get());
        assertNull(MDC.get("requestId"));
    }

    @Test void controlCharactersAreReplaced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestTraceFilter.HEADER, "bad\r\nInjected: value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RequestTraceFilter().doFilter(request, response, (a,b) -> { });
        assertTrue(response.getHeader(RequestTraceFilter.HEADER).matches("[a-f0-9-]{36}"));
    }

    @Test void oversizedIdIsReplaced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestTraceFilter.HEADER, new String(new char[65]).replace('\0', 'x'));
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RequestTraceFilter().doFilter(request, response, (a,b) -> { });
        assertEquals(36, response.getHeader(RequestTraceFilter.HEADER).length());
    }

    @Test void unrelatedRequestsReceiveDistinctIds() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse(), second = new MockHttpServletResponse();
        RequestTraceFilter filter = new RequestTraceFilter();
        filter.doFilter(new MockHttpServletRequest(), first, (a,b) -> { });
        filter.doFilter(new MockHttpServletRequest(), second, (a,b) -> { });
        assertNotEquals(first.getHeader(RequestTraceFilter.HEADER), second.getHeader(RequestTraceFilter.HEADER));
    }

    @Test void existingThreadContextIsRestoredOnException() throws Exception {
        MDC.put("requestId", "outer-context");
        try {
            assertThrows(java.io.IOException.class, () -> new RequestTraceFilter().doFilter(
                    new MockHttpServletRequest(), new MockHttpServletResponse(), (a,b) -> { throw new java.io.IOException("simulated"); }));
            assertEquals("outer-context", MDC.get("requestId"));
        } finally { MDC.remove("requestId"); }
    }

    @Test void serverErrorUsesTheSameIdWithoutDisclosingDetails() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestTraceFilter.HEADER, "failure-check");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RequestTraceFilter().doFilter(request, response, (a,b) -> {
            String message = new ApiErrors().unexpected(new IllegalStateException("sensitive detail")).getBody().getMessage();
            assertTrue(message.contains("failure-check"));
            assertFalse(message.contains("sensitive detail"));
        });
        assertEquals("failure-check", response.getHeader(RequestTraceFilter.HEADER));
    }
}
