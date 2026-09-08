package com.klcxkj.jni;

/**
 * 复用原 App 的 libklcxkjencry.so（静态 JNI 注册）。
 * 仅依赖系统库(liblog/libm/libdl/libc)，不依赖网易易盾，可直接在新 App 加载。
 */
public class JniUtils {
    static {
        System.loadLibrary("klcxkjencry");
    }

    /** 请求签名（第二套，native）：signParams(loginCode, sortedKeyValues) */
    public static native String signParams(String sessionKey, String raw);

    /** 密钥常量（MD5 签名用 getSk，其余为加密相关密钥素材） */
    public static native String getSk();
    public static native String getBk();
    public static native String getCk();
    public static native String getDk();
    public static native String getMk();

    /** AES（设备域 clData 加解密） */
    public static native String encryptByAES(String s);
    public static native String decryptByAES(String s);
}
