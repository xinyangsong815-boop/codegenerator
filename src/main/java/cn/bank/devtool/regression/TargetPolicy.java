package cn.bank.devtool.regression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.http.client.utils.URIBuilder;

public final class TargetPolicy {
    private final Map<String, InetAddress[]> origins = new LinkedHashMap<>();

    public TargetPolicy(String allowed) {
        for (String entry : allowed.split(",")) {
            try {
                URI u = new URI(entry.trim());
                String origin = origin(u);
                if (u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null || !u.getPath().isEmpty() && !u.getPath().equals("/")) {
                    throw new Exception();
                }

                InetAddress[] addresses = InetAddress.getAllByName(u.getHost());

                for (InetAddress a : addresses) {
                    if (a.isAnyLocalAddress() || a.isMulticastAddress() || a.isLinkLocalAddress()) {
                        throw new Exception();
                    }
                }

                this.origins.put(origin, addresses);
            } catch (Exception var13) {
                throw new IllegalArgumentException("允许目标配置不合法或DNS不可用");
            }
        }

        if (this.origins.isEmpty()) {
            throw new IllegalArgumentException("至少配置一个允许目标");
        }
    }

    static String origin(URI u) {
        if (Arrays.asList("http", "https").contains(u.getScheme()) && u.getHost() != null) {
            int p = u.getPort() < 0 ? (u.getScheme().equals("https") ? 443 : 80) : u.getPort();
            if (p >= 1 && p <= 65535) {
                String host = u.getHost().toLowerCase(Locale.ROOT);
                return u.getScheme() + "://" + host + ":" + p;
            } else {
                throw new IllegalArgumentException("端口不合法");
            }
        } else {
            throw new IllegalArgumentException("仅允许http/https目标");
        }
    }

    public List<String> allowed() {
        return new ArrayList<>(this.origins.keySet());
    }

    public ObjectNode validate(JsonNode env) {
        ScenarioRules.keys(env, "id,name,baseUrl,pathPrefix,authMode,authHeader,connectTimeoutMs,readTimeoutMs,stepTimeoutMs,scenarioTimeoutMs");
        ScenarioRules.id(env.path("id").asText());
        if (!env.path("name").asText().trim().isEmpty() && env.path("name").asText().length() <= 100) {
            URI u;
            try {
                u = new URI(env.path("baseUrl").asText());
            } catch (Exception var8) {
                throw new IllegalArgumentException("基础地址不合法");
            }

            if (this.origins.containsKey(origin(u))
                && u.getRawUserInfo() == null
                && u.getRawQuery() == null
                && u.getRawFragment() == null
                && (u.getPath().isEmpty() || u.getPath().equals("/"))) {
                String prefix = env.path("pathPrefix").asText("/");
                ScenarioRules.path(prefix);
                if (!prefix.contains("{") && !prefix.contains("}")) {
                    String mode = env.path("authMode").asText("NONE");
                    if (!Arrays.asList("NONE", "TOKEN", "SESSION").contains(mode)) {
                        throw new IllegalArgumentException("认证方式不支持");
                    } else {
                        String header = env.path("authHeader").asText("Authorization");
                        if (!header.equalsIgnoreCase("Authorization")) {
                            ScenarioRules.header(header);
                        }

                        for (String key : Arrays.asList("connectTimeoutMs", "readTimeoutMs", "stepTimeoutMs", "scenarioTimeoutMs")) {
                            if (env.has(key)
                                && (
                                    !env.path(key).isIntegralNumber() || !env.path(key).canConvertToInt()
                                        || env.path(key).asInt() < 100
                                        || env.path(key).asInt() > (key.equals("scenarioTimeoutMs") ? 300000 : 30000)
                                )) {
                                throw new IllegalArgumentException("超时时间越界");
                            }
                        }

                        ObjectNode copy = (ObjectNode)env.deepCopy();
                        copy.put("baseUrl", origin(u)).put("pathPrefix", prefix).put("authMode", mode).put("authHeader", header);
                        return copy;
                    }
                } else {
                    throw new IllegalArgumentException("路径前缀不能包含变量");
                }
            } else {
                throw new IllegalArgumentException("基础地址必须是启动配置允许的目标原点");
            }
        } else {
            throw new IllegalArgumentException("环境名称不合法");
        }
    }

    public InetAddress[] addresses(JsonNode env) throws Exception {
        return (InetAddress[])this.origins.get(origin(new URI(env.path("baseUrl").asText()))).clone();
    }

    public URI uri(JsonNode env, JsonNode request) throws Exception {
        this.validate(env);
        String path = request.path("path").asText();
        ScenarioRules.path(path);
        Iterator<Entry<String, JsonNode>> vars = request.path("pathVariables").fields();

        while (vars.hasNext()) {
            Entry<String, JsonNode> e = vars.next();
            if (e.getValue().isValueNode() && !e.getValue().isNull()) {
                String value = e.getValue().asText();
                if (!value.isEmpty()
                    && !value.contains("/")
                    && !value.contains("\\")
                    && !value.contains("%")
                    && !value.contains(";")
                    && !value.equals(".")
                    && !value.equals("..")
                    && !value.matches(".*[\\x00-\\x1f\\x7f].*")) {
                    path = path.replace("{" + e.getKey() + "}", value);
                    continue;
                }

                throw new IllegalArgumentException("路径参数不合法");
            }

            throw new IllegalArgumentException("路径变量需基本非空值");
        }

        if (!path.contains("{") && !path.contains("}")) {
            String prefix = env.path("pathPrefix").asText();
            if (!prefix.equals("/") && !path.equals(prefix) && !path.startsWith(prefix.endsWith("/") ? prefix : prefix + "/")) {
                throw new IllegalArgumentException("请求超出允许路径");
            } else {
                URIBuilder b = new URIBuilder(env.path("baseUrl").asText()).setPath(path);
                request.path("query").fields().forEachRemaining(e -> {
                    JsonNode value = (JsonNode)e.getValue();
                    if (value.isArray()) {
                        if (value.size() < 1 || value.size() > 100) {
                            throw new IllegalArgumentException("query数组需1至100项");
                        }

                        for (JsonNode item : value) {
                            if (!item.isValueNode() || item.isNull()) {
                                throw new IllegalArgumentException("query数组只允许非空基本值");
                            }

                            b.addParameter((String)e.getKey(), item.asText());
                        }
                    } else {
                        if (!value.isValueNode() || value.isNull()) {
                            throw new IllegalArgumentException("query参数需非空基本值");
                        }

                        b.addParameter((String)e.getKey(), value.asText());
                    }
                });
                return b.build();
            }
        } else {
            throw new IllegalArgumentException("路径变量未解析");
        }
    }
}
