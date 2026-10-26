// Inject the host Axios instance to reuse its authentication and interceptors.
export function create${entity}Api(http) {
    const querySerializer = (params) => {
        const result = new URLSearchParams();
        for (const [name, value] of Object.entries(params || {})) {
            if (value === undefined || value === null) continue;
            if (Array.isArray(value) && value.length === 0) throw new Error('查询列表不能为空：' + name);
            for (const item of Array.isArray(value) ? value : [value]) {
                if (item === undefined || item === null) throw new Error('查询列表不能包含空元素：' + name);
                result.append(name, String(item));
            }
        }
        return result.toString();
    };
    const integer = (value, name, minimum, maximum, asString, required = false) => {
        if (value === null || value === undefined) {
            if (required) throw new Error('必须提供整数：' + name);
            return;
        }
        if (asString) {
            if (typeof value !== 'string' || !/^-?(0|[1-9][0-9]*)$/.test(value)) throw new Error('必须使用十进制整数字符串：' + name);
            const parsed = BigInt(value);
            if (parsed < BigInt(minimum) || parsed > BigInt(maximum)) throw new Error('整数超过范围：' + name);
        } else if (!Number.isSafeInteger(value) || value < Number(minimum) || value > Number(maximum)) {
            throw new Error('必须使用范围内的安全整数：' + name);
        }
    };
    const longValue = (value, name, required = false, minimum = '-9223372036854775808') =>
        integer(value, name, minimum, '9223372036854775807', <#if stringNumbers>true<#else>false</#if>, required);
    const decimal = (value, name) => {
        if (value === null || value === undefined) return;
<#if stringNumbers>
        if (typeof value !== 'string' || !/^-?(0|[1-9][0-9]*)(\.[0-9]+)?$/.test(value)) throw new Error('金额必须使用十进制字符串：' + name);
<#else>
        if (typeof value !== 'number' || !Number.isFinite(value) || Math.abs(value) > Number.MAX_SAFE_INTEGER)
            throw new Error('金额数值无效或超过安全范围，请使用字符串数字协议：' + name);
</#if>
    };
    const validateData = (operation, data) => {
        if (data === null || typeof data !== 'object' || Array.isArray(data)) throw new Error('请求参数必须为对象');
        const each = (value, name, check, list = false) => {
            if (list && value !== undefined && value !== null) {
                if (!Array.isArray(value) || !value.length || value.some(item => item === null || item === undefined)) throw new Error('查询列表无效：' + name);
                value.forEach(item => check(item, name));
            } else check(value, name);
        };
        switch (operation) {
<#list apis as api>
            case '${api.operation}':
<#list fields as field>
<#if api.fields?seq_contains(field.name) && (field.type == 'Long' || field.type == 'Integer' || field.type == 'BigDecimal')>
<#assign check="longValue">
<#if field.type == 'Integer'><#assign check="(value, name) => integer(value, name, '-2147483648', '2147483647', false)"></#if>
<#if field.type == 'BigDecimal'><#assign check="decimal"></#if>
<#if api.operation == 'PAGE' && field.query == 'RANGE'>
                each(data.${field.name}From, '${field.name}From', ${check});
                each(data.${field.name}To, '${field.name}To', ${check});
<#else>
                each(data.${field.name}, '${field.name}', ${check}<#if api.operation == 'PAGE' && field.query == 'IN'>, true</#if>);
</#if>
</#if>
</#list>
<#if api.operation == 'PAGE'>
                integer(data.page, 'page', '1', '100000', false);
                integer(data.size, 'size', '1', '100', false);
</#if>
<#if api.operation == 'UPDATE' && optimisticLock>
                longValue(data.expectedVersion, 'expectedVersion', true, '0');
</#if>
                break;
</#list>
        }
    };
    return {
<#list apis as api>
        ${api.operation?lower_case}: (<#if api.idIn != 'NONE'>id<#if api.requestIn != 'NONE'>, </#if></#if><#if api.requestIn != 'NONE'>data</#if><#if api.operation == 'DELETE' && deleteVersionCheck>, expectedVersion</#if>) => {
<#if api.idIn != 'NONE'>
            longValue(id, 'id', true, '1');
</#if>
<#if api.requestIn != 'NONE'>
            validateData('${api.operation}', data);
</#if>
<#if api.operation == 'UPDATE'>
            if (data.id !== undefined && data.id !== null) {
                longValue(data.id, 'id', true, '1');
                if (data.id !== id) throw new Error('请求体ID必须与接口ID一致');
            }
</#if>
<#if api.operation == 'DELETE' && deleteVersionCheck>
            longValue(expectedVersion, 'expectedVersion', true, '0');
</#if>
            return http.request({
                method: '${api.method?lower_case}',
                url: <#if api.idIn == 'PATH'>'${api.url}'.replace('{id}', encodeURIComponent(id))<#else>'${api.url}'</#if>,
<#if api.requestIn == 'QUERY' || api.idIn == 'QUERY' || (api.operation == 'DELETE' && deleteVersionCheck && api.idIn != 'BODY')>
                params: {<#if api.requestIn == 'QUERY'>...data<#if api.idIn == 'QUERY'>, </#if></#if><#if api.idIn == 'QUERY'>id</#if><#if api.operation == 'DELETE' && deleteVersionCheck && api.idIn != 'BODY'><#if api.idIn == 'QUERY'>, </#if>expectedVersion</#if>},
                paramsSerializer: querySerializer,
</#if>
<#if api.body>
                data: <#if api.wrapped>{<#if api.idIn == 'BODY'>id, </#if>${api.bodyProperty}: data}<#elseif api.requestIn == 'BODY'><#if api.idIn == 'BODY'>{...data, id}<#else>data</#if><#else>{id<#if api.operation == 'DELETE' && deleteVersionCheck>, expectedVersion</#if>}</#if>,
</#if>
            });
        }<#sep>,</#sep>
</#list>
    };
}
