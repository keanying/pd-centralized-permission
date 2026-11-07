package com.pd.pds.common;

/**
 * 统一响应：{"code":0,"message":"成功","data":{...},"traceId":"..."}。
 */
public record R<T>(int code, String message, T data, String traceId) {

    public static <T> R<T> ok(T data) {
        return new R<>(0, "成功", data, null);
    }

    public static R<Void> ok() {
        return new R<>(0, "成功", null, null);
    }

    public static <T> R<T> fail(ErrorCode error, String message) {
        return new R<>(error.code(), message == null ? error.message() : message, null, null);
    }

    public R<T> withTraceId(String traceId) {
        return new R<>(code, message, data, traceId);
    }
}
