package com.smartrtvc.call;

import com.smartrtvc.common.Api;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// HTTP 层负责参数与身份提取；状态变更和成员权限统一委托 CallService。
@RestController
@RequestMapping("/api")
public class CallController {
  private final CallService service;

  public CallController(CallService s) {
    service = s;
  }

  public record Invite(@NotNull @Positive Long calleeId) {}

  private long id(Authentication a) {
    return (Long) a.getPrincipal();
  }

  @PostMapping("/calls")
  public Api<?> invite(Authentication a, @Valid @RequestBody Invite body) {
    return Api.ok(service.invite(id(a), body.calleeId()));
  }

  @GetMapping("/calls/current")
  public Api<?> current(Authentication a) {
    return Api.ok(service.current(id(a)));
  }

  @GetMapping("/calls/{call}")
  public Api<?> get(Authentication a, @PathVariable String call) {
    return Api.ok(service.member(call, id(a)));
  }

  @PostMapping("/calls/{call}/accept")
  public Api<?> accept(Authentication a, @PathVariable String call) {
    return Api.ok(service.accept(call, id(a)));
  }

  @PostMapping("/calls/{call}/hangup")
  public Api<?> hangup(Authentication a, @PathVariable String call) {
    return Api.ok(service.hangup(call, id(a)));
  }

  @PostMapping("/calls/{call}/token")
  public Api<?> token(Authentication a, @PathVariable String call) {
    return Api.ok(service.token(call, id(a)));
  }

  @PostMapping("/livekit/webhook")
  public Api<?> webhook(
      @RequestBody String body,
      @RequestHeader(value = "Authorization", required = false) String h) {
    service.webhook(body, h);
    return Api.ok(true);
  }
}
