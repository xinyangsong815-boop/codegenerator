package cn.bank.devtool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Spec {
    private Integer schemaVersion;
    private Spec.Options options;
    private List<Spec.Index> indexes;
    private String packageName;
    private String artifactId;
    private String entityName;
    private String tableName;
    private String description;
    private String basePath;
    private List<String> operations;
    private List<Spec.Field> fields;
    private Spec.Integration integration;
    private String apiProfile;
    private String updateMode;
    private Map<String, Spec.Endpoint> endpoints;

    public Integer getSchemaVersion() {
        return this.schemaVersion;
    }

    public void setSchemaVersion(Integer value) {
        this.schemaVersion = value;
    }

    public Spec.Options getOptions() {
        return this.options;
    }

    public void setOptions(Spec.Options value) {
        this.options = value;
    }

    public List<Spec.Index> getIndexes() {
        return this.indexes;
    }

    public void setIndexes(List<Spec.Index> value) {
        this.indexes = value;
    }

    public Spec.Integration getIntegration() {
        return this.integration;
    }

    public void setIntegration(Spec.Integration value) {
        this.integration = value;
    }

    public String getApiProfile() {
        return this.apiProfile;
    }

    public void setApiProfile(String value) {
        this.apiProfile = value;
    }

    public String getUpdateMode() {
        return this.updateMode;
    }

    public void setUpdateMode(String value) {
        this.updateMode = value;
    }

    public Map<String, Spec.Endpoint> getEndpoints() {
        return this.endpoints;
    }

    public void setEndpoints(Map<String, Spec.Endpoint> value) {
        this.endpoints = value;
    }

    public Spec() {
    }

    public Spec(
        String packageName,
        String artifactId,
        String entityName,
        String tableName,
        String description,
        String basePath,
        List<String> operations,
        List<Spec.Field> fields
    ) {
        this.packageName = packageName;
        this.artifactId = artifactId;
        this.entityName = entityName;
        this.tableName = tableName;
        this.description = description;
        this.basePath = basePath;
        this.operations = operations;
        this.fields = fields;
    }

    public String packageName() {
        return this.packageName;
    }

    public String artifactId() {
        return this.artifactId;
    }

    public String entityName() {
        return this.entityName;
    }

    public String tableName() {
        return this.tableName;
    }

    public String description() {
        return this.description;
    }

    public String basePath() {
        return this.basePath;
    }

    public List<String> operations() {
        return this.operations;
    }

    public List<Spec.Field> fields() {
        return this.fields;
    }

    public String getPackageName() {
        return this.packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getArtifactId() {
        return this.artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getEntityName() {
        return this.entityName;
    }

    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    public String getTableName() {
        return this.tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getDescription() {
        return this.description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getBasePath() {
        return this.basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public List<String> getOperations() {
        return this.operations;
    }

    public void setOperations(List<String> operations) {
        this.operations = operations;
    }

    public List<Spec.Field> getFields() {
        return this.fields;
    }

    public void setFields(List<Spec.Field> fields) {
        this.fields = fields;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        } else {
            if (!(o instanceof Spec)) return false;
                Spec other = (Spec) o;
                return Objects.equals(this.packageName, other.packageName)
                    && Objects.equals(this.artifactId, other.artifactId)
                    && Objects.equals(this.entityName, other.entityName)
                    && Objects.equals(this.tableName, other.tableName)
                    && Objects.equals(this.description, other.description)
                    && Objects.equals(this.basePath, other.basePath)
                    && Objects.equals(this.operations, other.operations)
                    && Objects.equals(this.fields, other.fields)
                    && Objects.equals(this.apiProfile, other.apiProfile)
                    && Objects.equals(this.updateMode, other.updateMode)
                    && Util.pretty(this.endpoints).equals(Util.pretty(other.endpoints))
                    && Util.pretty(this.integration).equals(Util.pretty(other.integration))
                    && Objects.equals(this.schemaVersion, other.schemaVersion)
                    && Util.pretty(this.options).equals(Util.pretty(other.options))
                    && Util.pretty(this.indexes).equals(Util.pretty(other.indexes));
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            this.packageName,
            this.artifactId,
            this.entityName,
            this.tableName,
            this.description,
            this.basePath,
            this.operations,
            this.fields,
            this.apiProfile,
            this.updateMode,
            Util.pretty(this.endpoints),
            Util.pretty(this.integration),
            this.schemaVersion,
            Util.pretty(this.options),
            Util.pretty(this.indexes)
        );
    }

    @Override
    public String toString() {
        return "Spec{" + this.packageName + "," + this.artifactId + "," + this.entityName + "," + this.tableName + "}";
    }

    public static class DatabaseProfile {
        private String version = "8.4";
        private String charset = "utf8mb4";
        private String collation = "utf8mb4_0900_ai_ci";
        private String rowFormat = "DYNAMIC";
        private int pageSize = 16384;

        public String getVersion() {
            return this.version;
        }

        public void setVersion(String v) {
            this.version = v;
        }

        public String getCharset() {
            return this.charset;
        }

        public void setCharset(String v) {
            this.charset = v;
        }

        public String getCollation() {
            return this.collation;
        }

        public void setCollation(String v) {
            this.collation = v;
        }

        public String getRowFormat() {
            return this.rowFormat;
        }

        public void setRowFormat(String v) {
            this.rowFormat = v;
        }

        public int getPageSize() {
            return this.pageSize;
        }

        public void setPageSize(int v) {
            this.pageSize = v;
        }
    }

    public static class Endpoint {
        private String method;
        private String path;
        private String requestIn;
        private String idIn;
        private String bodyProperty;
        private List<String> fields;
        private List<String> requiredFields;

        public String getMethod() {
            return this.method;
        }

        public void setMethod(String value) {
            this.method = value;
        }

        public String getPath() {
            return this.path;
        }

        public void setPath(String value) {
            this.path = value;
        }

        public String getRequestIn() {
            return this.requestIn;
        }

        public void setRequestIn(String value) {
            this.requestIn = value;
        }

        public String getIdIn() {
            return this.idIn;
        }

        public void setIdIn(String value) {
            this.idIn = value;
        }

        public String getBodyProperty() {
            return this.bodyProperty;
        }

        public void setBodyProperty(String value) {
            this.bodyProperty = value;
        }

        public List<String> getFields() {
            return this.fields;
        }

        public void setFields(List<String> value) {
            this.fields = value;
        }

        public List<String> getRequiredFields() {
            return this.requiredFields;
        }

        public void setRequiredFields(List<String> value) {
            this.requiredFields = value;
        }
    }

    public static class Field {
        private String source;
        private JsonNode defaultValue;
        private Boolean nullable;
        private Integer temporalPrecision;
        private boolean serverUpdate;
        private String name;
        private String column;
        private String javaType;
        private String description;
        private boolean required;
        private boolean editable;
        private boolean exposed;
        private String query;
        private Integer maxLength;
        private Integer precision;
        private Integer scale;

        public boolean isServerUpdate() {
            return this.serverUpdate;
        }

        public void setServerUpdate(boolean value) {
            this.serverUpdate = value;
        }

        public Integer getTemporalPrecision() {
            return this.temporalPrecision;
        }

        public void setTemporalPrecision(Integer value) {
            this.temporalPrecision = value;
        }

        public String getSource() {
            return this.source;
        }

        public void setSource(String value) {
            this.source = value;
        }

        @JsonInclude(Include.NON_NULL)
        public JsonNode getDefaultValue() {
            return this.defaultValue;
        }

        public void setDefaultValue(JsonNode value) {
            this.defaultValue = value;
        }

        public Boolean getNullable() {
            return this.nullable;
        }

        public void setNullable(Boolean value) {
            this.nullable = value;
        }

        public Field() {
        }

        public Field(
            String name,
            String column,
            String javaType,
            String description,
            boolean required,
            boolean editable,
            boolean exposed,
            String query,
            Integer maxLength,
            Integer precision,
            Integer scale
        ) {
            this.name = name;
            this.column = column;
            this.javaType = javaType;
            this.description = description;
            this.required = required;
            this.editable = editable;
            this.exposed = exposed;
            this.query = query;
            this.maxLength = maxLength;
            this.precision = precision;
            this.scale = scale;
        }

        public String name() {
            return this.name;
        }

        public String column() {
            return this.column;
        }

        public String javaType() {
            return this.javaType;
        }

        public String description() {
            return this.description;
        }

        public boolean required() {
            return this.nullable == null ? this.required : !this.nullable;
        }

        public boolean editable() {
            return this.editable;
        }

        public boolean exposed() {
            return this.exposed;
        }

        public String query() {
            return this.query;
        }

        public Integer maxLength() {
            return this.maxLength;
        }

        public Integer precision() {
            return this.precision;
        }

        public Integer scale() {
            return this.scale;
        }

        public String getName() {
            return this.name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getColumn() {
            return this.column;
        }

        public void setColumn(String column) {
            this.column = column;
        }

        public String getJavaType() {
            return this.javaType;
        }

        public void setJavaType(String javaType) {
            this.javaType = javaType;
        }

        public String getDescription() {
            return this.description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public boolean isRequired() {
            return this.required();
        }

        public void setRequired(boolean required) {
            this.required = required;
        }

        public boolean isEditable() {
            return this.editable;
        }

        public void setEditable(boolean editable) {
            this.editable = editable;
        }

        public boolean isExposed() {
            return this.exposed;
        }

        public void setExposed(boolean exposed) {
            this.exposed = exposed;
        }

        public String getQuery() {
            return this.query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public Integer getMaxLength() {
            return this.maxLength;
        }

        public void setMaxLength(Integer maxLength) {
            this.maxLength = maxLength;
        }

        public Integer getPrecision() {
            return this.precision;
        }

        public void setPrecision(Integer precision) {
            this.precision = precision;
        }

        public Integer getScale() {
            return this.scale;
        }

        public void setScale(Integer scale) {
            this.scale = scale;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            } else {
                if (!(o instanceof Spec.Field)) return false;
                Spec.Field other = (Spec.Field) o;
                return this.required() == other.required()
                        && this.editable == other.editable
                        && this.exposed == other.exposed
                        && Objects.equals(this.name, other.name)
                        && Objects.equals(this.column, other.column)
                        && Objects.equals(this.javaType, other.javaType)
                        && Objects.equals(this.description, other.description)
                        && Objects.equals(this.query, other.query)
                        && Objects.equals(this.maxLength, other.maxLength)
                        && Objects.equals(this.precision, other.precision)
                        && Objects.equals(this.scale, other.scale)
                        && Objects.equals(this.source, other.source)
                        && Objects.equals(this.defaultValue, other.defaultValue)
                        && Objects.equals(this.nullable, other.nullable)
                        && Objects.equals(this.temporalPrecision, other.temporalPrecision)
                        && this.serverUpdate == other.serverUpdate;
            }
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                this.name,
                this.column,
                this.javaType,
                this.description,
                this.required(),
                this.editable,
                this.exposed,
                this.query,
                this.maxLength,
                this.precision,
                this.scale,
                this.source,
                this.defaultValue,
                this.nullable,
                this.temporalPrecision,
                this.serverUpdate
            );
        }

        @Override
        public String toString() {
            return "Field{" + this.name + "," + this.column + "," + this.javaType + "}";
        }
    }

    public static class Index {
        private String name;
        private boolean unique;
        private List<String> fields;

        public String getName() {
            return this.name;
        }

        public void setName(String value) {
            this.name = value;
        }

        public boolean isUnique() {
            return this.unique;
        }

        public void setUnique(boolean value) {
            this.unique = value;
        }

        public List<String> getFields() {
            return this.fields;
        }

        public void setFields(List<String> value) {
            this.fields = value;
        }
    }

    public static class Integration {
        private String mode;
        private String resultClass;
        private String successMethod;
        private String dataProperty;
        private String codeProperty;
        private String codeType;
        private String successCode;
        private String messageProperty;
        private String pageClass;
        private String pageFactoryMethod;
        private String recordsProperty;
        private String totalProperty;
        private String exceptionClass;
        private String notFoundMethod;

        public String getMode() {
            return this.mode;
        }

        public void setMode(String value) {
            this.mode = value;
        }

        public String getResultClass() {
            return this.resultClass;
        }

        public void setResultClass(String value) {
            this.resultClass = value;
        }

        public String getSuccessMethod() {
            return this.successMethod;
        }

        public void setSuccessMethod(String value) {
            this.successMethod = value;
        }

        public String getDataProperty() {
            return this.dataProperty;
        }

        public void setDataProperty(String value) {
            this.dataProperty = value;
        }

        public String getCodeProperty() {
            return this.codeProperty;
        }

        public void setCodeProperty(String value) {
            this.codeProperty = value;
        }

        public String getCodeType() {
            return this.codeType;
        }

        public void setCodeType(String value) {
            this.codeType = value;
        }

        public String getSuccessCode() {
            return this.successCode;
        }

        public void setSuccessCode(String value) {
            this.successCode = value;
        }

        public String getMessageProperty() {
            return this.messageProperty;
        }

        public void setMessageProperty(String value) {
            this.messageProperty = value;
        }

        public String getPageClass() {
            return this.pageClass;
        }

        public void setPageClass(String value) {
            this.pageClass = value;
        }

        public String getPageFactoryMethod() {
            return this.pageFactoryMethod;
        }

        public void setPageFactoryMethod(String value) {
            this.pageFactoryMethod = value;
        }

        public String getRecordsProperty() {
            return this.recordsProperty;
        }

        public void setRecordsProperty(String value) {
            this.recordsProperty = value;
        }

        public String getTotalProperty() {
            return this.totalProperty;
        }

        public void setTotalProperty(String value) {
            this.totalProperty = value;
        }

        public String getExceptionClass() {
            return this.exceptionClass;
        }

        public void setExceptionClass(String value) {
            this.exceptionClass = value;
        }

        public String getNotFoundMethod() {
            return this.notFoundMethod;
        }

        public void setNotFoundMethod(String value) {
            this.notFoundMethod = value;
        }
    }

    public static class Options {
        private String wireFormat;
        private String jsonMode;
        private String jsonScope;
        private Spec.DatabaseProfile database;
        private boolean recordAccessCheck;
        private boolean transactionalAudit;
        private List<String> auditFields;
        private boolean accessGuard;
        private boolean deleteVersionCheck;
        private boolean optimisticLock;
        private boolean softDelete;
        private boolean hooks;
        private String scopeField;

        public String getJsonScope() {
            return this.jsonScope;
        }

        public void setJsonScope(String value) {
            this.jsonScope = value;
        }

        public Spec.DatabaseProfile getDatabase() {
            return this.database;
        }

        public void setDatabase(Spec.DatabaseProfile value) {
            this.database = value;
        }

        public boolean isRecordAccessCheck() {
            return this.recordAccessCheck;
        }

        public void setRecordAccessCheck(boolean value) {
            this.recordAccessCheck = value;
        }

        public boolean isTransactionalAudit() {
            return this.transactionalAudit;
        }

        public void setTransactionalAudit(boolean value) {
            this.transactionalAudit = value;
        }

        public List<String> getAuditFields() {
            return this.auditFields;
        }

        public void setAuditFields(List<String> value) {
            this.auditFields = value;
        }

        public boolean isAccessGuard() {
            return this.accessGuard;
        }

        public void setAccessGuard(boolean value) {
            this.accessGuard = value;
        }

        public boolean isDeleteVersionCheck() {
            return this.deleteVersionCheck;
        }

        public void setDeleteVersionCheck(boolean value) {
            this.deleteVersionCheck = value;
        }

        public String getWireFormat() {
            return this.wireFormat;
        }

        public void setWireFormat(String value) {
            this.wireFormat = value;
        }

        public String getJsonMode() {
            return this.jsonMode;
        }

        public void setJsonMode(String value) {
            this.jsonMode = value;
        }

        public boolean isOptimisticLock() {
            return this.optimisticLock;
        }

        public void setOptimisticLock(boolean value) {
            this.optimisticLock = value;
        }

        public boolean isSoftDelete() {
            return this.softDelete;
        }

        public void setSoftDelete(boolean value) {
            this.softDelete = value;
        }

        public boolean isHooks() {
            return this.hooks;
        }

        public void setHooks(boolean value) {
            this.hooks = value;
        }

        public String getScopeField() {
            return this.scopeField;
        }

        public void setScopeField(String value) {
            this.scopeField = value;
        }
    }
}
