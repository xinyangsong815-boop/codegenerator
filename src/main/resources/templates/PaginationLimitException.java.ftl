package ${package}.common;

/** 分页偏移超过容量配置时由服务抛出；独立工程映射HTTP400，宿主接入时应保留此状态，避免把容量拒绝转为500。 */
@org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
public final class PaginationLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public PaginationLimitException(String message) { super(message); }
}
