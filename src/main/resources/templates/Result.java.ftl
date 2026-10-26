package ${package}.common;

/** 示例响应协议：code=0 表示成功；错误 code 与 HTTP 状态对应。合入时对齐行内协议。 */
public class Result<T> {

    private final int code;
    private final String message;
    private final T data;
    private java.util.List<ValidationIssue> errors = java.util.Collections.emptyList();
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public java.util.List<ValidationIssue> getErrors(){return errors;}
    /** 构造字段校验失败响应；errors 会复制为只读列表，调用方只可传公开字段与固定错误码，不能传拒绝值或原始异常消息。 */
    public static Result<Void> error(int code,String message,java.util.List<ValidationIssue> errors){Result<Void> result=error(code,message);result.errors=java.util.Collections.unmodifiableList(new java.util.ArrayList<ValidationIssue>(errors));return result;}

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    /** 包装业务成功结果，code固定为0；data 为允许对外展示的数据，null 表示无业务正文。HTTP状态由控制器决定。 */
    public static <T> Result<T> success(T data) {
        return new Result<T>(0, "成功", data);
    }

    /** 构造无业务数据的错误协议；code 应与 HTTP 状态一致，message 必须是可公开提示，不能包含SQL、凭据或堆栈。 */
    public static Result<Void> error(int code, String message) {
        return new Result<Void>(code, message, null);
    }
}
