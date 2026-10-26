<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="${package}.mapper.${entity}Mapper">

    <resultMap id="EntityMap" type="${package}.entity.${entity}">
        <id column="id" property="id"/>
<#if optimisticLock>
        <result column="version" property="version"/>
</#if>
<#if softDelete>
        <result column="deleted" property="deleted"/>
</#if>
<#list fields as field>
        <result column="${field.column}" property="${field.name}"/>
</#list>
    </resultMap>

    <sql id="Columns">
        `id`,
<#if optimisticLock>
        `version`,
</#if>
<#if softDelete>
        `deleted`,
</#if>
<#list fields as field>
        `${field.column}`<#sep>,</#sep>
</#list>
    </sql>

    <sql id="Visible">
<#if scoped>
        AND `${scopeColumn}` = #{scope}
</#if>
<#if softDelete>
        AND `deleted` = FALSE
</#if>
    </sql>

<#if page>
    <!-- 查询条件由参数绑定；LIKE 转义符必须与 LikePattern 保持一致。 -->
    <sql id="Where">
        <where>
            <include refid="Visible"/>
<#list queryFields as field>
<#if field.query == 'RANGE'>
            <if test="query.${field.name}From != null">
                AND `${field.column}` &gt;= #{query.${field.name}From}
            </if>
            <if test="query.${field.name}To != null">
                AND `${field.column}` &lt;= #{query.${field.name}To}
            </if>
<#elseif field.query == 'IN'>
            <if test="query.${field.name} != null and query.${field.name}.size() > 0">
                AND `${field.column}` IN
                <foreach collection="query.${field.name}" item="value" open="(" separator="," close=")">
                    #{value}
                </foreach>
            </if>
<#elseif field.query == 'CONTAINS'>
            <if test="query.${field.name} != null and query.${field.name} != ''">
                AND `${field.column}` LIKE CONCAT('%', #{query.${field.name}Like}, '%') ESCAPE '!'
            </if>
<#else>
            <if test="query.${field.name} != null<#if field.type == 'String'> and query.${field.name} != ''</#if>">
                AND `${field.column}` = #{query.${field.name}}
            </if>
</#if>
</#list>
        </where>
    </sql>

</#if>
<#if create>
    <insert id="insert" useGeneratedKeys="true" keyProperty="entity.id" keyColumn="id">
        INSERT INTO `${table}` (
<#list insertFields as field>
            `${field.column}`<#sep>,</#sep>
</#list>
        ) VALUES (
<#list insertFields as field>
            #{entity.${field.name}}<#sep>,</#sep>
</#list>
        )
    </insert>

</#if>
<#if update>
    <!-- 字段来自生成时白名单，所有值使用绑定；部分更新仅写入显式出现的字段。 -->
    <update id="update">
        UPDATE `${table}`
<#if partialUpdate>
        <set>
<#list editableFields as field>
            <if test="request.${field.name}Present">
                `${field.column}` = #{request.${field.name}},
            </if>
</#list>
<#list serverUpdateFields as field>
            `${field.column}` = #{server.${field.name}},
</#list>
<#if optimisticLock>
            `version` = `version` + 1,
</#if>
        </set>
<#else>
        SET
<#list serverUpdateFields as field>
            `${field.column}` = #{server.${field.name}},
</#list>
<#list editableFields as field>
            `${field.column}` = #{entity.${field.name}}<#if field_has_next || optimisticLock>,</#if>
</#list>
<#if optimisticLock>
            `version` = `version` + 1
</#if>
</#if>
        WHERE `id` = #{id} <include refid="Visible"/>
<#if optimisticLock>
            AND `version` = #{<#if partialUpdate>request.expectedVersion<#else>entity.version</#if>}
</#if>
    </update>

</#if>
<#if update || deleteVersionCheck>
    <select id="existsById" resultType="long">
        SELECT COUNT(*) FROM `${table}` WHERE `id` = #{id} <include refid="Visible"/>
    </select>

</#if>
<#if rowLock && (update || delete)>
    <!-- 根据主键及可信范围锁定当前记录；权限检查与修改处于同一事务。 -->
    <select id="findByIdForUpdate" resultMap="EntityMap">
        SELECT <include refid="Columns"/> FROM `${table}` WHERE `id` = #{id} <include refid="Visible"/> FOR UPDATE
    </select>
</#if>
<#if transactionalAudit || (recordAccessCheck && create)>
    <select id="findByIdForAudit" resultMap="EntityMap">
        SELECT <include refid="Columns"/> FROM `${table}` WHERE `id` = #{id}
<#if scoped>AND `${scopeColumn}` = #{scope}
</#if>
    </select>
</#if>
<#if detail>
    <select id="findById" resultMap="EntityMap">
        SELECT <include refid="Columns"/>
        FROM `${table}`
        WHERE `id` = #{id} <include refid="Visible"/>
    </select>

</#if>
<#if page>
    <select id="page" resultMap="EntityMap">
        SELECT <include refid="Columns"/>
        FROM `${table}`
        <include refid="Where"/>
        ORDER BY `id` DESC
        LIMIT #{query.size} OFFSET #{query.offset}
    </select>

    <select id="count" resultType="long">
        SELECT COUNT(*)
        FROM `${table}`
        <include refid="Where"/>
    </select>

</#if>
<#if delete>
<#if softDelete>
    <!-- 逻辑删除；唯一键是否允许再次使用由业务明确配置。 -->
    <update id="delete">
        UPDATE `${table}` SET `deleted` = TRUE<#if optimisticLock>, `version` = `version` + 1</#if>
        WHERE `id` = #{id} <include refid="Visible"/><#if deleteVersionCheck> AND `version` = #{expectedVersion}</#if>
    </update>
<#else>
    <!-- 物理删除，不自动处理其他表的关联记录。 -->
    <delete id="delete">
        DELETE FROM `${table}` WHERE `id` = #{id} <include refid="Visible"/><#if deleteVersionCheck> AND `version` = #{expectedVersion}</#if>
    </delete>
</#if>
</#if>
</mapper>
