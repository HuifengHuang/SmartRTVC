package com.smartrtvc.auth;

import static com.smartrtvc.common.Errors.fail;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartrtvc.common.Api;
import com.smartrtvc.signaling.Signaling;
import com.smartrtvc.user.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
  private final UserMapper users;
  private final AuthService auth;
  private final BCryptPasswordEncoder passwords;
  private final Signaling signaling;

  public AuthController(UserMapper u, AuthService a, BCryptPasswordEncoder p, Signaling s) {
    users = u;
    auth = a;
    passwords = p;
    signaling = s;
  }

  public record Credentials(
      @Pattern(regexp = "[a-zA-Z0-9_]{3,32}") @NotNull String username,
      @NotNull @Size(min = 8, max = 64) String password) {}

  @PostMapping("/auth/register")
  public Api<?> register(@Valid @RequestBody Credentials c) {
    User u = new User();
    u.username = c.username();
    u.passwordHash = passwords.encode(c.password());
    users.insert(u);
    return Api.ok(u);
  }

  @PostMapping("/auth/login")
  public Api<?> login(@Valid @RequestBody Credentials c) {
    User u = users.selectOne(new QueryWrapper<User>().eq("username", c.username()));
    if (u == null || !passwords.matches(c.password(), u.passwordHash)) throw fail(401, "用户名或密码错误");
    return Api.ok(Map.of("token", auth.issue(u.id), "user", u));
  }

  @PostMapping("/auth/logout")
  public Api<?> logout(Authentication a, @RequestHeader("Authorization") String h) {
    auth.revoke(h.substring(7));
    signaling.close((Long) a.getPrincipal());
    return Api.ok(true);
  }

  @PostMapping("/ws/ticket")
  public Api<?> ticket(Authentication a) {
    return Api.ok(Map.of("ticket", auth.ticket((Long) a.getPrincipal())));
  }

  @GetMapping("/users/me")
  public Api<?> me(Authentication a) {
    return Api.ok(users.selectById((Long) a.getPrincipal()));
  }

  @GetMapping("/users/{id}")
  public Api<?> user(@PathVariable long id) {
    User u = users.selectById(id);
    if (u == null) throw fail(404, "用户不存在");
    return Api.ok(u);
  }

  @GetMapping("/users")
  public Api<?> list() {
    return Api.ok(
        users.selectList(new QueryWrapper<User>().orderByAsc("id").last("LIMIT 200")).stream()
            .map(u -> Map.of("id", u.id, "username", u.username, "online", signaling.online(u.id)))
            .toList());
  }
}
