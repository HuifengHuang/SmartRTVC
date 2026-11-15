package com.smartrtvc.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

// 将控制器异常统一转换为 API 错误响应，不向客户端暴露内部异常详情。
@RestControllerAdvice
public class Errors {
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Api<Void>> known(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode()).body(new Api<>(null, e.getReason()));
  }

  @ExceptionHandler({
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<Api<Void>> invalid(Exception e) {
    return ResponseEntity.badRequest().body(new Api<>(null, "请求参数无效"));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<Api<Void>> conflict(Exception e) {
    return ResponseEntity.status(409).body(new Api<>(null, "用户名已存在或用户正在通话"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Api<Void>> other(Exception e) {
    return ResponseEntity.status(503).body(new Api<>(null, "服务暂不可用，请检查后端依赖服务"));
  }

  public static ResponseStatusException fail(int code, String message) {
    return new ResponseStatusException(HttpStatusCode.valueOf(code), message);
  }
}
