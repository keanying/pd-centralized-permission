package com.pd.pds.common;

/**
 * 错误码段：10xxx 参数，20xxx 认证，21xxx 权限，30xxx 业务，50xxx 系统。
 */
public enum ErrorCode {
    BAD_REQUEST(10000, "请求参数错误"),
    VALIDATION_FAILED(10001, "参数校验失败"),
    UNAUTHENTICATED(20000, "未登录或登录已过期"),
    TOKEN_REVOKED(20001, "登录凭证已失效，请重新登录"),
    FORBIDDEN(21000, "无访问权限"),
    PERMISSION_DENIED(21001, "无此功能权限"),
    RESOURCE_DENIED(21002, "无此资源权限"),
    SCENIC_DENIED(21003, "无此景区数据权限"),
    GRANT_EXCEEDS_OWN(21004, "授予的权限超出了您自身拥有的权限"),
    SEPARATION_OF_DUTIES(21005, "违反三员分立要求"),
    NOT_FOUND(30001, "数据不存在"),
    DUPLICATE(30002, "数据已存在"),
    CONFLICT(30003, "当前状态不允许此操作"),
    INTERNAL_ERROR(50000, "系统繁忙，请稍后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
