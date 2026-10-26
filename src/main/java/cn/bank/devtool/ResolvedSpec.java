package cn.bank.devtool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class ResolvedSpec {
    public final Spec spec;
    public final List<ResolvedSpec.FieldRule> fields;
    public final Map<String, Map<String, Object>> endpoints;
    public final Map<String, Object> engineering;
    public final boolean stringNumbers;
    public final boolean strictJson;

    private ResolvedSpec(Spec s) {
        this.spec = s;
        this.endpoints = InterfacePolicy.resolve(s);
        this.engineering = EngineeringPolicy.resolve(s);
        List<ResolvedSpec.FieldRule> values = new ArrayList<>();

        for (Spec.Field f : s.fields()) {
            values.add(new ResolvedSpec.FieldRule(s, f));
        }

        this.fields = Collections.unmodifiableList(values);
        this.stringNumbers = ProductionPolicy.strings(s);
        this.strictJson = ProductionPolicy.strict(s);
    }

    public static ResolvedSpec resolve(Spec s) {
        new SpecValidator().validate(s);
        return new ResolvedSpec(s);
    }

    public static final class FieldRule {
        public final Spec.Field field;
        public final String source;
        public final String defaultSql;
        public final boolean stringNumber;

        private FieldRule(Spec s, Spec.Field f) {
            this.field = f;
            this.source = ProductionPolicy.source(f);
            this.defaultSql = ProductionPolicy.defaultSql(f);
            this.stringNumber = ProductionPolicy.strings(s) && Arrays.asList("Long", "BigDecimal").contains(f.javaType());
        }
    }
}
