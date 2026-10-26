package ${package}.common;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
/** 更新入口两份 ID 不一致；宿主异常协议需保留 HTTP400 和 id 字段信息。 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public final class RequestMismatchException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    public RequestMismatchException() { super("更新参数 ID 与请求体 ID 不一致"); }
}
