package ${package}.common;

import ${validationPackage}.ConstraintViolationException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

/** 统一错误协议，不将 SQL、连接信息、原始请求或异常堆栈返回给调用方。 */
@RestControllerAdvice
public class ApiErrors {
    @org.springframework.beans.factory.annotation.Value("${r'${app.log-exception-details:false}'}")
    private boolean logExceptionDetails;

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrors.class);

<#if accessGuard>
    @ExceptionHandler(${entity}AccessException.class)
    public ResponseEntity<Result<Void>> access(${entity}AccessException e){return ResponseEntity.status(e.getStatus()).body(Result.error(e.getStatus(),e.getStatus()==401?"身份认证失败":"没有操作或数据权限"));}
</#if>
<#if page>
    @ExceptionHandler(PaginationLimitException.class)
    public ResponseEntity<Result<Void>> pagination(PaginationLimitException exception) {
        return ResponseEntity.badRequest().body(Result.error(400, exception.getMessage()));
    }
</#if>
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> business(BusinessException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(Result.error(exception.getStatus(), exception.getMessage()));
    }

    @ExceptionHandler({BindException.class, ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Result<Void>> invalidParameters(Exception exception) {
        java.util.List<ValidationIssue> issues = new java.util.ArrayList<ValidationIssue>();
        if (exception instanceof BindException) {
            org.springframework.validation.BindingResult binding = ((BindException) exception).getBindingResult();
            for (org.springframework.validation.FieldError error : binding.getFieldErrors()) {
                if (issues.size() == 20) { break; }
                issues.add(new ValidationIssue(error.getField(), ValidationIssue.safeCode(error.getDefaultMessage(), error.getCode())));
            }
            for (org.springframework.validation.ObjectError error : binding.getGlobalErrors()) {
                if (issues.size() == 20) { break; }
                issues.add(new ValidationIssue("$", ValidationIssue.safeCode(error.getDefaultMessage(), error.getCode())));
            }
        } else if (exception instanceof ConstraintViolationException) {
            for (${validationPackage}.ConstraintViolation<?> error : ((ConstraintViolationException) exception).getConstraintViolations()) {
                if (issues.size() == 20) { break; }
                StringBuilder path = new StringBuilder();
                for (${validationPackage}.Path.Node node : error.getPropertyPath()) {
                    if (node.isInIterable() && node.getIndex() != null) { path.append("[").append(node.getIndex()).append("]"); }
                    if (node.getKind() == ${validationPackage}.ElementKind.PROPERTY) {
                        if (path.length() > 0) { path.append('.'); } path.append(node.getName());
                    } else if (node.getKind() == ${validationPackage}.ElementKind.PARAMETER && ("id".equals(node.getName()) || "expectedVersion".equals(node.getName()))) { path.append(node.getName()); }
                }
                issues.add(new ValidationIssue(path.toString(), ValidationIssue.safeCode(error.getMessageTemplate(), error.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName())));
            }
        } else if (exception instanceof MethodArgumentTypeMismatchException) {
            issues.add(new ValidationIssue(((MethodArgumentTypeMismatchException) exception).getName(), "typeMismatch"));
        } else if (exception instanceof MissingServletRequestParameterException) {
            issues.add(new ValidationIssue(((MissingServletRequestParameterException) exception).getParameterName(), "required"));
        }
        return ResponseEntity.badRequest().body(Result.error(400, "参数校验失败", issues));
    }
<#if update>
    @ExceptionHandler(RequestMismatchException.class)
    public ResponseEntity<Result<Void>> mismatchedIdentity(RequestMismatchException exception) {
        return ResponseEntity.badRequest().body(Result.error(400, "参数校验失败", java.util.Collections.singletonList(new ValidationIssue("id", "ID_MISMATCH"))));
    }
</#if>

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> malformedBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(Result.error(400, "JSON 格式、字段名称或字段类型错误"));
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<Result<Void>> staleVersion(Exception exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Result.error(409, "记录版本已变化，请刷新后重新提交"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> dataConflict(DataIntegrityViolationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.error(409, "数据违反完整性约束，请检查唯一性或关联关系"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> unsupportedMethod(HttpRequestMethodNotSupportedException exception) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        Set<HttpMethod> supportedMethods = exception.getSupportedHttpMethods();
        if (supportedMethods != null && !supportedMethods.isEmpty()) {
            response.allow(supportedMethods.toArray(new HttpMethod[0]));
        }
        return response.body(Result.error(405, "请求方法不支持"));
    }

    /** A bodyless 406 also works when the caller cannot accept JSON. Mapping rejects it before business execution. */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Void> unacceptableMedia(Exception exception) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).header("Vary", "Accept").build();
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> unsupportedMedia(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Result.error(415, "请求媒体类型不支持，请使用 application/json"));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<Void>> missingRoute(NoHandlerFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(404, "接口不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> unexpected(Exception exception) {
        String errorId = RequestTraceFilter.currentId();
        // 只记录关联编号和异常类型，不记录可能含凭据或业务数据的异常消息。
        if(logExceptionDetails) LOGGER.error("请求处理失败，错误编号="+errorId, exception);
        else LOGGER.error("请求处理失败，错误编号={}，异常类型={}", errorId, exception.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "服务器内部错误，错误编号：" + errorId));
    }
}
