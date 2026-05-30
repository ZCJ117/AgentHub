package vip.mate.common.result;

import lombok.Data;

import java.io.Serializable;
import java.util.function.Function;

/**
 * 统一响应结果封装
 *
 * @author MateClaw Team
 */
@Data
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态码 */
    private int code;

    /** 提示信息 */
    private String msg;

    /** 数据 */
    private T data;

    /** i18n holder — set once at startup by I18nAutoConfig, used by ok()/fail() */
    private static volatile Function<String, String> i18n;

    public static void setI18n(Function<String, String> resolver) { i18n = resolver; }

    private static String resolveMsg(ResultCode rc) {
        return i18n != null ? rc.getMsg(i18n) : rc.getMsg();
    }

    public static <T> R<T> ok() {
        return result(ResultCode.SUCCESS.getCode(), resolveMsg(ResultCode.SUCCESS), null);
    }

    public static <T> R<T> ok(T data) {
        return result(ResultCode.SUCCESS.getCode(), resolveMsg(ResultCode.SUCCESS), data);
    }

    public static <T> R<T> ok(String msg, T data) {
        return result(ResultCode.SUCCESS.getCode(), msg, data);
    }

    public static <T> R<T> fail() {
        return result(ResultCode.SYSTEM_ERROR.getCode(), resolveMsg(ResultCode.SYSTEM_ERROR), null);
    }

    public static <T> R<T> fail(String msg) {
        return result(ResultCode.SYSTEM_ERROR.getCode(), msg, null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return result(code, msg, null);
    }

    private static <T> R<T> result(int code, String msg, T data) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMsg(msg);
        r.setData(data);
        return r;
    }
}
