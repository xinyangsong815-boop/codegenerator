package cn.bank.devtool;

import java.util.NoSuchElementException;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException e) {
        return this.response(400, e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> missing(NoSuchElementException e) {
        return this.response(404, "记录或文件不存在");
    }

    @ExceptionHandler(ApiController.BusyException.class)
    public ResponseEntity<?> busy() {
        return this.response(429, "分析进程繁忙，请稍后重试");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> tooLarge() {
        return this.response(413, "上传超过限制：源码/项目ZIP最大20MiB，需求、DDL、接口JSON最大5MiB；排除target、node_modules和编译产物后重试");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformed() {
        return this.response(400, "请求JSON格式或字段错误");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> other(Exception e) {
        LoggerFactory.getLogger(ApiErrors.class).error("操作失败：{}", e.getClass().getSimpleName());
        return this.response(500, "操作失败，请核对文件格式并查看日志；原始响应不会写入日志");
    }

    private ResponseEntity<?> response(int status, String message) {
        return ResponseEntity.status(status).body(Util.mapOf("code", "ERROR", "message", message == null ? "操作失败" : message));
    }
}
