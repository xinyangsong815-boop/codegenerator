<#import "_macros.ftl" as code>
package ${package}.dto;

<@code.imports values=queryImports />
/** ${description}分页条件。范围含起止端点；CONTAINS 按字面值匹配，不支持通配符。 */
<#if hasRange>@${entity}QueryCheck
</#if>public class ${entity}QueryDTO {

    /** 页码，从 1 开始，最大 100000；未提供时使用 1。 */
    @NotNull
    @Min(1)
    @Max(100000)
    private Integer page = 1;

    /** 每页记录数，范围 1 至 100；未提供时使用 20。 */
    @NotNull
    @Min(1)
    @Max(100)
    private Integer size = 20;

<#list queryFields as field>
<#if field.query == 'RANGE'>
    /** ${field.description}起始值（含）；null 表示不限制下界。 */
<#if field.type == 'BigDecimal'>
    @Digits(integer = ${field.precision - field.scale}, fraction = ${field.scale})
</#if>
<#if field.required>
    @NotNull
</#if>
<#if field.type == "LocalDate" || field.type == "LocalDateTime">
    @DateTimeFormat(iso = DateTimeFormat.ISO.<#if field.type == "LocalDate">DATE<#else>DATE_TIME</#if>)
</#if>
<#if field.type == 'LocalDate'>
    @${package}.common.DatabaseDateRange
</#if>
<#if field.type == 'LocalDateTime'>
    @${package}.common.TemporalPrecision(${field.temporalPrecision})
</#if>
<#if field.type == 'LocalDate' || field.type == 'LocalDateTime'>
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
</#if>
<@code.numeric type=field.type />
    private ${field.type} ${field.name}From;

    /** ${field.description}结束值（含）；null 表示不限制上界。 */
<#if field.type == 'BigDecimal'>
    @Digits(integer = ${field.precision - field.scale}, fraction = ${field.scale})
</#if>
<#if field.required>
    @NotNull
</#if>
<#if field.type == "LocalDate" || field.type == "LocalDateTime">
    @DateTimeFormat(iso = DateTimeFormat.ISO.<#if field.type == "LocalDate">DATE<#else>DATE_TIME</#if>)
</#if>
<#if field.type == 'LocalDate'>
    @${package}.common.DatabaseDateRange
</#if>
<#if field.type == 'LocalDateTime'>
    @${package}.common.TemporalPrecision(${field.temporalPrecision})
</#if>
<#if field.type == 'LocalDate' || field.type == 'LocalDateTime'>
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
</#if>
<@code.numeric type=field.type />
    private ${field.type} ${field.name}To;

<#elseif field.query == 'IN'>
    /** ${field.description}多值条件；null 不限制，空列表拒绝，最多100项。 */
<#if field.required>
    @NotNull
</#if>
    @Size(min = 1, max = 100)
<#if field.type == "LocalDate" || field.type == "LocalDateTime">
    @DateTimeFormat(iso = DateTimeFormat.ISO.<#if field.type == "LocalDate">DATE<#else>DATE_TIME</#if>)
</#if>
<@code.numeric type=field.type collection=true />
    private List< @NotNull <#if field.type == "LocalDate">@${package}.common.DatabaseDateRange </#if><#if field.type == "LocalDateTime">@${package}.common.TemporalPrecision(${field.temporalPrecision}) </#if><#if field.type == 'String'>@${package}.common.CodePointSize(max = ${field.length}) <#elseif field.type == 'BigDecimal'>@Digits(integer = ${field.precision - field.scale}, fraction = ${field.scale}) </#if>${field.type}> ${field.name};

<#else>
    /** ${field.description}条件；null<#if field.type == 'String'> 或空串</#if> 表示不限制。 */
<#if field.required>
<#if field.type == "String">
    @NotBlank
<#else>
    @NotNull
</#if>
</#if>
<#if field.type == 'String'>
    @${package}.common.CodePointSize(max = ${field.length})
</#if>
<#if field.type == 'BigDecimal'>
    @Digits(integer = ${field.precision - field.scale}, fraction = ${field.scale})
</#if>
<#if field.type == "LocalDate" || field.type == "LocalDateTime">
    @DateTimeFormat(iso = DateTimeFormat.ISO.<#if field.type == "LocalDate">DATE<#else>DATE_TIME</#if>)
</#if>
<#if field.type == 'LocalDate'>
    @${package}.common.DatabaseDateRange
</#if>
<#if field.type == 'LocalDateTime'>
    @${package}.common.TemporalPrecision(${field.temporalPrecision})
</#if>
<#if field.type == 'LocalDate' || field.type == 'LocalDateTime'>
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
</#if>
<@code.numeric type=field.type />
    private ${field.type} ${field.name};

</#if>
</#list>
<@code.accessor type="Integer" name="page" cap="Page" />
<@code.accessor type="Integer" name="size" cap="Size" />
    /** 由已校验的 page、size 计算；客户端不能传入或覆盖 offset。 */
    @JsonIgnore
    public int getOffset() {
        return Math.multiplyExact(page - 1, size);
    }

<#list queryFields as field>
<#if field.query == 'RANGE'>
<@code.accessor type=field.type name=field.name+"From" cap=field.cap+"From" jsonExplicit=field.jsonExplicit />
<@code.accessor type=field.type name=field.name+"To" cap=field.cap+"To" jsonExplicit=field.jsonExplicit />
<#else>
<@code.accessor type=(field.query == "IN")?then("List<"+field.type+">", field.type) name=field.name cap=field.cap jsonExplicit=field.jsonExplicit />
<#if field.query == 'CONTAINS'>
    /** MyBatis 内部使用的安全 LIKE 值，不属于接口字段。 */
    @JsonIgnore
    public String get${field.cap}Like() {
        return LikePattern.escape(${field.name});
    }

</#if>
</#if>
</#list>
}
