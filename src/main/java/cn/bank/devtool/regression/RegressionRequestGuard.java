package cn.bank.devtool.regression;

import cn.bank.devtool.Util;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.JsonParser.Feature;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-2147483618)
public final class RegressionRequestGuard extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        boolean regression = req.getRequestURI().startsWith("/api/regression/");
        // Other JSON endpoints use the strict shared Jackson configuration. Their existing
        // document/collection limits differ; do not impose the regression limit on source bundles.
        if (regression && (req.getMethod().equals("POST") || req.getMethod().equals("PUT"))) {
            byte[] bytes;
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];

                int size;
                while ((size = req.getInputStream().read(chunk)) != -1) {
                    if (out.size() + size > (regression ? 1048576 : 5242880)) {
                        throw new IllegalArgumentException("JSON请求超过限制");
                    }

                    out.write(chunk, 0, size);
                }

                bytes = out.toByteArray();
                if (bytes.length > 0) {
                    validate(bytes, regression ? 30 : 80, regression ? 50000 : 100000);
                }
            } catch (IllegalArgumentException var8) {
                res.setStatus(400);
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write(Util.pretty(Util.mapOf("code", "ERROR", "message", "请求过大、过深或JSON结构不合法")));
                return;
            }

            final byte[] body = bytes;
            chain.doFilter(new HttpServletRequestWrapper(req) {
                public ServletInputStream getInputStream() {
                    final ByteArrayInputStream in = new ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        public int read() {
                            return in.read();
                        }

                        public boolean isFinished() {
                            return in.available() == 0;
                        }

                        public boolean isReady() {
                            return true;
                        }

                        public void setReadListener(ReadListener listener) {
                            throw new UnsupportedOperationException();
                        }
                    };
                }

                public BufferedReader getReader() {
                    return new BufferedReader(new InputStreamReader(this.getInputStream(), StandardCharsets.UTF_8));
                }
            }, res);
        } else {
            chain.doFilter(req, res);
        }
    }

    public static void validate(byte[] bytes) { validate(bytes, 30, 50000); }

    private static void validate(byte[] bytes, int maxDepth, int maxTokens) {
        try {
            JsonParser p = Util.JSON.getFactory().createParser(bytes);

            try {
                p.enable(Feature.STRICT_DUPLICATE_DETECTION);
                int depth = 0;
                int count = 0;
                int roots = 0;

                JsonToken t;
                while ((t = p.nextToken()) != null) {
                    if (++count > maxTokens) {
                        throw new IllegalArgumentException();
                    }

                    if (depth == 0) {
                        roots++;
                    }

                    if (t.isStructStart()) {
                        depth++;
                    }

                    if (depth > maxDepth) {
                        throw new IllegalArgumentException();
                    }

                    if (t.isStructEnd()) {
                        depth--;
                    }
                }

                if (roots != 1) {
                    throw new IllegalArgumentException();
                }
            } catch (Throwable var7) {
                if (p != null) {
                    try {
                        p.close();
                    } catch (Throwable var6) {
                        var7.addSuppressed(var6);
                    }
                }

                throw var7;
            }

            if (p != null) {
                p.close();
            }
        } catch (Exception var8) {
            throw new IllegalArgumentException("JSON结构不合法");
        }
    }
}
