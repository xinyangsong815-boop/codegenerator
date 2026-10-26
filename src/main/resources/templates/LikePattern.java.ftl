package ${package}.common;

/** 与 Mapper 中 ESCAPE '!' 配套；只转义 LIKE 通配符，SQL 值仍通过 #{...} 绑定。 */
public final class LikePattern {

    private LikePattern() {
    }

    public static String escape(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
