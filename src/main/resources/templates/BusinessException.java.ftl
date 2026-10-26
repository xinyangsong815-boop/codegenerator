package ${package}.common;

/** 可以向调用方说明的业务异常；数据库和内部异常由全局处理器隐藏细节。 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    /** 普通业务规则不满足时使用 HTTP 400。 */
    public BusinessException(String message) {
        this(400, message);
    }

    private BusinessException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    /** 主键合法但对应记录不存在时使用 HTTP 404。 */
    public static BusinessException notFound(String message) {
        return new BusinessException(404, message);
    }
}
