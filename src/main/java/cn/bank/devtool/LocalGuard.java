package cn.bank.devtool;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-2147483628)
public class LocalGuard extends OncePerRequestFilter {
    private final String token = UUID.randomUUID().toString();

    public String token() {
        return this.token;
    }

    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String peer = req.getRemoteAddr();
        if (peer != null && (peer.startsWith("127.") || peer.equals("::1") || peer.equals("0:0:0:0:0:0:0:1"))) {
            String origin = req.getHeader("Origin");
            String expected = req.getScheme() + "://" + req.getHeader("Host");
            String host = req.getServerName();
            if (!host.equals("localhost") && !host.equals("127.0.0.1") && !host.equals("[::1]") && !host.equals("::1")) {
                this.reject(res, "本机工具仅支持本机访问，请使用localhost或127.0.0.1");
            } else if (origin != null && !origin.equals(expected)) {
                this.reject(res, "拒绝跨站请求");
            } else if (req.getRequestURI().startsWith("/api/") && !req.getMethod().equals("GET") && !this.token.equals(req.getHeader("X-Tool-Token"))) {
                this.reject(res, "缺少本机操作令牌，请刷新页面");
            } else {
                res.setHeader("X-Content-Type-Options", "nosniff");
                res.setHeader("X-Frame-Options", "DENY");
                res.setHeader("Cache-Control", "no-store");
                chain.doFilter(req, res);
            }
        } else {
            this.reject(res, "本机工具只允许本地连接");
        }
    }

    private void reject(HttpServletResponse res, String message) throws IOException {
        res.setStatus(403);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write(Util.pretty(Util.mapOf("code", "FORBIDDEN", "message", message)));
    }
}
