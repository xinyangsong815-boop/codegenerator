<#macro imports values>
<#list values as value>
import ${value};
</#list>
</#macro>

<#macro accessor type name cap jsonExplicit=false>
<#if jsonExplicit>
    @JsonProperty("${name}")
</#if>
    public ${type} get${cap}() {
        return ${name};
    }

<#if jsonExplicit>
    @JsonProperty("${name}")
</#if>
    public void set${cap}(${type} ${name}) {
        this.${name} = ${name};
    }

</#macro>

<#macro field f validate=false wire=true>
    /** ${f.description} */
<#if validate && f.required>
<#if f.type == 'String'>
    @NotBlank
<#else>
    @NotNull
</#if>
</#if>
<#if validate && f.type == 'String'>
    @${package}.common.CodePointSize(max = ${f.length})
</#if>
<#if validate && f.type == 'BigDecimal'>
    @Digits(integer = ${f.precision - f.scale}, fraction = ${f.scale})
</#if>
<#if f.type == 'LocalDate'>
    @${package}.common.DatabaseDateRange
</#if>
<#if f.type == 'LocalDateTime'>
    @${package}.common.TemporalPrecision(${f.temporalPrecision})
</#if>
<#if wire && (f.type == 'LocalDate' || f.type == 'LocalDateTime')>
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
</#if>
<#if wire>
<@numeric type=f.type />
</#if>
    private ${f.type} ${f.name};

</#macro>

<#macro numeric type collection=false>
<#if stringNumbers && (type == 'Long' || type == 'BigDecimal')>
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(<#if collection>contentUsing<#else>using</#if> = <#if type == 'BigDecimal'>${package}.common.NumericWire.DecimalWriter<#else>com.fasterxml.jackson.databind.ser.std.ToStringSerializer</#if>.class)
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(<#if collection>contentUsing<#else>using</#if> = ${package}.common.NumericWire.<#if type == 'Long'>LongReader<#else>DecimalReader</#if>.class)
</#if>
</#macro>
