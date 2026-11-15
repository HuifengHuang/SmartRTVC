package com.smartrtvc.config;

import com.smartrtvc.auth.AuthService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
  @Bean
  BCryptPasswordEncoder passwords() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, AuthService auth) throws Exception {
    // API 使用显式 Bearer 凭证，不依赖 Cookie Session，因此采用无状态认证。
    // /ws 由 Ticket 握手认证，Webhook 由 LiveKit 签名认证，并非无保护入口。
    return http.csrf(c -> c.disable())
        .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            c ->
                c.requestMatchers(
                        "/api/auth/login", "/api/auth/register", "/api/livekit/webhook", "/ws")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            c ->
                c.authenticationEntryPoint(
                    (r, s, e) -> {
                      s.setStatus(401);
                      s.setContentType("application/json;charset=UTF-8");
                      s.getWriter().write("{\"data\":null,\"error\":\"请登录\"}");
                    }))
        .addFilterBefore(
            new OncePerRequestFilter() {
              protected void doFilterInternal(
                  HttpServletRequest r, HttpServletResponse s, FilterChain chain)
                  throws ServletException, IOException {
                String h = r.getHeader("Authorization");
                if (h != null && h.startsWith("Bearer ")) {
                  try {
                    var jwt = auth.verify(h.substring(7));
                    // 仅使用已验签的 subject 建立身份，Controller 从 Authentication 读取用户 ID。
                    SecurityContextHolder.getContext()
                        .setAuthentication(
                            new UsernamePasswordAuthenticationToken(
                                Long.valueOf(jwt.getSubject()), null, List.of()));
                  } catch (Exception e) {
                    s.setStatus(401);
                    s.setContentType("application/json");
                    s.getWriter().write("{\"data\":null,\"error\":\"Unauthorized\"}");
                    return;
                  }
                }
                chain.doFilter(r, s);
              }
            },
            UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
