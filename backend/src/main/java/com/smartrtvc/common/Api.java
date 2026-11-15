package com.smartrtvc.common;

public record Api<T>(T data, String error) {
  public static <T> Api<T> ok(T data) {
    return new Api<>(data, null);
  }
}
