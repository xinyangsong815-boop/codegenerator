package ${package}.common;

import <#if modern>jakarta<#else>javax</#if>.servlet.FilterChain;
import <#if modern>jakarta<#else>javax</#if>.servlet.ServletException;
import <#if modern>jakarta<#else>javax</#if>.servlet.http.HttpServletRequest;
import <#if modern>jakarta<#else>javax</#if>.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 同步请求关联编号；不记录 URL、查询参数、正文或认证信息，不作为身份凭据。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestTraceFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    private static final String ATTRIBUTE = RequestTraceFilter.class.getName() + ".id";
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestTraceFilter.class);

    /** 返回当前线程请求关联编号；未处于过滤器上下文或原值非法时生成新UUID，不可将此编号作为认证或幂等凭据。 */
    public static String currentId() {
        String id = MDC.get("requestId");
        return valid(id) ? id : UUID.randomUUID().toString();
    }

    private static boolean valid(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String previous = MDC.get("requestId");
        String id = (String) request.getAttribute(ATTRIBUTE);
        if (!valid(id)) { id = request.getHeader(HEADER); }
        if (!valid(id)) { id = UUID.randomUUID().toString(); }
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        MDC.put("requestId", id);
        long started = System.nanoTime();
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            LOGGER.info("request_completed method={} status={} completed={} durationMs={} requestId={}",
                    request.getMethod(), response.getStatus(), completed, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), id);
            if (previous == null) { MDC.remove("requestId"); }
            else { MDC.put("requestId", previous); }
        }
    }
}
