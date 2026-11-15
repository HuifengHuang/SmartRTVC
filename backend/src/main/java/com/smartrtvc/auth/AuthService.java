package com.smartrtvc.auth;

import static com.smartrtvc.common.Errors.fail;

import com.auth0.jwt.*;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private final Algorithm algorithm;
  private final StringRedisTemplate redis;

  public AuthService(@Value("${app.jwt-secret}") String secret, StringRedisTemplate redis) {
    if (secret.length() < 32)
      throw new IllegalArgumentException("JWT_SECRET must be at least 32 characters");
    algorithm = Algorithm.HMAC256(secret);
    this.redis = redis;
  }

  // subject 绑定数据库用户 ID，jti 用于注销时精确撤销当前登录凭证。
  public String issue(long id) {
    return JWT.create()
        .withIssuer("smartrtvc")
        .withSubject("" + id)
        .withJWTId(UUID.randomUUID().toString())
        .withExpiresAt(Instant.now().plusSeconds(7200))
        .sign(algorithm);
  }

  // 同时检查签名、有效期和撤销标记；验证失败不能建立认证身份。
  public DecodedJWT verify(String token) {
    try {
      var jwt =
          JWT.require(algorithm)
              .withIssuer("smartrtvc")
              .withClaimPresence("exp")
              .withClaimPresence("jti")
              .build()
              .verify(token);
      if (Boolean.TRUE.equals(redis.hasKey("revoked:" + jwt.getId()))) throw fail(401, "登录已失效");
      Long.parseLong(jwt.getSubject());
      return jwt;
    } catch (Exception e) {
      throw fail(401, "请重新登录");
    }
  }

  // 撤销记录只保留到 JWT 自然过期，避免 Redis 无限制积累。
  public void revoke(String token) {
    var jwt = verify(token);
    redis
        .opsForValue()
        .set(
            "revoked:" + jwt.getId(),
            "1",
            Duration.between(Instant.now(), jwt.getExpiresAtAsInstant()));
  }

  // 短期 Ticket 将 HTTP 认证身份传递给浏览器 WebSocket 握手。
  public String ticket(long id) {
    String t = UUID.randomUUID().toString();
    redis.opsForValue().set("ticket:" + t, "" + id, Duration.ofSeconds(30));
    return t;
  }

  // 原子读取并删除，防止两个握手同时复用同一张 Ticket。
  public Long consume(String t) {
    if (t == null) return null;
    String id = redis.opsForValue().getAndDelete("ticket:" + t);
    return id == null ? null : Long.valueOf(id);
  }
}
