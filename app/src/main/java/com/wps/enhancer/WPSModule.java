package com.wps.enhancer;

import android.app.Activity;
import android.app.Application;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedModule;

public final class WPSModule extends XposedModule {
    private static final String TAG = "[WPS-Miuix]";
    private static final String TARGET = "com.wps.koa";

    private static String logPath = "/data/local/tmp/wps-miuix.log";
    private static void log(String msg) {
        android.util.Log.d(TAG, msg); // logcat 备用
        try {
            java.io.FileWriter fw = new java.io.FileWriter(logPath, true);
            fw.write(android.text.format.DateFormat.format("HH:mm:ss", new Date()) + " " + msg + "\n");
            fw.close();
        } catch (Throwable t) {
            try {
                logPath = getDataDir() + "/wps-miuix.log";
                java.io.FileWriter fw = new java.io.FileWriter(logPath, true);
                fw.write(android.text.format.DateFormat.format("HH:mm:ss", new Date()) + " " + msg + "\n");
                fw.close();
            } catch (Throwable ignored) {}
        }
    }

    private static final String[] SU_PATHS = {
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/data/local/xbin/su", "/data/local/bin/su",
        "/data/adb/magisk", "/data/adb/ksu"
    };
    private static final String[] ROOT_PKGS = {
        "com.topjohnwu.magisk", "eu.chainfire.supersu",
        "com.koushikdutta.superuser", "me.weishu.kernelsu", "com.rifsxd.ksunext"
    };

    private int wpAccent = 0;

    // WebSocket 状态
    private static Socket cchSocket = null;
    private static OutputStream cchOut = null;
    private static volatile StringBuilder currentResponse = null;
    private static volatile CountDownLatch currentLatch = null;

    // 宠物状态
    private static volatile String petStatusText = "";
    private static volatile int petStatusMode = 0; // 0=空闲 1=思考 2=工具 3=权限 4=错误
    private static volatile boolean autoApprovePerm = false; // false=弹气泡确认, true=自动批准
    private static volatile boolean petEnabled = false; // Claude Code 宠物UI开关
    private static volatile boolean checkinUiEnabled = false; // 自动打卡UI开关（仅控制"打卡设置"按钮显示）
    private static volatile boolean monetEnabled = false; // 莫奈取色开关
    private static volatile boolean rootHideEnabled = false; // 去除Root检测开关
    private static volatile boolean watermarkEnabled = false; // 去除水印开关
    private static TextView petView = null;
    private static TextView petStatusView = null;
    private static TextView petPermView = null; // 独立权限气泡
    private static final Handler petHandler = new Handler(Looper.getMainLooper());
    private static volatile String pendingPermissionId = null;
    private static volatile String pendingPermissionText = ""; // 保存授权提示文本

    private static final String[][] PET_FRAMES = {
        {"(=^.^=)", "(=^ω^=)", "(=^._.^=)", "(=^.^=)~"},           // 空闲
        {"(=^-^?)", "(=^-^ )", "(=^o^?)", "(=^-^?)"},              // 思考
        {"(=^o^=)", "(=^O^=)", "(=^0^=)", "(=^o^=)"},              // 工具执行
        {"(=^?^=)", "(=^.^?)", "(=o.o=)", "(=^?^=)"},              // 权限请求
        {"(=T.T=)", "(=;_;=)", "(=><=)", "(=T.T=)"},               // 错误
    };

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!param.getPackageName().equals(TARGET)) return;
        log("LOADED PID=" + android.os.Process.myPid());

        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            hook(attach).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                Context ctx = (Context) chain.getArg(0);
                ClassLoader cl = ctx.getClassLoader();
                Object result = chain.proceed();
                log("ATTACH PID=" + android.os.Process.myPid());

                // 恢复配置和会话
                appContext = ctx;
                initDataPaths(); // 配置文件路径指向 WPS 私有目录
                loadConfig();
                cchSessionId = loadSessionId();
                if (cchSessionId != null) log("RESTORED_SESSION=" + cchSessionId);
                loadCheckinConfig();
                requestRoot(ctx); // 第一时间请求 root 权限
                deployCheckinWorker(); // 部署独立打卡程序
                if (checkinEnabled) scheduleCheckin(null);

                // 动态注册 CheckinReceiver（运行在 WPS 进程内，能访问 appContext）
                try {
                    android.content.IntentFilter filter = new android.content.IntentFilter("com.wps.enhancer.CHECKIN_ACTION");
                    if (Build.VERSION.SDK_INT >= 33) {
                        ctx.registerReceiver(new CheckinReceiver(), filter, Context.RECEIVER_EXPORTED);
                    } else {
                        ctx.registerReceiver(new CheckinReceiver(), filter);
                    }
                    log("CHECKIN_RECEIVER registered dynamically");
                } catch (Throwable t) { log("CHECKIN_RECEIVER_REG=" + t.getMessage()); }

                // 提前加载壁纸颜色，确保 hook 生效时 wpAccent 已就绪
                loadWP(ctx, false);

                // Root
                try { hook(Runtime.class.getDeclaredMethod("exec", String.class)).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(c -> { if (!rootHideEnabled) return c.proceed(); if (su((String) c.getArg(0))) throw new java.io.IOException("denied"); return c.proceed(); }); } catch (Throwable ignored) {}
                try { hook(Runtime.class.getDeclaredMethod("exec", String[].class)).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(c -> { if (!rootHideEnabled) return c.proceed(); if (suA((String[]) c.getArg(0))) throw new java.io.IOException("denied"); return c.proceed(); }); } catch (Throwable ignored) {}
                try { hook(File.class.getDeclaredMethod("exists")).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(c -> { if (!rootHideEnabled) return c.proceed(); for (String s : SU_PATHS) if (((File) c.getThisObject()).getAbsolutePath().equals(s)) return false; return c.proceed(); }); } catch (Throwable ignored) {}
                try { hook(Class.forName("android.app.ApplicationPackageManager").getDeclaredMethod("getPackageInfo", String.class, int.class))
                    .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> { if (!rootHideEnabled) return c.proceed(); for (String r : ROOT_PKGS) if (r.equals(c.getArg(0))) throw new PackageManager.NameNotFoundException((String) c.getArg(0)); return c.proceed(); }); } catch (Throwable ignored) {}
                try { Class<?> sp = Class.forName("android.os.SystemProperties");
                    hook(sp.getDeclaredMethod("get", String.class)).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(c -> { if (!rootHideEnabled) return c.proceed(); String k = (String) c.getArg(0); if ("ro.build.tags".equals(k)) return "release-keys"; if ("ro.debuggable".equals(k)) return "0"; return c.proceed(); }); } catch (Throwable ignored) {}
                try { Class<?> rb = cl.loadClass("com.scottyab.rootbeer.RootBeer");
                    for (String m : new String[]{"isRooted","detectRootManagementApps","checkSuExists"})
                        try { hook(rb.getDeclaredMethod(m)).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> { if (!rootHideEnabled) return c.proceed(); return false; }); } catch (Throwable ignored) {} } catch (Throwable ignored) {}

                // 反检测：隐藏 Xposed/LSPosed 痕迹
                try { hook(Class.forName("java.lang.Runtime").getDeclaredMethod("exec", String[].class, String[].class, File.class))
                    .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                        if (!rootHideEnabled) return c.proceed();
                        String[] cmd = (String[]) c.getArg(0);
                        if (cmd != null) for (String s : cmd) if (s != null && (s.contains("xposed") || s.contains("lspd") || s.contains("lsposed"))) throw new java.io.IOException("denied");
                        return c.proceed();
                    }); } catch (Throwable ignored) {}
                try { hook(File.class.getDeclaredMethod("exists")).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(c -> {
                        if (!rootHideEnabled) return c.proceed();
                        String p = ((File) c.getThisObject()).getAbsolutePath();
                        if (p.contains("xposed") || p.contains("lspd") || p.contains("lsposed") || p.contains("module.prop")) return false;
                        return c.proceed();
                    }); } catch (Throwable ignored) {}
                try { hook(File.class.getDeclaredMethod("canRead")).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(c -> {
                        String p = ((File) c.getThisObject()).getAbsolutePath();
                        if (p.contains("xposed") || p.contains("lspd") || p.contains("lsposed") || p.contains("module.prop")) return false;
                        return c.proceed();
                    }); } catch (Throwable ignored) {}
                try { hook(Class.forName("java.io.FileInputStream").getDeclaredConstructor(File.class))
                    .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                        String p = ((File) c.getArg(0)).getAbsolutePath();
                        if (p.contains("xposed") || p.contains("lspd") || p.contains("lsposed") || p.contains("module.prop"))
                            throw new java.io.FileNotFoundException("denied");
                        return c.proceed();
                    }); } catch (Throwable ignored) {}
                try { hook(TextView.class.getDeclaredMethod("setText", CharSequence.class, android.widget.TextView.BufferType.class, boolean.class, int.class))
                    .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                        CharSequence text = (CharSequence) c.getArg(0);
                        if (text != null && (text.toString().contains("Xposed") || text.toString().contains("LSPosed") || text.toString().contains("Vector")))
                            return c.proceed(new Object[]{"", c.getArg(1), c.getArg(2), c.getArg(3)});
                        return c.proceed();
                    }); } catch (Throwable ignored) {}

                // 去除开屏：LauncherActivity 透明 + 直接跳转
                try {
                    Class<?> launcher = cl.loadClass("com.wps.woa.module.launcher.ui.LauncherActivity");
                    hook(launcher.getDeclaredMethod("onCreate", Bundle.class)).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                        Activity la = (Activity) c.getThisObject();
                        // 透明主题，视觉上去掉开屏
                        try { la.setTheme(android.R.style.Theme_Translucent); } catch (Throwable ignored) {}
                        la.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                        c.proceed();
                        // 直接跳转主界面
                        Intent intent = new Intent(la, cl.loadClass("com.wps.koa.ui.MainActivity"));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        la.startActivity(intent);
                        la.finish();
                        return null;
                    });
                } catch (Throwable t) { log("SPLASH_FAIL=" + t.getMessage()); }

                // ExternalTagView：固定白色文字（两个 setTextColor 版本都拦截）
                try {
                    hook(TextView.class.getDeclaredMethod("setTextColor", int.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                            if (((View) c.getThisObject()).getClass().getName().contains("tags."))
                                return c.proceed(new Object[]{0xFFFFFFFF});
                            return c.proceed();
                        });
                } catch (Throwable ignored) {}
                try {
                    hook(TextView.class.getDeclaredMethod("setTextColor", android.content.res.ColorStateList.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                            if (((View) c.getThisObject()).getClass().getName().contains("tags."))
                                return c.proceed(new Object[]{android.content.res.ColorStateList.valueOf(0xFFFFFFFF)});
                            return c.proceed();
                        });
                } catch (Throwable ignored) {}

                // Watermark
                try { hook(cl.loadClass("com.kingsoft.kim.kit.watermark.WatermarkController").getDeclaredMethod("init")).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> { if (!watermarkEnabled) return c.proceed(); return null; }); } catch (Throwable ignored) {}
                try { hook(cl.loadClass("com.kingsoft.kim.kit.watermark.WatermarkController").getDeclaredMethod("switchWatermark")).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> { if (!watermarkEnabled) return c.proceed(); return null; }); } catch (Throwable ignored) {}
                for (String m : new String[]{"chatIsOpen","docIsOpen","webIsOpen","searchIsOpen","personCenterIsOpen"})
                    try { hook(cl.loadClass("com.kingsoft.kim.kit.watermark.WatermarkController").getDeclaredMethod(m)).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> { if (!watermarkEnabled) return c.proceed(); return false; }); } catch (Throwable ignored) {}
                try { hook(cl.loadClass("com.wps.koa.BaseActivity").getDeclaredMethod("showWatermark")).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> { if (!watermarkEnabled) return c.proceed(); return null; }); } catch (Throwable ignored) {}
                try { hook(cl.loadClass("com.wps.koa.BaseActivity").getDeclaredMethod("isShowWatermark")).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> { if (!watermarkEnabled) return c.proceed(); return false; }); } catch (Throwable ignored) {}
                try { hook(cl.loadClass("com.wps.koa.ui.view.watermark.WatermarkDrawable").getDeclaredMethod("draw", Canvas.class)).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> { if (!watermarkEnabled) return c.proceed(); return null; }); } catch (Throwable ignored) {}

                // 自动打卡：hook WebChromeClient 自动授予定位权限
                try {
                    hook(android.webkit.WebChromeClient.class.getDeclaredMethod("onGeolocationPermissionsShowPrompt",
                        String.class, android.webkit.GeolocationPermissions.Callback.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                        if (checkinSpoofing) {
                            try {
                                android.webkit.GeolocationPermissions.Callback cb = (android.webkit.GeolocationPermissions.Callback) c.getArg(1);
                                cb.invoke((String) c.getArg(0), true, false);
                                log("GEO_PERMIT auto-granted");
                            } catch (Throwable t) { log("GEO_PERMIT=" + t.getMessage()); }
                            return null;
                        }
                        return c.proceed();
                    });
                    log("GEO_PERMIT_HOOK ok");
                } catch (Throwable t) { log("GEO_PERMIT_HOOK=" + t.getMessage()); }

                // 自动打卡：hook onJsPrompt 捕获 presetKeyValue（表单预设姓名）
                try {
                    hook(android.webkit.WebChromeClient.class.getDeclaredMethod("onJsPrompt",
                        android.webkit.WebView.class, String.class, String.class, String.class, android.webkit.JsResult.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                        try {
                            String message = (String) c.getArg(2);
                            if (message != null && message.startsWith("__WYU_INPUTNAME__:")) {
                                String name = message.substring(18);
                                if (!name.isEmpty()) {
                                    checkinInputName = name;
                                    saveCheckinConfig();
                                    log("INPUTNAME_CAPTURED: " + name);
                                }
                                ((android.webkit.JsResult) c.getArg(4)).cancel();
                                return true;
                            }
                        } catch (Throwable t) { log("JSPROMPT=" + t.getMessage()); }
                        return c.proceed();
                    });
                    log("JSPROMPT_HOOK ok");
                } catch (Throwable t) { log("JSPROMPT_HOOK=" + t.getMessage()); }

                // 自动打卡：页面开始加载时注入假定位
                try {
                    hook(android.webkit.WebViewClient.class.getDeclaredMethod("onPageStarted",
                        android.webkit.WebView.class, String.class, android.graphics.Bitmap.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                        try {
                            String url = (String) c.getArg(1);
                            if (url != null && url.contains("f.wps.cn/ksform")) {
                                // 动态捕获表单 URL，跟随"学生打卡入口"链接变化
                                saveFormUrl(url);
                                // 在页面脚本执行前激活定位覆盖（使用真实GPS时不激活）
                                if (!checkinSpoofing && !checkinUseRealGps) {
                                    checkinSpoofing = true;
                                    checkinSubmitted = false;
                                    log("AUTO_SPOOF activated on page start");
                                }
                                android.webkit.WebView wv = (android.webkit.WebView) c.getArg(0);
                                // 用 loadUrl 同步注入，确保在页面脚本前执行
                                wv.loadUrl("javascript:(function(){" +
                                    "var fakeLat=" + checkinLat + ",fakeLng=" + checkinLng + ";" +
                                    "var fakePos={coords:{latitude:fakeLat,longitude:fakeLng,accuracy:10,altitude:null,heading:null,speed:null,altitudeAccuracy:null},timestamp:Date.now()};" +
                                    "try{Object.defineProperty(navigator,'geolocation',{" +
                                    "  value:{getCurrentPosition:function(s){s(fakePos);},watchPosition:function(s){s(fakePos);return 1;},clearWatch:function(){}}," +
                                    "  writable:false,configurable:false" +
                                    "});}catch(e){}" +
                                    "})();");
                                log("GEO_OVERRIDE loadUrl " + checkinLat + "," + checkinLng);
                                log("GEO_SPOOF onStart " + checkinLat + "," + checkinLng);
                            } else if (checkinSpoofing) {
                                // 离开表单页面，重置
                                checkinSpoofing = false;
                                checkinSubmitted = false;
                                log("AUTO_SPOOF deactivated");
                            }
                        } catch (Throwable t) { log("GEO_START=" + t.getMessage()); }
                        c.proceed();
                        return null;
                    });
                    log("GEO_START_HOOK ok");
                } catch (Throwable t) { log("GEO_START_HOOK=" + t.getMessage()); }

                // 自动打卡：拦截金山表单页面，自动点击提交
                try {
                    hook(android.webkit.WebViewClient.class.getDeclaredMethod("onPageFinished",
                        android.webkit.WebView.class, String.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                        c.proceed();
                        try {
                            String url = (String) c.getArg(1);
                            if (url != null && url.contains("f.wps.cn/ksform")) {
                                if (checkinSubmitted) { log("AUTO_SUBMIT skip, already submitted"); c.proceed(); return null; }
                                final android.webkit.WebView wv = (android.webkit.WebView) c.getArg(0);
                                log("AUTO_SUBMIT url=" + url + " spoofing=" + checkinSpoofing);
                                // 动态捕获表单 URL，跟随"学生打卡入口"链接变化
                                saveFormUrl(url);
                                // 从 CookieManager 读取 cookie 用于 API 静默打卡
                                try {
                                    android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                                    // 尝试多个域名
                                    String[] domains = {
                                        "https://f-api.kdocs.cn",
                                        "https://account.kdocs.cn",
                                        "https://f.kdocs.cn",
                                        "https://account.wps.cn",
                                        "https://www.kdocs.cn"
                                    };
                                    StringBuilder allCookies = new StringBuilder();
                                    for (String d : domains) {
                                        String ck = cm.getCookie(d);
                                        if (ck != null && !ck.isEmpty()) {
                                            allCookies.append(ck).append("; ");
                                            log("COOKIE_DOMAIN " + d + " → " + ck.substring(0, Math.min(80, ck.length())));
                                        }
                                    }
                                    if (allCookies.length() > 0) {
                                        capturedCookies = allCookies.toString();
                                        int csrfIdx = capturedCookies.indexOf("csrf=");
                                        if (csrfIdx >= 0) {
                                            int start = csrfIdx + 5;
                                            int end = capturedCookies.indexOf(";", start);
                                            if (end < 0) end = capturedCookies.length();
                                            capturedCsrf = capturedCookies.substring(start, end);
                                        }
                                        log("COOKIE_CAPTURED total_len=" + capturedCookies.length() + " csrf=" + (capturedCsrf.isEmpty() ? "none" : capturedCsrf.substring(0, Math.min(8, capturedCsrf.length())) + "..."));
                                        // 保存 Cookie 备份，供独立 CheckinWorker 使用
                                        saveCheckinBackup(capturedCookies, capturedCsrf);
                                    } else {
                                        log("COOKIE_CAPTURED: no cookies found");
                                    }
                                } catch (Throwable t) { log("COOKIE_CAPTURED=" + t.getMessage()); }
                                // Cookie 刷新模式：只抓 Cookie，不自动提交
                                if (refreshingCookies) {
                                    refreshingCookies = false;
                                    checkinSubmitted = true;
                                    log("COOKIE_REFRESH done, captured len=" + capturedCookies.length());
                                    return null;
                                }
                                // 首次配置：Cookie 捕获后自动打卡并关闭页面
                                // 跨进程检查：文件标记（WPS 多进程 static 不共享，用私有目录文件）
                                boolean flagFileExists = new java.io.File(getDataDir() + "/wps-first-setup").exists();
                                if (firstSetupPending || flagFileExists) {
                                    firstSetupPending = false;
                                    // 删除标记文件
                                    try { new java.io.File(getDataDir() + "/wps-first-setup").delete(); } catch (Throwable t) {}
                                    checkinSubmitted = true;
                                    log("FIRST_SETUP: cookies captured, fetching user info from history...");
                                    // 从历史打卡记录提取姓名/部门/学号（用户已打过卡时表单页取不到）
                                    ensureCheckinUserInfoFromAnswers(capturedCookies, capturedCsrf);
                                    // 后台执行 API 打卡
                                    new Thread(() -> {
                                        doSilentCheckinAPI();
                                        // 关闭表单页面
                                        try {
                                            if (wv.getContext() instanceof Activity) {
                                                ((Activity) wv.getContext()).finish();
                                            }
                                        } catch (Throwable t) {}
                                        // Toast 提醒
                                        new Handler(Looper.getMainLooper()).post(() ->
                                            Toast.makeText(appContext, "✅ 首次配置完成", Toast.LENGTH_SHORT).show()
                                        );
                                    }).start();
                                    return null;
                                }
                                checkinSubmitted = true;
                                // API 静默打卡模式：有 root 时跳过自动点击
                                // presetKeyValue 未配置时也跳过（通过 doSilentCheckinAPI 的 preset/key/check 会失败，降级打开表单）
                                log("API_MODE: root=" + checkinHasRoot + " inputName=" + (checkinInputName.isEmpty() ? "empty" : checkinInputName));
                                // 姓名等用户信息由 doSilentCheckinAPI 从历史记录提取，这里不再轮询 DOM
                            }
                        } catch (Throwable t) { log("AUTO_SUBMIT=" + t.getMessage()); }
                        return null;
                    });
                    log("AUTO_SUBMIT_HOOK ok");
                } catch (Throwable t) { log("AUTO_SUBMIT_HOOK=" + t.getMessage()); }

                // API静默打卡：在 onPageFinished 中更新 CookieManager 的 Cookie
                // 这样 doSilentCheckinAPI 可以直接读取
                try {
                    // 已有 onPageFinished hook，这里不需要额外 hook
                    // CookieManager 在 WebView 加载表单后自动保存 cookie
                    // doSilentCheckinAPI 会直接从 CookieManager 读取
                    log("AUTH_COOKIEMGR ok (will read on demand)");
                } catch (Throwable t) { log("AUTH_COOKIEMGR=" + t.getMessage()); }

                // 定位注入：hook LocationManager 所有定位方法
                try {
                    Class<?> locMgrClass = cl.loadClass("android.location.LocationManager");
                    // 1. getLastKnownLocation
                    for (Method m : locMgrClass.getDeclaredMethods()) {
                        if (m.getName().equals("getLastKnownLocation") && m.getParameterCount() == 1) {
                            hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                                if (checkinSpoofing) {
                                    try {
                                        android.location.Location fakeLoc = new android.location.Location("gps");
                                        fakeLoc.setLatitude(checkinLat);
                                        fakeLoc.setLongitude(checkinLng);
                                        fakeLoc.setAccuracy(10.0f);
                                        fakeLoc.setTime(System.currentTimeMillis());
                                        if (Build.VERSION.SDK_INT >= 17) fakeLoc.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
                                        log("LOC_SPOOF lastKnown " + checkinLat + "," + checkinLng);
                                        return fakeLoc;
                                    } catch (Throwable t) { log("LOC_SPOOF=" + t.getMessage()); }
                                }
                                return c.proceed();
                            });
                        }
                    }
                    // 2. requestLocationUpdates — 所有重载
                    for (Method m : locMgrClass.getDeclaredMethods()) {
                        if (m.getName().equals("requestLocationUpdates")) {
                            final int paramCount = m.getParameterCount();
                            hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                                log("REQ_LOC_UPDATES params=" + paramCount + " spoofing=" + checkinSpoofing);
                                // 先让原始调用注册 listener
                                Object reqResult = c.proceed();
                                if (checkinSpoofing) {
                                    try {
                                        for (int i = 0; i < 5; i++) {
                                            try {
                                                Object arg = c.getArg(i);
                                                if (arg instanceof android.location.LocationListener) {
                                                    android.location.Location fakeLoc = new android.location.Location("gps");
                                                    fakeLoc.setLatitude(checkinLat);
                                                    fakeLoc.setLongitude(checkinLng);
                                                    fakeLoc.setAccuracy(10.0f);
                                                    fakeLoc.setTime(System.currentTimeMillis());
                                                    if (Build.VERSION.SDK_INT >= 17) fakeLoc.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
                                                    ((android.location.LocationListener) arg).onLocationChanged(fakeLoc);
                                                    log("LOC_SPOOF requestUpdates[" + i + "] " + checkinLat + "," + checkinLng);
                                                    break;
                                                }
                                            } catch (Throwable e) { break; }
                                        }
                                    } catch (Throwable t) { log("LOC_SPOOF_REQ=" + t.getMessage()); }
                                }
                                return reqResult;
                            });
                        }
                    }
                    // 3. getCurrentLocation (API 30+)
                    try {
                        for (Method m : locMgrClass.getDeclaredMethods()) {
                            if (m.getName().equals("getCurrentLocation") && m.getParameterCount() >= 1) {
                                hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                                    if (checkinSpoofing) {
                                        android.location.Location fakeLoc = new android.location.Location("gps");
                                        fakeLoc.setLatitude(checkinLat);
                                        fakeLoc.setLongitude(checkinLng);
                                        fakeLoc.setAccuracy(10.0f);
                                        fakeLoc.setTime(System.currentTimeMillis());
                                        if (Build.VERSION.SDK_INT >= 17) fakeLoc.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
                                        log("LOC_SPOOF currentLoc " + checkinLat + "," + checkinLng);
                                        return fakeLoc;
                                    }
                                    return c.proceed();
                                });
                            }
                        }
                    } catch (Throwable ignored) {}
                    // 4. hook KIM 位置服务实现
                    try {
                        Class<?> locSvcImpl = cl.loadClass("com.kingsoft.kim.location.LocationServiceImpl");
                        for (Method m : locSvcImpl.getDeclaredMethods()) {
                            if (m.getName().equals("acquireLocation") && m.getParameterCount() == 2) {
                                hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                                    if (checkinSpoofing) {
                                        try {
                                            Object callback = c.getArg(1);
                                            // 构建 KIMLocationResult 假结果
                                            Class<?> resultClass = cl.loadClass("com.kingsoft.kim.kit.location.service.KIMLocationResult");
                                            Object locResult = resultClass.newInstance();
                                            resultClass.getMethod("setLatitude", double.class).invoke(locResult, checkinLat);
                                            resultClass.getMethod("setLongitude", double.class).invoke(locResult, checkinLng);
                                            resultClass.getMethod("setAccuracy", float.class).invoke(locResult, 10.0f);
                                            resultClass.getMethod("setTimestamp", long.class).invoke(locResult, System.currentTimeMillis());
                                            resultClass.getMethod("setCoordinateType", String.class).invoke(locResult, "gcj02");
                                            resultClass.getMethod("setProvider", String.class).invoke(locResult, "gps");
                                            resultClass.getMethod("setMocked", boolean.class).invoke(locResult, false);
                                            // 设置地址信息
                                            Class<?> addrClass = cl.loadClass("com.kingsoft.kim.kit.location.service.KIMLocationResult$Address");
                                            Object addr = addrClass.newInstance();
                                            addrClass.getMethod("setDescription", String.class).invoke(addr, checkinLocationName);
                                            addrClass.getMethod("setCity", String.class).invoke(addr, "江门市");
                                            addrClass.getMethod("setDistrict", String.class).invoke(addr, "蓬江区");
                                            addrClass.getMethod("setProvice", String.class).invoke(addr, "广东省");
                                            addrClass.getMethod("setCountry", String.class).invoke(addr, "中国");
                                            resultClass.getMethod("setAddress", addrClass).invoke(locResult, addr);
                                            // 调用 onSuccess 回调
                                            for (Method cb : callback.getClass().getMethods()) {
                                                if (cb.getName().equals("onSuccess") && cb.getParameterCount() == 1) {
                                                    cb.invoke(callback, locResult);
                                                    log("KIM_LOC_SPOOF ok " + checkinLat + "," + checkinLng);
                                                    break;
                                                }
                                            }
                                        } catch (Throwable t) { log("KIM_LOC_SPOOF=" + t.getMessage()); }
                                        return null; // 不调原始方法
                                    }
                                    return c.proceed();
                                });
                                log("KIM_LOC_IMPL_HOOK ok");
                            }
                        }
                    } catch (Throwable t) { log("KIM_LOC_IMPL=" + t.getMessage()); }
                    // 5. hook LocationService.getLocationInfo (JS Bridge 入口)
                    try {
                        Class<?> locSvcClass = cl.loadClass("com.kingsoft.kim.sdk.webapp.jsapi.jsbridge.location.LocationService");
                        for (Method m : locSvcClass.getDeclaredMethods()) {
                            if (m.getName().equals("getLocationInfo")) {
                                hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                                    log("LOC_SVC getLocationInfo spoofing=" + checkinSpoofing);
                                    if (checkinSpoofing) {
                                        try {
                                            // 获取 Callback 参数
                                            Object callback = c.getArg(1);
                                            // 构建 GetLocationInfoParamResp
                                            Class<?> respClass = cl.loadClass("com.kingsoft.kim.sdk.webapp.jsapi.jsbridge.location.callback.GetLocationInfoParamResp");
                                            Class<?> addrCbClass = cl.loadClass("com.kingsoft.kim.sdk.webapp.jsapi.jsbridge.location.callback.AddressCbParams");
                                            // 构建 AddressCbParams
                                            Object addrCb = null;
                                            try {
                                                // AddressCbParams(KIMLocationResult.Address)
                                                Class<?> addrClass = cl.loadClass("com.kingsoft.kim.kit.location.service.KIMLocationResult$Address");
                                                Object addr = addrClass.newInstance();
                                                addrClass.getMethod("setDescription", String.class).invoke(addr, checkinLocationName);
                                                addrClass.getMethod("setCity", String.class).invoke(addr, "江门市");
                                                addrClass.getMethod("setDistrict", String.class).invoke(addr, "蓬江区");
                                                addrClass.getMethod("setProvice", String.class).invoke(addr, "广东省");
                                                addrClass.getMethod("setCountry", String.class).invoke(addr, "中国");
                                                addrCb = addrCbClass.getConstructor(addrClass).newInstance(addr);
                                            } catch (Throwable t) { log("ADDR_CB=" + t.getMessage()); }
                                            Object resp = respClass.getConstructor(
                                                Double.class, Double.class, Float.class, addrCbClass,
                                                Float.class, Float.class, Boolean.class, String.class
                                            ).newInstance(
                                                checkinLat, checkinLng, 10.0f, addrCb,
                                                10.0f, 10.0f, false, "gcj02"
                                            );
                                            // callback.call("success", resp)
                                            callback.getClass().getMethod("call", String.class, Object.class)
                                                .invoke(callback, "success", resp);
                                            log("LOC_SVC spoofed ok " + checkinLat + "," + checkinLng);
                                        } catch (Throwable t) { log("LOC_SVC_SPOOF=" + t.getMessage()); }
                                        return null;
                                    }
                                    return c.proceed();
                                });
                                log("LOC_SVC_HOOK ok");
                                break;
                            }
                        }
                    } catch (Throwable t) { log("LOC_SVC_HOOK=" + t.getMessage()); }
                    log("LOC_HOOK ok");
                } catch (Throwable t) { log("LOC_HOOK=" + t.getMessage()); }

                // 调试：hook addJavascriptInterface 发现 JS Bridge
                try {
                    hook(android.webkit.WebView.class.getMethod("addJavascriptInterface", Object.class, String.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                        String name = (String) c.getArg(1);
                        log("JS_BRIDGE add: " + name + " -> " + c.getArg(0).getClass().getName());
                        return c.proceed();
                    });
                    log("JS_BRIDGE_HOOK ok");
                } catch (Throwable t) { log("JS_BRIDGE_HOOK=" + t.getMessage()); }

                // hook JsBridge.invoke 记录定位调用
                try {
                    Class<?> jsBridgeClass = cl.loadClass("com.wps.woa.sdk.browser.openplatform.jsbridge.JsBridge");
                    hook(jsBridgeClass.getMethod("invoke", String.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                        String invokeParam = (String) c.getArg(0);
                        if (invokeParam != null && invokeParam.contains("getLocationInfo")) {
                            log("JS_BRIDGE_INVOKE location: " + invokeParam.substring(0, Math.min(invokeParam.length(), 200)));
                        }
                        return c.proceed();
                    });
                    log("JS_INVOKE_HOOK ok");
                } catch (Throwable t) { log("JS_INVOKE_HOOK=" + t.getMessage()); }

                // hook RenderJSBridge.invoke 拦截小程序/表单定位
                try {
                    Class<?> renderBridgeClass = cl.loadClass("com.wps.woa.sdk.mpcore.jsbridge.RenderJSBridge");
                    hook(renderBridgeClass.getMethod("invoke", String.class, String.class, int.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                        String event = (String) c.getArg(0);
                        if (event != null && (event.contains("ocation") || event.contains("Gps") || event.contains("geo"))) {
                            log("RENDER_INVOKE event=" + event + " data=" + ((String) c.getArg(1)));
                        }
                        return c.proceed();
                    });
                    log("RENDER_INVOKE_HOOK ok");
                } catch (Throwable t) { log("RENDER_INVOKE_HOOK=" + t.getMessage()); }

                // 工作台应用列表：hook OkHttp 抓所有 KIM API 请求
                try {
                    Class<?> realCallClass = cl.loadClass("okhttp3.internal.connection.RealCall");
                    Class<?> requestClass = cl.loadClass("okhttp3.Request");
                    Class<?> httpUrlClass = cl.loadClass("okhttp3.HttpUrl");
                    java.lang.reflect.Method urlToString = httpUrlClass.getMethod("toString");

                    // 找到 RealCall 中类型为 Request 的字段
                    final java.lang.reflect.Field[] fields = {null, null};
                    for (java.lang.reflect.Field f : realCallClass.getDeclaredFields()) {
                        if (f.getType() == requestClass) { fields[0] = f; break; }
                    }
                    // 找到 Request 中类型为 HttpUrl 的字段
                    for (java.lang.reflect.Field f : requestClass.getDeclaredFields()) {
                        if (f.getType() == httpUrlClass) { fields[1] = f; break; }
                    }
                    log("HTTP_TRACE reqField=" + fields[0] + " urlField=" + fields[1]);

                    if (fields[0] != null && fields[1] != null) {
                        Class<?> callbackClass = cl.loadClass("okhttp3.Callback");
                        for (Method m : realCallClass.getDeclaredMethods()) {
                            if (m.getName().equals("j0") && m.getParameterCount() == 1 && callbackClass.isAssignableFrom(m.getParameterTypes()[0])) {
                                hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                                    try {
                                        Object req = fields[0].get(c.getThisObject());
                                        Object httpUrl = fields[1].get(req);
                                        String url = (String) urlToString.invoke(httpUrl);
                                        if (url != null && (url.contains("/apps") || url.contains("/workbenchs/applist") || url.contains("/workbenchs/setting"))) {
                                            log("HTTP_ENQ " + url);
                                            final String capturedUrl = url;
                                            final Object origCb = c.getArg(0);
                                            Object wrappedCb = java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{callbackClass}, (p, m2, a2) -> {
                                                if ("onResponse".equals(m2.getName())) {
                                                    try {
                                                        Object resp = a2[1];
                                                        Object body = resp.getClass().getMethod("body").invoke(resp);
                                                        if (body != null) {
                                                            java.lang.reflect.Method[] bodyMethods = body.getClass().getMethods();
                                                            for (java.lang.reflect.Method bm : bodyMethods) {
                                                                if (bm.getName().equals("string") && bm.getParameterCount() == 0) {
                                                                    String json = (String) bm.invoke(body);
                                                                    if (json.length() > 3000) json = json.substring(0, 3000) + "...";
                                                                    log("HTTP_RESP " + capturedUrl.substring(Math.max(0, capturedUrl.lastIndexOf('/') - 30)) + " " + json);
                                                                    break;
                                                                }
                                                            }
                                                        }
                                                    } catch (Throwable t) { log("HTTP_RESP_ERR=" + t.getMessage()); }
                                                    return m2.invoke(origCb, a2);
                                                }
                                                return m2.invoke(origCb, a2);
                                            });
                                            return c.proceed(new Object[]{wrappedCb});
                                        }
                                    } catch (Throwable ignored) {}
                                    return c.proceed();
                                });
                                break;
                            }
                        }
                    }
                    log("HTTP_TRACE ok");
                } catch (Throwable t) { log("HTTP_TRACE=" + t.getMessage()); }

                // 工作台应用：hook Gson 反序列化抓应用数据
                try {
                    Class<?> gsonClass = cl.loadClass("com.google.gson.Gson");
                    Class<?> appBriefDataClass = cl.loadClass("com.kingsoft.kim.api.webapp.model.KIMAppBriefData");
                    Class<?> appGroupDataClass = cl.loadClass("com.kingsoft.kim.api.webapp.model.KIMAppGroupData");

                    for (Method m : gsonClass.getDeclaredMethods()) {
                        if (m.getName().equals("fromJson") && m.getParameterCount() == 2
                            && m.getParameterTypes()[0].getName().contains("JsonReader")
                            && m.getParameterTypes()[1] == Class.class) {
                            hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                                Object parsed = c.proceed();
                                Class<?> targetType = (Class<?>) c.getArg(1);
                                try {
                                    if (targetType == appBriefDataClass && parsed != null) {
                                        Object appList = appBriefDataClass.getMethod("getAppList").invoke(parsed);
                                        if (appList instanceof java.util.List) {
                                            java.util.List<?> apps = (java.util.List<?>) appList;
                                            StringBuilder sb = new StringBuilder();
                                            sb.append("APPS count=").append(apps.size()).append("\n");
                                            for (Object app : apps) {
                                                try {
                                                    String name = (String) app.getClass().getField("name").get(app);
                                                    String appId = (String) app.getClass().getField("appId").get(app);
                                                    String url = (String) app.getClass().getField("url").get(app);
                                                    sb.append("  ").append(name).append(" [").append(appId).append("] ").append(url != null ? url : "").append("\n");
                                                } catch (Throwable ignored) {}
                                            }
                                            log(sb.toString());
                                        }
                                    } else if (targetType == appGroupDataClass && parsed != null) {
                                        Object groups = appGroupDataClass.getMethod("getGroupList").invoke(parsed);
                                        if (groups instanceof java.util.List) {
                                            java.util.List<?> gl = (java.util.List<?>) groups;
                                            StringBuilder sb = new StringBuilder();
                                            sb.append("GROUPS count=").append(gl.size()).append("\n");
                                            for (Object g : gl) {
                                                try {
                                                    long id = (long) g.getClass().getMethod("getId").invoke(g);
                                                    String name = (String) g.getClass().getMethod("getName").invoke(g);
                                                    sb.append("  ").append(name).append(" [id=").append(id).append("]\n");
                                                } catch (Throwable ignored) {}
                                            }
                                            log(sb.toString());
                                        }
                                    }
                                } catch (Throwable t) { log("GSON_DUMP=" + t.getMessage()); }
                                return parsed;
                            });
                            break;
                        }
                    }
                    log("GSON_DUMP ok");
                } catch (Throwable t) { log("GSON_DUMP=" + t.getMessage()); }

                // 机器人菜单：在菜单末尾添加"打卡设置"按钮
                try {
                    Class<?> robotMsgList = cl.loadClass("com.kingsoft.kim.sdk.webapp.extension.message.KERobotMessageList");
                    Class<?> robotExtView = cl.loadClass("com.kingsoft.kim.sdk.webapp.extension.message.KERobotMessageList$RobotExtensionView");
                    Method createMenu = null;
                    for (Method m : robotMsgList.getDeclaredMethods()) {
                        if (m.getName().equals("createMenu") && m.getParameterCount() == 4) {
                            createMenu = m; break;
                        }
                    }
                    if (createMenu != null) {
                        createMenu.setAccessible(true);
                        hook(createMenu).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                            c.proceed();
                            if (!checkinUiEnabled) return null;
                            try {
                                Object extView = c.getArg(3);
                                Method getMenuView = robotExtView.getMethod("getMenuView");
                                android.widget.GridLayout menuView = (android.widget.GridLayout) getMenuView.invoke(extView);
                                if (menuView == null) return null;
                                Context menuCtx = (Context) c.getArg(0);

                                // 用同样的布局和样式
                                Class<?> rLayout = cl.loadClass("com.kingsoft.kim.sdk.workbench.R$layout");
                                int layoutId = rLayout.getField("kim_ui_robot_menu_grid_item").getInt(null);
                                View item = android.view.LayoutInflater.from(menuCtx).inflate(layoutId, null);
                                Class<?> rId = cl.loadClass("com.kingsoft.kim.sdk.workbench.R$id");
                                int tvId = rId.getField("tv_item").getInt(null);
                                int dividerId = rId.getField("dividerLine").getInt(null);
                                int submenuId = rId.getField("iv_submenu_indicator").getInt(null);
                                TextView tv = (TextView) item.findViewById(tvId);
                                tv.setText("打卡设置");
                                View divider = item.findViewById(dividerId);
                                if (divider != null) divider.setVisibility(View.GONE);
                                View indicator = item.findViewById(submenuId);
                                if (indicator != null) indicator.setVisibility(View.GONE);
                                int contentId = rId.getField("ll_content").getInt(null);
                                View content = item.findViewById(contentId);
                                content.setOnClickListener(v -> showCheckinSettingsDialog(menuCtx));

                                // 追加到末尾
                                int oldCount = menuView.getColumnCount();
                                menuView.setColumnCount(oldCount + 1);
                                android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
                                lp.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1, 1.0f);
                                menuView.addView(item, lp);

                                // 统一所有按钮 weight=1.0，均匀分布
                                for (int i = 0; i < menuView.getChildCount(); i++) {
                                    View child = menuView.getChildAt(i);
                                    android.widget.GridLayout.LayoutParams clp = new android.widget.GridLayout.LayoutParams();
                                    clp.columnSpec = android.widget.GridLayout.spec(i, 1, 1.0f);
                                    clp.width = 0;
                                    child.setLayoutParams(clp);
                                }

                                // 原来末尾按钮隐藏了分割线，现在它不是末尾了，恢复分割线
                                if (oldCount > 0) {
                                    View prevItem = menuView.getChildAt(oldCount - 1);
                                    View prevDivider = prevItem.findViewById(dividerId);
                                    if (prevDivider != null) prevDivider.setVisibility(View.VISIBLE);
                                }
                                log("CHECKIN_TAG added");
                            } catch (Throwable t) { log("CHECKIN_TAG=" + t.getMessage()); }
                            return null;
                        });
                        log("MENU_HOOK ok");
                    }
                } catch (Throwable t) { log("MENU_HOOK=" + t.getMessage()); }

                // 长按文本：hook TextSelector — 记录触摸坐标，将全选改为光标定位
                try {
                    Class<?> textSelectorClass = cl.loadClass("com.kingsoft.kim.message.ext.p.message.utils.textselector.TextSelector");
                    Class<?> selectableTextClass = cl.loadClass("com.wps.woa.lib.wui.widget.textview.SelectableText");

                    // 先 hook OnLongClickListener 的 anchorView 来捕获触摸坐标
                    // 用 View.setOnTouchListener 捕获所有触摸事件
                    hook(View.class.getDeclaredMethod("onTouchEvent", MotionEvent.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                        View v = (View) c.getThisObject();
                        MotionEvent ev = (MotionEvent) c.getArg(0);
                        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                            lastTouchX = ev.getRawX();
                            lastTouchY = ev.getRawY();
                        }
                        return c.proceed();
                    });

                    // Hook show: 拦截show中的全选行为，改为光标定位
                    Method showMethod = textSelectorClass.getDeclaredMethod("show");
                    showMethod.setAccessible(true);
                    hook(showMethod).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                        try {
                            java.lang.reflect.Field stField = textSelectorClass.getDeclaredField("selectableTexts");
                            stField.setAccessible(true);
                            java.util.List<?> texts = (java.util.List<?>) stField.get(c.getThisObject());
                            if (texts != null && texts.size() > 0) {
                                // 找到触摸位置对应的SelectableText和offset
                                int targetIdx = 0;
                                int targetOffset = 0;
                                TextView targetTv = null;
                                float bestDist = Float.MAX_VALUE;
                                for (int i = 0; i < texts.size(); i++) {
                                    Object st = texts.get(i);
                                    java.lang.reflect.Method getTv = st.getClass().getMethod("getTextView");
                                    TextView tv = (TextView) getTv.invoke(st);
                                    if (tv == null || tv.getLayout() == null) continue;
                                    int[] loc = new int[2];
                                    tv.getLocationOnScreen(loc);
                                    float relX = lastTouchX - loc[0];
                                    float relY = lastTouchY - loc[1];
                                    android.text.Layout layout = tv.getLayout();
                                    if (relY >= 0 && relY <= tv.getHeight()) {
                                        int line = layout.getLineForVertical((int) relY);
                                        int offset = layout.getOffsetForHorizontal(line, relX);
                                        float dist = Math.abs(relY - tv.getHeight() / 2f);
                                        if (dist < bestDist) {
                                            bestDist = dist;
                                            targetIdx = i;
                                            targetOffset = offset;
                                            targetTv = tv;
                                        }
                                    }
                                }
                                log("TEXTSELECTOR: cursor at " + targetIdx + ":" + targetOffset + " x=" + lastTouchX + " y=" + lastTouchY);

                                // 设置所有 SelectableText
                                java.lang.reflect.Method setStart = selectableTextClass.getMethod("setSelectTextStart", int.class);
                                java.lang.reflect.Method setEnd = selectableTextClass.getMethod("setSelectTextEnd", int.class);
                                java.lang.reflect.Method setEnable = selectableTextClass.getMethod("setSelectTextEnable", boolean.class);
                                for (int i = 0; i < texts.size(); i++) {
                                    if (i == targetIdx) {
                                        setEnable.invoke(texts.get(i), true);
                                        // 选中手指下的字符/单词
                                        CharSequence cs = targetTv.getText();
                                        if (cs instanceof android.text.Spannable) {
                                            android.text.Selection.setSelection((android.text.Spannable) cs, targetOffset);
                                            // 扩展选区：选中手指下字符及前后各一字（共约3字）
                                            int wStart = targetOffset, wEnd = targetOffset;
                                            if (targetOffset < cs.length() && Character.isIdeographic(cs.charAt(targetOffset))) {
                                                wStart = Math.max(0, targetOffset - 1);
                                                wEnd = Math.min(cs.length(), targetOffset + 2);
                                            } else if (targetOffset < cs.length() && Character.isLetterOrDigit(cs.charAt(targetOffset))) {
                                                while (wStart > 0 && Character.isLetterOrDigit(cs.charAt(wStart - 1))) wStart--;
                                                while (wEnd < cs.length() && Character.isLetterOrDigit(cs.charAt(wEnd))) wEnd++;
                                            } else {
                                                wStart = Math.max(0, targetOffset - 1);
                                                wEnd = Math.min(cs.length(), targetOffset + 2);
                                            }
                                            setStart.invoke(texts.get(i), wStart);
                                            setEnd.invoke(texts.get(i), wEnd);
                                            log("TEXTSELECTOR: selected [" + wStart + "," + wEnd + "] = \"" + cs.subSequence(wStart, wEnd) + "\"");
                                        } else {
                                            setStart.invoke(texts.get(i), targetOffset);
                                            setEnd.invoke(texts.get(i), targetOffset);
                                        }
                                    } else {
                                        setEnable.invoke(texts.get(i), false);
                                    }
                                }

                                // 显示 handle
                                java.lang.reflect.Field h1Field = textSelectorClass.getDeclaredField("handleOne");
                                h1Field.setAccessible(true);
                                java.lang.reflect.Field h2Field = textSelectorClass.getDeclaredField("handleTwo");
                                h2Field.setAccessible(true);
                                Object handle1 = h1Field.get(c.getThisObject());
                                Object handle2 = h2Field.get(c.getThisObject());
                                Class<?> handleClass = handle1.getClass();
                                java.lang.reflect.Method setIdx = handleClass.getMethod("setSelectableTextIndex", int.class);
                                java.lang.reflect.Method showH = handleClass.getMethod("show");
                                setIdx.invoke(handle1, targetIdx);
                                setIdx.invoke(handle2, targetIdx);
                                showH.invoke(handle1);
                                showH.invoke(handle2);

                                // dismiss listener
                                java.lang.reflect.Field listenerField = textSelectorClass.getDeclaredField("onHandleDismissListener");
                                listenerField.setAccessible(true);
                                Object listener = listenerField.get(c.getThisObject());
                                java.lang.reflect.Method getPopup = handleClass.getMethod("getPopupWindow");
                                Object popup1 = getPopup.invoke(handle1);
                                Object popup2 = getPopup.invoke(handle2);
                                java.lang.reflect.Method setDismiss = popup1.getClass().getMethod("setOnDismissListener", android.widget.PopupWindow.OnDismissListener.class);
                                setDismiss.invoke(popup1, listener);
                                setDismiss.invoke(popup2, listener);
                            }
                        } catch (Throwable t) { log("TEXTSELECTOR_SHOW=" + t.getMessage()); }
                        return null;
                    });

                    // Hook selectAllText: 拦截全选
                    Method selectAllMethod = textSelectorClass.getDeclaredMethod("selectAllText");
                    selectAllMethod.setAccessible(true);
                    hook(selectAllMethod).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                        log("TEXTSELECTOR: selectAllText blocked");
                        return null;
                    });
                    log("TEXTSELECTOR_HOOK ok");
                } catch (Throwable t) { log("TEXTSELECTOR_HOOK=" + t.getMessage()); }

                // 精简：屏蔽埋点统计
                try {
                    Class<?> statMgr = cl.loadClass("com.wps.stat.StatManager");
                    for (Method m : statMgr.getDeclaredMethods()) {
                        if (m.getName().equals("a") && m.getParameterCount() == 2) {
                            hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> null);
                        }
                        if (m.getName().equals("c") && m.getParameterCount() == 2) {
                            hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> null);
                        }
                        if (m.getName().equals("b") && m.getParameterCount() == 3) {
                            hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> null);
                        }
                    }
                    log("STAT_BLOCK ok");
                } catch (Throwable t) { log("STAT_BLOCK=" + t.getMessage()); }

                // 精简：屏蔽更新检查
                try {
                    Class<?> sdkUpg = cl.loadClass("com.wps.woa.sdk.upgrade.api.SdkUpgrade");
                    for (Method m : sdkUpg.getDeclaredMethods()) {
                        if (m.getName().equals("a") && m.getParameterCount() == 0) {
                            hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> null);
                        }
                        if (m.getName().equals("b") && m.getParameterCount() == 1) {
                            hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> null);
                        }
                    }
                    log("UPGRADE_BLOCK ok");
                } catch (Throwable t) { log("UPGRADE_BLOCK=" + t.getMessage()); }

                // 精简：屏蔽强制更新弹窗
                try {
                    Class<?> forceVer = cl.loadClass("com.wps.woa.sdk.upgrade.ui.ForceVerDialogView");
                    hook(forceVer.getDeclaredMethod("c")).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> null);
                    log("FORCE_VER_BLOCK ok");
                } catch (Throwable t) { log("FORCE_VER_BLOCK=" + t.getMessage()); }

                // 精简：屏蔽APM上报
                try {
                    Class<?> apmTask = cl.loadClass("com.wps.woa.module.launcher.performance.task.APMUploadTask");
                    for (Method m : apmTask.getDeclaredMethods()) {
                        if (m.getName().equals("a") && m.getParameterCount() == 1) {
                            hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> null);
                            log("APM_BLOCK ok: " + m);
                            break;
                        }
                    }
                } catch (Throwable t) { log("APM_BLOCK=" + t.getMessage()); }

                // 精简：屏蔽启动性能追踪
                try {
                    Class<?> lifecycleObs = cl.loadClass("com.wps.koa.ui.util.performance.WoaStatAppLifecycleObserver");
                    for (String m : new String[]{"onActivityResumed","onActivityStarted","onActivityPaused","onActivityStopped","onActivityPreStarted"}) {
                        try {
                            hook(lifecycleObs.getDeclaredMethod(m, Activity.class))
                                .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> null);
                        } catch (Throwable ignored) {}
                    }
                    // onActivityPreCreated has extra Bundle param
                    try {
                        hook(lifecycleObs.getDeclaredMethod("onActivityPreCreated", Activity.class, Bundle.class))
                            .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> null);
                    } catch (Throwable ignored) {}
                    log("LIFECYCLE_TRACE_BLOCK ok");
                } catch (Throwable t) { log("LIFECYCLE_TRACE_BLOCK=" + t.getMessage()); }

                // 全局莫奈：hook Resources.getColor，蓝色 → 莫奈色
                try {
                    hook(android.content.res.Resources.class.getDeclaredMethod("getColor", int.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                            if (!monetEnabled) return c.proceed();
                            int color = (int) c.proceed();
                            if (isBlue(color) && wpAccent != 0) return wpAccent;
                            return color;
                        });
                } catch (Throwable ignored) {}
                try {
                    hook(android.content.res.Resources.class.getDeclaredMethod("getColor", int.class, android.content.res.Resources.Theme.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                            if (!monetEnabled) return c.proceed();
                            int color = (int) c.proceed();
                            if (isBlue(color) && wpAccent != 0) return wpAccent;
                            return color;
                        });
                } catch (Throwable ignored) {}

                // 全局莫奈：hook TypedArray.getColor
                try {
                    hook(android.content.res.TypedArray.class.getDeclaredMethod("getColor", int.class, int.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                            if (!monetEnabled) return c.proceed();
                            int color = (int) c.proceed();
                            if (isBlue(color) && wpAccent != 0) return wpAccent;
                            return color;
                        });
                } catch (Throwable ignored) {}

                // 全局莫奈：hook ContextCompat.getColor
                try {
                    hook(androidx.core.content.ContextCompat.class.getDeclaredMethod("getColor", Context.class, int.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                            if (!monetEnabled) return c.proceed();
                            int color = (int) c.proceed();
                            if (isBlue(color) && wpAccent != 0) return wpAccent;
                            return color;
                        });
                } catch (Throwable ignored) {}

                // 全局莫奈：hook Resources.getColorStateList，蓝色 CSL → 莫奈
                try {
                    hook(android.content.res.Resources.class.getDeclaredMethod("getColorStateList", int.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                            if (!monetEnabled) return c.proceed();
                            android.content.res.ColorStateList csl = (android.content.res.ColorStateList) c.proceed();
                            return replaceBlueCSL(csl);
                        });
                } catch (Throwable ignored) {}
                try {
                    hook(android.content.res.Resources.class.getDeclaredMethod("getColorStateList", int.class, android.content.res.Resources.Theme.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                            if (!monetEnabled) return c.proceed();
                            android.content.res.ColorStateList csl = (android.content.res.ColorStateList) c.proceed();
                            return replaceBlueCSL(csl);
                        });
                } catch (Throwable ignored) {}

                // 莫奈：hook setTextColor(ColorStateList)，底栏文字蓝色 → 莫奈
                try {
                    hook(TextView.class.getDeclaredMethod("setTextColor", android.content.res.ColorStateList.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                            if (!monetEnabled) return c.proceed();
                            View v = (View) c.getThisObject();
                            if (v.getClass().getName().contains("tags.")) return c.proceed();
                            android.content.res.ColorStateList csl = (android.content.res.ColorStateList) c.getArg(0);
                            if (csl != null && wpAccent != 0) {
                                int def = csl.getDefaultColor();
                                if (isBlue(def)) {
                                    try {
                                        java.lang.reflect.Field sf = android.content.res.ColorStateList.class.getDeclaredField("mStateList");
                                        sf.setAccessible(true);
                                        int[][] states = (int[][]) sf.get(csl);
                                        int[] colors = new int[states.length];
                                        for (int i = 0; i < states.length; i++)
                                            colors[i] = isBlue(csl.getColorForState(states[i], 0)) ? wpAccent : csl.getColorForState(states[i], 0);
                                        return c.proceed(new Object[]{new android.content.res.ColorStateList(states, colors)});
                                    } catch (Throwable ignored) {
                                        return c.proceed(new Object[]{android.content.res.ColorStateList.valueOf(wpAccent)});
                                    }
                                }
                            }
                            return c.proceed();
                        });
                } catch (Throwable ignored) {}

                // 莫奈：hook setColorFilter，底栏图标蓝色 → 莫奈（仅屏幕下半区）
                try {
                    hook(ImageView.class.getDeclaredMethod("setColorFilter", int.class, android.graphics.PorterDuff.Mode.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                            if (!monetEnabled) return c.proceed();
                            int color = (int) c.getArg(0);
                            if (isBlue(color) && wpAccent != 0) {
                                View v = (View) c.getThisObject();
                                int[] loc = new int[2];
                                v.getLocationOnScreen(loc);
                                int sh = v.getResources().getDisplayMetrics().heightPixels;
                                if (loc[1] > sh * 0.6f) return c.proceed(new Object[]{wpAccent, c.getArg(1)});
                            }
                            return c.proceed();
                        });
                } catch (Throwable ignored) {}

                // 莫奈：hook ForegroundColorSpan，对话气泡蓝色文字 span → 莫奈
                try {
                    hook(android.text.style.ForegroundColorSpan.class.getConstructor(int.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                            if (!monetEnabled) return c.proceed();
                            int color = (int) c.getArg(0);
                            if (isBlue(color) && wpAccent != 0) return c.proceed(new Object[]{wpAccent});
                            return c.proceed();
                        });
                } catch (Throwable ignored) {}

                // UI：hook 所有 Activity 的 onResume
                try {
                    Class<?> base = cl.loadClass("com.wps.koa.BaseActivity");
                    hook(base.getDeclaredMethod("onResume")).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain2 -> {
                        Object r2 = chain2.proceed();
                        Activity a = (Activity) chain2.getThisObject();
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try { applyStyle(a, 1); } catch (Throwable t) { log("STYLE=" + t.getMessage()); }
                        }, 500);
                        return r2;
                    });
                } catch (Throwable t) { log("UI_FAIL=" + t.getMessage()); }

                // Webhook 机器人：在聊天列表添加浮动入口
                try {
                    Class<?> chatListFrag = cl.loadClass("com.kingsoft.kim.kit.ui.chat.KIMChatListFragment");
                    hook(chatListFrag.getDeclaredMethod("onViewCreated", View.class, Bundle.class))
                        .setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                            c.proceed();
                            try {
                                View view = (View) c.getArg(0);
                                // 往上找到 Activity 的 content view，避免 FragmentContainerView 限制
                                ViewGroup target = null;
                                View current = view;
                                while (current != null) {
                                    if (current.getParent() instanceof android.widget.FrameLayout
                                        && current.getParent().getParent() == ((View) current.getParent()).getRootView().findViewById(android.R.id.content)) {
                                        target = (ViewGroup) current.getParent();
                                        break;
                                    }
                                    if (current.getParent() instanceof View) {
                                        current = (View) current.getParent();
                                    } else break;
                                }
                                if (target == null) {
                                    View root = view.getRootView();
                                    target = root.findViewById(android.R.id.content);
                                }
                                if (target == null) return null;
                                ViewGroup finalTarget = target;
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    try { addRobotButton(finalTarget, finalTarget.getContext(), cl); } catch (Throwable t) { log("ROBOT_BTN=" + t.getMessage()); }
                                }, 1500);
                            } catch (Throwable t) { log("ROBOT_INIT=" + t.getMessage()); }
                            return null;
                        });
                } catch (Throwable t) { log("ROBOT_HOOK=" + t.getMessage()); }

                // Webhook 机器人：创建群聊 + 注册消息监听
                try {
                    Class<?> kimKit = cl.loadClass("com.kingsoft.kim.kit.KIM");
                    Object kimObj = kimKit.getField("INSTANCE").get(null);

                    // 1. 通过 hook KIMCore 构造函数捕获实例
                    Class<?> kimCore = cl.loadClass("com.kingsoft.kim.core.api.KIMCore");
                    Class<?> listenerClass = cl.loadClass("com.kingsoft.kim.core.api.callback.OnReceiveMessageListener");

                    final Class<?> fKimCore = kimCore;
                    final Class<?> fListenerClass = listenerClass;
                    final Object[] kimCoreHolder = {null};

                    // hook KIMCore 所有构造函数
                    for (java.lang.reflect.Constructor<?> ctor : kimCore.getDeclaredConstructors()) {
                        hook(ctor).intercept(c -> {
                            c.proceed();
                            kimCoreHolder[0] = c.getThisObject();
                            log("KIMCORE_CTOR=" + c.getThisObject().getClass().getName());
                            return null;
                        });
                    }

                    Object listener = java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{listenerClass}, (proxy, method, args) -> {
                        // 处理 Object 方法
                        String name = method.getName();
                        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                        if ("equals".equals(name)) return proxy == args[0];
                        if ("toString".equals(name)) return "KIMListenerProxy@" + Integer.toHexString(System.identityHashCode(proxy));
                        if ("onReceived".equals(name) && args.length >= 1) {
                            try {
                                Object msg = args[0];
                                Method getChatId = msg.getClass().getMethod("getChatId");
                                Method getContent = msg.getClass().getMethod("getContent");
                                String chatId = (String) getChatId.invoke(msg);
                                Object content = getContent.invoke(msg);
                                String text = "";
                                if (content != null) {
                                    try { Object t = content.getClass().getMethod("getText").invoke(content); if (t != null) text = t.toString(); } catch (Throwable ignored) {}
                                }
                                // 获取发送者信息
                                String senderId = "";
                                try {
                                    Method getSender = msg.getClass().getMethod("getSenderId");
                                    Object s = getSender.invoke(msg);
                                    if (s != null) senderId = s.toString();
                                } catch (Throwable ignored) {
                                    try {
                                        Method getSender = msg.getClass().getMethod("getSender");
                                        Object s = getSender.invoke(msg);
                                        if (s != null) {
                                            try { Method getUid = s.getClass().getMethod("getUid"); senderId = String.valueOf(getUid.invoke(s)); } catch (Throwable ignored2) {}
                                            if (senderId.isEmpty()) senderId = s.toString();
                                        }
                                    } catch (Throwable ignored2) {}
                                }
                                log("RECV chat=" + chatId + " sender=" + senderId + " text=" + text.replace("\n", "\\n").replace("\r", "\\r") + " len=" + text.length());
                                // 过滤：只处理目标群聊的消息，排除 bot 自己发的
                                boolean isTargetChat = "100216677".equals(chatId);
                                boolean isBotMsg = text.contains("[收到]") || text.startsWith("\u200B");
                                // 自动读取隧道 URL（Watcher 通过 Webhook 推送）
                                if (text.contains("[TUNNEL_URL]")) {
                                    int urlIdx = text.indexOf("[TUNNEL_URL]") + 12;
                                    String tunnelUrl = text.substring(urlIdx).trim();
                                    if (tunnelUrl.startsWith("http")) {
                                        cchServerUrl = tunnelUrl;
                                        log("TUNNEL_URL=" + tunnelUrl);
                                    }
                                    return null; // 不处理这条消息
                                }
                                if (isTargetChat && !isBotMsg && !text.isEmpty()) {
                                    String q = text.replaceFirst("^@\\S*\\s*", "").trim();
                                    if (q.isEmpty()) return null;
                                    log("CMD q=" + q);
                                    // Slash 命令：本地处理，不发给 Claude
                                    if (q.startsWith("/")) {
                                        String cmd = q.split("\\s+")[0].toLowerCase();
                                        final String cmdArg = q.length() > cmd.length() ? q.substring(cmd.length()).trim() : "";
                                        cchExecutor.execute(() -> {
                                            String a = cmdArg;
                                            String r;
                                            switch (cmd) {
                                                case "/help":
                                                    r = "🐱 WPS-Miuix Claude 助手\n" +
                                                        "━━━━━━━━━━━━━━━━━━\n\n" +
                                                        "📨 基本使用：\n" +
                                                        "  直接在群聊发消息，会自动转发给 Claude\n" +
                                                        "  Claude 的回复会通过 Webhook 回到群聊\n\n" +
                                                        "📋 命令列表：\n" +
                                                        "  /help — 显示此帮助\n" +
                                                        "  /status — 查看服务器状态和连接信息\n" +
                                                        "  /sessions — 列出所有会话\n" +
                                                        "  /switch <id前缀> — 切换到指定会话\n" +
                                                        "  /session [id] — 查看当前/指定会话详情\n" +
                                                        "  /messages [id] — 查看当前/指定会话消息\n" +
                                                        "  /new [首条消息] — 创建新会话\n" +
                                                        "  /model [模型名] — 查看或切换模型\n" +
                                                        "  /models — 列出可用模型\n" +
                                                        "  /providers — 列出模型供应商\n" +
                                                        "  /settings — 查看系统设置\n" +
                                                        "  /perm [ask|allow] — 查看/切换权限模式\n" +
                                                        "🔑 权限授权：\n" +
                                                        "  当 Claude 需要执行敏感操作时\n" +
                                                        "  宠物上方会弹出金色权限气泡\n" +
                                                        "  点击气泡 → 同意/拒绝\n\n" +
                                                        "⏹ 停止生成：\n" +
                                                        "  思考中点击状态气泡 → 停止生成\n\n" +
                                                        "⚙️ 配置：\n" +
                                                        "  点击宠物小猫 → 设置 Webhook 和 H5 地址";
                                                    break;
                                                case "/status": r = formatStatus(cchGetStatus()); break;
                                                case "/perm": {
                                                    if ("ask".equalsIgnoreCase(a)) {
                                                        autoApprovePerm = false;
                                                        r = "🔑 权限模式: 手动确认\n敏感操作会弹出授权气泡";
                                                    } else if ("allow".equalsIgnoreCase(a)) {
                                                        autoApprovePerm = true;
                                                        r = "✅ 权限模式: 自动批准\n所有操作自动通过，不弹气泡";
                                                    } else {
                                                        r = "当前: " + (autoApprovePerm ? "自动批准(allow)" : "手动确认(ask)") +
                                                            "\n用法: /perm ask 或 /perm allow";
                                                    }
                                                    break;
                                                }
                                                case "/checkin": {
                                                    String[] parts = a.split("\\s+");
                                                    String sub = parts.length > 0 ? parts[0].toLowerCase() : "";
                                                    switch (sub) {
                                                        case "on":
                                                            checkinEnabled = true;
                                                            saveCheckinConfig();
                                                            scheduleCheckin(cchExecutor);
                                                            r = "自动打卡已开启\n时间: " + checkinHour + ":" + String.format("%02d", checkinMinute)
                                                                + (checkinWeekly ? " 每周一" : " 每天")
                                                                + "\n定位: " + checkinLocationName;
                                                            break;
                                                        case "off":
                                                            checkinEnabled = false;
                                                            saveCheckinConfig();
                                                            cancelCheckin();
                                                            r = "自动打卡已关闭";
                                                            break;
                                                        case "time":
                                                            if (parts.length >= 2) {
                                                                String[] hm = parts[1].split(":");
                                                                checkinHour = Integer.parseInt(hm[0]);
                                                                checkinMinute = hm.length > 1 ? Integer.parseInt(hm[1]) : 0;
                                                                saveCheckinConfig();
                                                                if (checkinEnabled) scheduleCheckin(cchExecutor);
                                                                r = "打卡时间: " + checkinHour + ":" + String.format("%02d", checkinMinute);
                                                            } else {
                                                                r = "用法: /checkin time 8:30";
                                                            }
                                                            break;
                                                        case "weekly":
                                                            checkinWeekly = !checkinWeekly;
                                                            saveCheckinConfig();
                                                            if (checkinEnabled) scheduleCheckin(cchExecutor);
                                                            r = "频率: " + (checkinWeekly ? "每周一" : "每天");
                                                            break;
                                                        case "loc":
                                                            if (parts.length >= 3) {
                                                                checkinLat = Double.parseDouble(parts[1]);
                                                                checkinLng = Double.parseDouble(parts[2]);
                                                                checkinLocationName = parts.length > 3 ? a.substring(sub.length() + parts[1].length() + parts[2].length() + 3) : checkinLocationName;
                                                                saveCheckinConfig();
                                                                r = "定位: " + checkinLat + "," + checkinLng + "\n" + checkinLocationName;
                                                            } else {
                                                                r = "当前: " + checkinLat + "," + checkinLng + "\n" + checkinLocationName
                                                                    + "\n用法: /checkin loc 纬度 经度 [地址名]";
                                                            }
                                                            break;
                                                        case "now":
                                                            r = "手动触发打卡...";
                                                            cchExecutor.execute(() -> doCheckin());
                                                            break;
                                                        case "root":
                                                            requestRoot(appContext);
                                                            r = "正在请求 Root 权限，请查看弹窗...";
                                                            break;
                                                        case "log":
                                                            try {
                                                                File logFile = new File(CHECKIN_LOG_FILE);
                                                                if (!logFile.exists()) {
                                                                    r = "暂无打卡记录";
                                                                } else {
                                                                    BufferedReader br = new BufferedReader(new java.io.FileReader(logFile));
                                                                    StringBuilder sb = new StringBuilder("打卡记录:\n");
                                                                    String line;
                                                                    int count = 0;
                                                                    java.util.List<String> lines = new java.util.ArrayList<>();
                                                                    while ((line = br.readLine()) != null) lines.add(line);
                                                                    br.close();
                                                                    // 显示最近10条
                                                                    int start = Math.max(0, lines.size() - 10);
                                                                    for (int i = start; i < lines.size(); i++) sb.append(lines.get(i)).append("\n");
                                                                    sb.append("共 ").append(lines.size()).append(" 条记录");
                                                                    r = sb.toString();
                                                                }
                                                            } catch (Throwable t) { r = "读取记录失败: " + t.getMessage(); }
                                                            break;
                                                        default:
                                                            r = "自动打卡设置\n"
                                                                + "状态: " + (checkinEnabled ? "开启" : "关闭") + "\n"
                                                                + "时间: " + checkinHour + ":" + String.format("%02d", checkinMinute)
                                                                + (checkinWeekly ? " 每周一" : " 每天") + "\n"
                                                                + "定位: " + checkinLat + "," + checkinLng + "\n"
                                                                + checkinLocationName + "\n"
                                                                + "Root: " + (checkinHasRoot ? "已授权" : "未授权，打开WYU-Monet授权") + "\n"
                                                                + "/checkin on — 开启\n"
                                                                + "/checkin off — 关闭\n"
                                                                + "/checkin time 8:30 — 设置时间\n"
                                                                + "/checkin weekly — 切换每周/每天\n"
                                                                + "/checkin loc 23.13 113.11 地址 — 设置定位\n"
                                                                + "/checkin now — 立即打卡\n"
                                                                + "/checkin root — 检测root状态\n"
                                                                + "/checkin log — 打卡记录";
                                                            break;
                                                    }
                                                    break;
                                                }
                                                case "/sessions": r = formatSessions(cchListSessions()); break;
                                                case "/switch": {
                                                    if (a.isEmpty()) { r = "用法: /switch <id前8位>\n发 /sessions 查看列表"; break; }
                                                    String list = cchListSessions();
                                                    String found = findSessionId(list, a);
                                                    if (found.isEmpty()) { r = "未找到匹配: " + a; break; }
                                                    cchSessionId = found;
                                                    saveSessionId();
                                                    String title = findSessionTitle(list, found);
                                                    r = "已切换到: " + (title.isEmpty() ? found.substring(0, 8) : title);
                                                    break;
                                                }
                                                case "/model": {
                                                    if (a.isEmpty()) {
                                                        // 显示当前模型
                                                        String info = httpGet("/api/models");
                                                        String curModel = jsonStr(info, "id");
                                                        r = "当前模型: " + (curModel.isEmpty() ? "默认" : curModel);
                                                        r += "\n\n切换: /model <模型名>\n可用: /models";
                                                        break;
                                                    }
                                                    // 切换模型
                                                    String resp = httpPut("/api/models", "{\"modelId\":\"" + escapeJson(a) + "\"}");
                                                    if (resp.contains("\"ok\":true")) {
                                                        r = "模型已切换为: " + a;
                                                    } else {
                                                        r = "切换失败: " + resp;
                                                    }
                                                    break;
                                                }
                                                case "/session":
                                                    if (a.isEmpty() && cchSessionId != null) a = cchSessionId;
                                                    r = a.isEmpty() ? "用法: /session <id>" : cchGetSession(a);
                                                    if (r.length() > 1000) r = r.substring(0, 1000) + "...";
                                                    break;
                                                case "/messages":
                                                    if (a.isEmpty() && cchSessionId != null) a = cchSessionId;
                                                    r = a.isEmpty() ? "用法: /messages <id>" : cchGetMessages(a);
                                                    if (r.length() > 1000) r = r.substring(0, 1000) + "...";
                                                    break;
                                                case "/new": {
                                                    String m = a.isEmpty() ? "你好" : a;
                                                    String resp = cchCreateSession(m);
                                                    String sid = jsonStr(resp, "sessionId");
                                                    if (!sid.isEmpty()) {
                                                        cchSessionId = sid;
                                                        saveSessionId();
                                                        r = "新会话: " + sid.substring(0, 8) + "...";
                                                    } else {
                                                        r = "创建失败: " + resp;
                                                    }
                                                    break;
                                                }
                                                case "/models": r = formatModels(cchGetModels()); break;
                                                case "/providers": r = formatProviders(cchGetProviders()); break;
                                                case "/settings": r = formatSettings(cchGetSettings()); break;
                                                case "/apps": {
                                                    try {
                                                        Class<?> wsmClass = cl.loadClass("com.wps.woa.sdk.net.WWebServiceManager");
                                                        Class<?> wsSvcClass = cl.loadClass("com.kingsoft.kim.sdk.webapp.api.WorkSpaceService");
                                                        Object retrofitInst = wsmClass.getMethod("e", Class.class).invoke(null, wsSvcClass);
                                                        java.lang.reflect.Field[] rFields = retrofitInst.getClass().getDeclaredFields();
                                                        Object okClient = null;
                                                        String baseUrl = null;
                                                        for (java.lang.reflect.Field rf : rFields) {
                                                            rf.setAccessible(true);
                                                            Object val = rf.get(retrofitInst);
                                                            if (val != null && val.getClass().getName().equals("okhttp3.OkHttpClient")) okClient = val;
                                                            if (val instanceof String && ((String) val).contains("http")) baseUrl = (String) val;
                                                        }
                                                        if (okClient != null && baseUrl != null) {
                                                            Class<?> reqBuilderClass = cl.loadClass("okhttp3.Request$Builder");
                                                            Object builder = reqBuilderClass.getConstructor().newInstance();
                                                            reqBuilderClass.getMethod("url", String.class).invoke(builder, baseUrl + "api/v3/apps?device_type=1&offset=0&limit=100&include_sys_app=true");
                                                            Object request = reqBuilderClass.getMethod("build").invoke(builder);
                                                            Class<?> okClientClass = cl.loadClass("okhttp3.OkHttpClient");
                                                            Object call = okClientClass.getMethod("newCall", cl.loadClass("okhttp3.Request")).invoke(okClient, request);
                                                            Object response = call.getClass().getMethod("execute").invoke(call);
                                                            Object body = response.getClass().getMethod("body").invoke(response);
                                                            String json = (String) body.getClass().getMethod("string").invoke(body);
                                                            response.getClass().getMethod("close").invoke(response);
                                                            if (json.length() > 3000) json = json.substring(0, 3000) + "...";
                                                            r = "全部应用:\n" + json;
                                                        } else {
                                                            r = "无法获取HTTP客户端 baseUrl=" + baseUrl;
                                                        }
                                                    } catch (Throwable t) { r = "APPS_ERR=" + t.getMessage(); }
                                                    break;
                                                }
                                                case "/groups": {
                                                    try {
                                                        Class<?> wsmClass2 = cl.loadClass("com.wps.woa.sdk.net.WWebServiceManager");
                                                        Class<?> wsSvcClass2 = cl.loadClass("com.kingsoft.kim.sdk.webapp.api.WorkSpaceService");
                                                        Object retrofitInst2 = wsmClass2.getMethod("e", Class.class).invoke(null, wsSvcClass2);
                                                        java.lang.reflect.Field[] rFields2 = retrofitInst2.getClass().getDeclaredFields();
                                                        Object okClient2 = null;
                                                        String baseUrl2 = null;
                                                        for (java.lang.reflect.Field rf2 : rFields2) {
                                                            rf2.setAccessible(true);
                                                            Object val2 = rf2.get(retrofitInst2);
                                                            if (val2 != null && val2.getClass().getName().equals("okhttp3.OkHttpClient")) okClient2 = val2;
                                                            if (val2 instanceof String && ((String) val2).contains("http")) baseUrl2 = (String) val2;
                                                        }
                                                        if (okClient2 != null && baseUrl2 != null) {
                                                            Class<?> reqBuilderClass2 = cl.loadClass("okhttp3.Request$Builder");
                                                            Object builder2 = reqBuilderClass2.getConstructor().newInstance();
                                                            reqBuilderClass2.getMethod("url", String.class).invoke(builder2, baseUrl2 + "api/v1/workspace/app/groups");
                                                            Object request2 = reqBuilderClass2.getMethod("build").invoke(builder2);
                                                            Class<?> okClientClass2 = cl.loadClass("okhttp3.OkHttpClient");
                                                            Object call2 = okClientClass2.getMethod("newCall", cl.loadClass("okhttp3.Request")).invoke(okClient2, request2);
                                                            Object response2 = call2.getClass().getMethod("execute").invoke(call2);
                                                            Object body2 = response2.getClass().getMethod("body").invoke(response2);
                                                            String json2 = (String) body2.getClass().getMethod("string").invoke(body2);
                                                            response2.getClass().getMethod("close").invoke(response2);
                                                            if (json2.length() > 2000) json2 = json2.substring(0, 2000) + "...";
                                                            r = "应用组:\n" + json2;
                                                        } else {
                                                            r = "无法获取HTTP客户端";
                                                        }
                                                    } catch (Throwable t) { r = "GROUPS_ERR=" + t.getMessage(); }
                                                    break;
                                                }
                                                default: r = "未知命令: " + cmd + "，发 /help 查看列表"; break;                                            }
                                            log("CMD_RESULT=" + r.substring(0, Math.min(80, r.length())));
                                            sendWebhookMessage("\u200B" + r, null);
                                        });
                                        return null;
                                    }
                                    // 普通消息：发给 Claude
                                    sendToCCH(q, cl, r -> sendWebhookMessage("\u200B" + r, null));
                                }
                            } catch (Throwable t) { log("RECV_ERR=" + t.getMessage()); }
                        }
                        return null;
                    });

                    // 通过 hook BaseActivity.onResume 来注册监听（需要 LifecycleOwner）
                    Class<?> baseActivity = cl.loadClass("com.wps.koa.BaseActivity");
                    final Object fListener = listener;
                    final Object fKimObj = kimObj;
                    final boolean[] listenerRegistered = {false};

                    hook(baseActivity.getDeclaredMethod("onResume")).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(c -> {
                        c.proceed();
                        if (listenerRegistered[0]) return null;
                        Activity a = null;
                        try {
                            a = (Activity) c.getThisObject();
                            Object coreInst = kimCoreHolder[0];
                            if (coreInst == null) {
                                // 尝试 KIMCore.instance()
                                try { coreInst = fKimCore.getMethod("instance").invoke(null); } catch (Throwable ignored) {}
                            }
                            if (coreInst == null) {
                                log("NO_KIMCORE_INSTANCE");
                                return null;
                            }
                            log("KIMCORE=" + coreInst.getClass().getName());
                            // 在 KIMCore 实例上找 addOnReceiveMessageListener
                            Method addListener = null;
                            for (Method m : coreInst.getClass().getMethods()) {
                                if (m.getName().equals("addOnReceiveMessageListener")) {
                                    addListener = m;
                                    log("FOUND: " + m.getParameterCount() + " params, decl=" + m.getDeclaringClass().getName());
                                    break;
                                }
                            }
                            if (addListener != null) {
                                if (addListener.getParameterCount() == 2) {
                                    addListener.invoke(coreInst, a, fListener);
                                } else {
                                    addListener.invoke(coreInst, fListener);
                                }
                                listenerRegistered[0] = true;
                                log("MSG_LISTENER_OK");
                            } else {
                                log("MSG_LISTENER_NOT_FOUND");
                            }
                        } catch (Throwable t) {
                            Throwable cause = t;
                            while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null) {
                                cause = cause.getCause();
                            }
                            log("MSG_LISTENER=" + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                            // 打印堆栈帮助调试
                            for (StackTraceElement ste : cause.getStackTrace()) {
                                log("  at " + ste.toString());
                            }
                        }
                        return null;
                    });

                    // 2. 创建群聊
                    Method getChatModule = kimObj.getClass().getMethod("getChatModule");
                    Object chatModule = getChatModule.invoke(kimObj);
                    if (chatModule != null) {
                        // 找 navigateToCreateGroupChat 方法并调用
                        Method navigate = null;
                        for (Method m : chatModule.getClass().getMethods()) {
                            if (m.getName().equals("navigateToCreateGroupChat") && m.getParameterCount() == 2) {
                                navigate = m;
                                break;
                            }
                        }
                        if (navigate != null) {
                            // 延迟调用，等 Activity 准备好
                            final Method fNavigate = navigate;
                            final Object fChatModule = chatModule;
                            new Thread(() -> {
                                try {
                                    Thread.sleep(3000);
                                    // 通过 hook onResume 获取 Activity 引用
                                    // 这里先记录方法，等 Activity 可用时调用
                                    log("NAVIGATE_READY");
                                } catch (Throwable t) { log("NAVIGATE=" + t.getMessage()); }
                            }).start();
                        }
                    }

                } catch (Throwable t) { log("ROBOT_INIT=" + t.getMessage()); }

                // [已注释] Hook Cipher：旧方案拦截加密参数，API静默打卡不需要
                // try {
                //     Class<?> cipherClass = Class.forName("javax.crypto.Cipher");
                //     for (java.lang.reflect.Method m : cipherClass.getDeclaredMethods()) {
                //         if (m.getName().equals("init") && m.getParameterCount() == 2 ...) { ... }
                //     }
                // } catch (Throwable t) { log("CIPHER_HOOK=" + t.getMessage()); }

                log("HOOKS_DONE PID=" + android.os.Process.myPid());
                return result;
            });
        } catch (Throwable t) { log("ATTACH_FAIL=" + t.getMessage()); }
    }

    private boolean su(String c) { if (allowRootAccess) return false; String l = c.toLowerCase(); return l.contains("su") || l.contains("magisk") || l.contains("ksu") || l.contains("busybox"); }
    private boolean suA(String[] a) { if (allowRootAccess) return false; for (String s : a) if (s != null && su(s)) return true; return false; }

    private Activity mainActivity = null;
    private boolean wpListenerSet = false;

    private void applyStyle(Activity a, int attempt) {
        log("APPLY #" + attempt);
        ViewGroup content = (ViewGroup) a.getWindow().getDecorView().findViewById(android.R.id.content);
        if (content == null) { log("NO_CONTENT"); return; }
        boolean dark = dark(a);

        wpAccent = 0;
        loadWP(a, dark);
        int accent = wpAccent != 0 ? wpAccent : sysA(a, dark);

        // 1. 底栏图标：莫奈染色
        ViewGroup bar = findBar(content);
        if (bar != null) tintBlueIcons(bar, accent);

        // 2. 对话气泡 messageText：背景莫奈染色
        tintMessageBubble(content, accent);

        // 3. 状态栏 + 导航栏配色
        styleSystemBars(a, accent, dark);

        mainActivity = a;

        if (attempt == 1 && !wpListenerSet) {
            try {
                a.registerReceiver(new android.content.BroadcastReceiver() {
                    @Override public void onReceive(Context ctx, android.content.Intent intent) {
                        log("WALLPAPER_CHANGED");
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (mainActivity != null) applyStyle(mainActivity, 1);
                        }, 1000);
                    }
                }, new android.content.IntentFilter("android.intent.action.WALLPAPER_CHANGED"));
                wpListenerSet = true;
                log("WP_LISTENER_OK");
            } catch (Throwable t) { log("WP_LISTENER_FAIL=" + t.getMessage()); }
        }

        log("DONE accent=#" + Integer.toHexString(accent));
    }

    private void loadWP(Context ctx, boolean dark) {
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                android.app.WallpaperColors wc = WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
                if (wc != null) { wpAccent = wc.getPrimaryColor().toArgb(); log("COLOR=#" + Integer.toHexString(wpAccent)); }
            }
        } catch (Throwable t) { log("WP_FAIL=" + t.getMessage()); }
    }

    // 底栏图标：蓝色 colorFilter → 莫奈色
    private void tintBlueIcons(ViewGroup vg, int accent) {
        if (accent == 0) return;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View v = vg.getChildAt(i);
            if (v instanceof ImageView) {
                ImageView iv = (ImageView) v;
                ColorFilter cf = iv.getColorFilter();
                if (cf != null) {
                    iv.clearColorFilter();
                    iv.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
                }
            }
            if (v instanceof ViewGroup) tintBlueIcons((ViewGroup) v, accent);
        }
    }

    // 对话气泡 messageText：背景蓝色 → 莫奈色
    private void tintMessageBubble(ViewGroup vg, int accent) {
        if (accent == 0) return;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View v = vg.getChildAt(i);
            if (v.getId() == v.getResources().getIdentifier("messageText", "id", v.getContext().getPackageName())) {
                if (v.getBackground() != null) tintDrawable(v.getBackground(), accent);
            }
            if (v instanceof ViewGroup) tintMessageBubble((ViewGroup) v, accent);
        }
    }

    // 替换 ColorStateList 中的蓝色为莫奈色
    private android.content.res.ColorStateList replaceBlueCSL(android.content.res.ColorStateList csl) {
        if (csl == null || wpAccent == 0) return csl;
        try {
            int def = csl.getDefaultColor();
            if (!isBlue(def)) return csl;
            java.lang.reflect.Field sf = android.content.res.ColorStateList.class.getDeclaredField("mStateList");
            sf.setAccessible(true);
            int[][] states = (int[][]) sf.get(csl);
            int[] colors = new int[states.length];
            for (int i = 0; i < states.length; i++)
                colors[i] = isBlue(csl.getColorForState(states[i], 0)) ? wpAccent : csl.getColorForState(states[i], 0);
            return new android.content.res.ColorStateList(states, colors);
        } catch (Throwable t) {
            try { return android.content.res.ColorStateList.valueOf(wpAccent); } catch (Throwable ignored) { return csl; }
        }
    }

    // 修复被 Resources.getColor hook 误伤的 ExternalTagView（反射直接改字段，绕过 hook）
    private static java.lang.reflect.Field sTextColorField;
    private int resetTagViewColor(ViewGroup vg, int tagId) {
        int count = 0;
        try {
            if (sTextColorField == null) {
                sTextColorField = TextView.class.getDeclaredField("mCurTextColor");
                sTextColorField.setAccessible(true);
            }
            for (int i = 0; i < vg.getChildCount(); i++) {
                View v = vg.getChildAt(i);
                if (v.getId() == tagId && v instanceof TextView) {
                    sTextColorField.setInt(v, 0xFF3B64FC);
                    v.invalidate();
                    count++;
                }
                if (v instanceof ViewGroup) count += resetTagViewColor((ViewGroup) v, tagId);
            }
        } catch (Throwable t) { log("TAG_RST_FAIL=" + t.getMessage()); }
        return count;
    }

    // 递归给 Drawable 换色
    private void tintDrawable(android.graphics.drawable.Drawable d, int accent) {
        if (d instanceof GradientDrawable) {
            GradientDrawable gd = (GradientDrawable) d;
            // 检查填充色是否蓝色
            try {
                java.lang.reflect.Field f = GradientDrawable.class.getDeclaredField("mGradientState");
                f.setAccessible(true);
                Object state = f.get(gd);
                java.lang.reflect.Field cf = state.getClass().getDeclaredField("mSolidColor");
                cf.setAccessible(true);
                int color = cf.getInt(state);
                if (isBlue(color)) cf.setInt(state, accent);
            } catch (Throwable ignored) {}
        } else if (d instanceof android.graphics.drawable.StateListDrawable) {
            // 状态列表：递归每个状态
            try {
                java.lang.reflect.Field f = android.graphics.drawable.StateListDrawable.class.getDeclaredField("mStateListState");
                f.setAccessible(true);
                Object state = f.get(d);
                java.lang.reflect.Field cf = state.getClass().getDeclaredField("mDrawables");
                cf.setAccessible(true);
                android.graphics.drawable.Drawable[] drawables = (android.graphics.drawable.Drawable[]) cf.get(state);
                if (drawables != null) for (android.graphics.drawable.Drawable sd : drawables) {
                    if (sd != null) tintDrawable(sd, accent);
                }
            } catch (Throwable ignored) {}
        } else if (d instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) d;
            for (int i = 0; i < ld.getNumberOfLayers(); i++) tintDrawable(ld.getDrawable(i), accent);
        }
    }

    // 判断是否是蓝色系
    private boolean isBlue(int color) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        return b > 150 && b > r && b > g;
    }

    // 状态栏 + 导航栏配色
    private void styleSystemBars(Activity a, int accent, boolean dark) {
        try {
            Window w = a.getWindow();
            if (Build.VERSION.SDK_INT >= 21) {
                // 状态栏：半透明主题色
                w.setStatusBarColor(dark ? blend(accent, Color.BLACK, 0.7f) : blend(accent, Color.WHITE, 0.85f));
                // 导航栏：半透明主题色
                w.setNavigationBarColor(dark ? blend(accent, Color.BLACK, 0.7f) : blend(accent, Color.WHITE, 0.85f));
                // 深色模式下浅色图标，浅色模式下深色图标
                if (Build.VERSION.SDK_INT >= 23) {
                    View decor = w.getDecorView();
                    int flags = decor.getSystemUiVisibility();
                    if (dark) {
                        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    } else {
                        flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                        if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                    decor.setSystemUiVisibility(flags);
                }
            }
        } catch (Throwable ignored) {}
    }

    // 查找底栏
    private ViewGroup findBar(ViewGroup r) { return findBarR(r, 0); }
    private ViewGroup findBarR(ViewGroup v, int d) {
        if (d > 15) return null;
        int n = v.getChildCount();
        if (n >= 3 && n <= 7) {
            boolean ok = true;
            for (int i = 0; i < n; i++) { String c = v.getChildAt(i).getClass().getName(); if (!c.contains("ConstraintLayout") && !c.contains("LinearLayout") && !c.contains("FrameLayout")) { ok = false; break; } }
            if (ok) { int[] l = new int[2]; v.getLocationInWindow(l); int sh = v.getRootView().getHeight(); if (sh > 0 && l[1] > sh * 0.6f) return v; }
        }
        for (int i = 0; i < n; i++) if (v.getChildAt(i) instanceof ViewGroup) { ViewGroup r = findBarR((ViewGroup) v.getChildAt(i), d + 1); if (r != null) return r; }
        return null;
    }

    private static String WEBHOOK_URL = "https://xz.wps.cn/api/v1/webhook/send?key=f5e5b6cfb137d066f8bba173bdc82fb5";
    private static float lastTouchX = 0, lastTouchY = 0;

    private static String SESSION_FILE = "/data/local/tmp/wps-miuix-session.txt";
    private static String CONFIG_FILE = "/data/local/tmp/wps-miuix-config.txt";
    private static String COOKIE_FILE = "/data/local/tmp/wps-cookies.txt";
    private static String CSRF_FILE = "/data/local/tmp/wps-csrf.txt";
    private static String PARAMS_FILE = "/data/local/tmp/wps-checkin-params.txt";

    // 将配置文件路径初始化到 WPS 私有目录（模块运行在 WPS 进程内，写私有目录最可靠）
    private static void initDataPaths() {
        if (appContext == null) return;
        String dir = getDataDir();
        logPath = dir + "/wps-miuix.log";
        SESSION_FILE = dir + "/wps-miuix-session.txt";
        CONFIG_FILE = dir + "/wps-miuix-config.txt";
        COOKIE_FILE = dir + "/wps-cookies.txt";
        CSRF_FILE = dir + "/wps-csrf.txt";
        PARAMS_FILE = dir + "/wps-checkin-params.txt";
        CHECKIN_FILE = dir + "/wps-miuix-checkin.txt";
        CHECKIN_LOG_FILE = dir + "/wps-miuix-checkin-log.txt";
        FORM_URL_FILE = dir + "/wps-miuix-form-url.txt";
        // 迁移旧配置（从 /data/local/tmp 到私有目录）
        migrateFile("/data/local/tmp/wps-miuix-config.txt", CONFIG_FILE);
        migrateFile("/data/local/tmp/wps-miuix-session.txt", SESSION_FILE);
        migrateFile("/data/local/tmp/wps-miuix-checkin.txt", CHECKIN_FILE);
        migrateFile("/data/local/tmp/wps-miuix-checkin-log.txt", CHECKIN_LOG_FILE);
        migrateFile("/data/local/tmp/wps-miuix-form-url.txt", FORM_URL_FILE);
        // 预创建所有私有目录文件（owner 保持 WPS UID，CheckinWorker 以 root 追加读写不影响）
        precreateFiles();
        // 恢复上次捕获的动态表单 URL
        loadFormUrl();
    }

    private static void precreateFiles() {
        try {
            String[] files = {logPath, SESSION_FILE, CONFIG_FILE, COOKIE_FILE, CSRF_FILE, PARAMS_FILE, CHECKIN_FILE, CHECKIN_LOG_FILE, FORM_URL_FILE};
            for (String f : files) {
                try {
                    java.io.File file = new java.io.File(f);
                    if (!file.exists()) file.createNewFile();
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    // 保存动态表单 URL + campaign ID（供后续使用，也供 CheckinWorker 读取）
    private static void saveFormUrl(String url) {
        try {
            if (checkinManualFormUrl) return; // 手动锁定表单链接，自动捕获不覆盖
            String cid = extractCampaignId(url);
            if (cid == null) return;
            String content = url + "\n" + cid + "\n";
            saveRootFile(FORM_URL_FILE, content);
            // 同步更新内存中的值和参数文件，供 CheckinWorker 使用
            CHECKIN_FORM_URL = url;
            CAMPAIGN_ID = cid;
            log("FORM_URL_UPDATED cid=" + cid + " url=" + url);
        } catch (Throwable t) { log("FORM_URL_SAVE=" + t.getMessage()); }
    }

    // 手动填写表单链接：解析 cid 并锁定（自动捕获不覆盖），供 CheckinWorker 读取
    private static boolean applyManualFormUrl(String url, Context ctx) {
        try {
            String u = url == null ? "" : url.trim();
            if (u.isEmpty()) { Toast.makeText(ctx, "请输入表单链接", Toast.LENGTH_SHORT).show(); return false; }
            String cid = extractCampaignId(u);
            if (cid == null) { Toast.makeText(ctx, "无法识别表单链接，请检查链接格式", Toast.LENGTH_SHORT).show(); return false; }
            CHECKIN_FORM_URL = u;
            CAMPAIGN_ID = cid;
            checkinManualFormUrl = true;
            saveRootFile(FORM_URL_FILE, u + "\n" + cid + "\n");
            // 同步更新参数文件中的 campaign（供独立 CheckinWorker 使用）
            try {
                String params = readFileQuiet(PARAMS_FILE);
                StringBuilder sb = new StringBuilder();
                boolean found = false;
                boolean campaignChanged = false;
                String oldCid = "";
                for (String line : params.split("\n")) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    if (t.startsWith("campaign=")) {
                        oldCid = t.substring(9).trim();
                        if (!oldCid.equals(cid)) campaignChanged = true;
                        sb.append("campaign=").append(cid).append("\n");
                        found = true;
                    }
                    // campaign 变化时清空旧缓存，强制下次打卡重新读取新表单格式
                    else if (campaignChanged && (t.startsWith("cachedCampaign=") || t.startsWith("fields=") || t.startsWith("values="))) {
                        log("campaign changed " + oldCid + " -> " + cid + ", dropping cached: " + t.substring(0, t.indexOf('=')));
                    }
                    else sb.append(line).append("\n");
                }
                if (!found) sb.append("campaign=").append(cid).append("\n");
                saveRootFile(PARAMS_FILE, sb.toString());
            } catch (Throwable t) { log("FORM_URL_PARAMS=" + t.getMessage()); }
            saveCheckinConfig();
            log("FORM_URL_MANUAL cid=" + cid);
            Toast.makeText(ctx, "表单链接已更新\n" + u, Toast.LENGTH_SHORT).show();
            return true;
        } catch (Throwable t) {
            log("FORM_URL_MANUAL=" + t.getMessage());
            Toast.makeText(ctx, "保存失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    // 读取私有目录文件（不存在返回空串）
    private static String readFileQuiet(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return "";
            BufferedReader br = new BufferedReader(new java.io.FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) { if (sb.length() > 0) sb.append("\n"); sb.append(line); }
            br.close();
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }

    // 从表单 URL 提取 campaign ID（https://f.wps.cn/ksform/cw/w/{cid} 或 f.kdocs.cn/g/{cid}）
    private static String extractCampaignId(String url) {
        try {
            if (url == null) return null;
            String u = url;
            int hash = u.indexOf('#');
            if (hash >= 0) u = u.substring(0, hash);
            int lastSlash = u.lastIndexOf('/');
            String cid = lastSlash >= 0 ? u.substring(lastSlash + 1) : u;
            if (cid.isEmpty()) return null;
            // 只保留字母数字
            cid = cid.replaceAll("[^A-Za-z0-9]", "");
            return cid.isEmpty() ? null : cid;
        } catch (Throwable t) { return null; }
    }

    // 启动时从文件恢复上次捕获的动态表单 URL
    private static void loadFormUrl() {
        try {
            File f = new File(FORM_URL_FILE);
            if (!f.exists()) return;
            BufferedReader br = new BufferedReader(new java.io.FileReader(f));
            String url = br.readLine();
            String cid = br.readLine();
            br.close();
            if (url != null && !url.isEmpty() && cid != null && !cid.isEmpty()) {
                CHECKIN_FORM_URL = url;
                CAMPAIGN_ID = cid;
                log("FORM_URL_RESTORED cid=" + cid);
            }
        } catch (Throwable t) { log("FORM_URL_LOAD=" + t.getMessage()); }
    }

    private static void migrateFile(String src, String dst) {
        try {
            java.io.File sf = new java.io.File(src);
            java.io.File df = new java.io.File(dst);
            if (sf.exists() && !df.exists() && sf.length() > 0) {
                java.io.InputStream is = new java.io.FileInputStream(sf);
                java.io.OutputStream os = new java.io.FileOutputStream(df);
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                is.close();
                os.close();
                log("MIGRATED " + src + " -> " + dst);
            }
        } catch (Throwable ignored) {}
    }

    // 保存配置
    private static void saveConfig() {
        try {
            allowRootAccess = true;
            saveRootFile(CONFIG_FILE, WEBHOOK_URL + "\n" + cchServerUrl + "\n" + cchH5Token + "\n");
            allowRootAccess = false;
            log("CONFIG_SAVED");
        } catch (Throwable t) {
            allowRootAccess = false;
            log("CONFIG_SAVE_ERR=" + t.getMessage());
        }
    }

    // 加载配置
    private static void loadConfig() {
        try {
            File f = new File(CONFIG_FILE);
            if (!f.exists()) return;
            BufferedReader br = new BufferedReader(new java.io.FileReader(f));
            String wh = br.readLine();
            String srv = br.readLine();
            String tok = br.readLine();
            br.close();
            if (wh != null && !wh.isEmpty()) WEBHOOK_URL = wh;
            if (srv != null && !srv.isEmpty()) cchServerUrl = srv;
            if (tok != null && !tok.isEmpty()) cchH5Token = tok;
            log("CONFIG_LOADED server=" + cchServerUrl);
        } catch (Throwable t) {
            log("CONFIG_LOAD_ERR=" + t.getMessage());
        }
        // 从 /data/local/tmp/ 读取开关状态（模块 UI 直接写入，world-readable）
        try {
            petEnabled = "1".equals(readFlagFile("/data/local/tmp/wyu-pet-enabled"));
            checkinUiEnabled = "1".equals(readFlagFile("/data/local/tmp/wyu-checkin-enabled"));
            monetEnabled = "1".equals(readFlagFile("/data/local/tmp/wyu-monet-enabled"));
            rootHideEnabled = "1".equals(readFlagFile("/data/local/tmp/wyu-root-hide"));
            watermarkEnabled = "1".equals(readFlagFile("/data/local/tmp/wyu-watermark"));
        } catch (Throwable t) {
            log("FLAG_READ_ERR=" + t.getMessage());
        }
        log("FLAGS pet=" + petEnabled + " checkinUi=" + checkinUiEnabled + " monet=" + monetEnabled + " rootHide=" + rootHideEnabled + " watermark=" + watermarkEnabled);
    }

    // WPS 私有数据目录（模块运行在 WPS 进程内，写自己的目录最可靠，不受 /data/local/tmp SELinux 限制）
    private static String getDataDir() {
        return appContext != null ? appContext.getFilesDir().getAbsolutePath() : "/data/local/tmp";
    }

    private static String cchSessionId = null;

    private static void saveSessionId() {
        try {
            if (cchSessionId == null) return;
            allowRootAccess = true;
            saveRootFile(SESSION_FILE, cchSessionId);
            allowRootAccess = false;
        } catch (Throwable t) {
            allowRootAccess = false;
        }
    }

    private static String loadSessionId() {
        try {
            File f = new File(SESSION_FILE);
            if (!f.exists()) return null;
            byte[] data = new byte[(int) f.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            fis.read(data); fis.close();
            String s = new String(data, StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? null : s;
        } catch (Throwable t) { return null; }
    }

    // 宠物位置持久化（存 WPS 私有目录，可靠读写）
    private static void savePetPos(float tx, float ty) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter(getDataDir() + "/petpos.txt");
            fw.write((int) tx + "," + (int) ty);
            fw.close();
        } catch (Throwable ignored) {}
    }
    private static float[] loadPetPos() {
        try {
            File f = new File(getDataDir() + "/petpos.txt");
            if (!f.exists()) return null;
            byte[] data = new byte[(int) f.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            fis.read(data); fis.close();
            String[] parts = new String(data, StandardCharsets.UTF_8).trim().split(",");
            if (parts.length == 2) return new float[]{Float.parseFloat(parts[0]), Float.parseFloat(parts[1])};
        } catch (Throwable ignored) {}
        return null;
    }

    // 发送停止生成命令
    private static void sendStopGeneration() {
        cchExecutor.execute(() -> {
            try {
                if (cchSocket != null && !cchSocket.isClosed() && cchOut != null) {
                    String msg = "{\"type\":\"stop_generation\"}";
                    byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_8);
                    byte[] mask = new byte[4];
                    new SecureRandom().nextBytes(mask);
                    byte[] frame = new byte[msgBytes.length + 6];
                    frame[0] = (byte) 0x81;
                    frame[1] = (byte) (msgBytes.length | 0x80);
                    System.arraycopy(mask, 0, frame, 2, 4);
                    for (int i = 0; i < msgBytes.length; i++)
                        frame[i + 6] = (byte) (msgBytes[i] ^ mask[i % 4]);
                    synchronized (cchOut) { cchOut.write(frame); cchOut.flush(); }
                    log("STOP_SENT");
                    // 清除异步收集状态
                    asyncCollecting = false;
                    asyncResponse = null;
                    asyncCallback = null;
                    restPollingActive = false;
                    pendingPermissionId = null;
                    pendingPermissionText = "";
                    clearPermView();
                }
            } catch (Exception e) {
                log("STOP_ERR=" + e.getMessage());
            }
        });
    }

    private static final ExecutorService webhookExecutor = Executors.newSingleThreadExecutor();
    private static final ExecutorService cchExecutor = Executors.newSingleThreadExecutor();

    // 添加机器人浮动按钮到聊天列表
    private void addRobotButton(ViewGroup parent, Context ctx, ClassLoader cl) {
        if (!petEnabled) return; // 宠物UI已关闭
        // 避免重复添加
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i).getTag() != null && "wyu_robot_btn".equals(parent.getChildAt(i).getTag().toString())) return;
        }

        LinearLayout wrapper = new LinearLayout(ctx);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        wrapper.setTag("wyu_robot_btn");
        FrameLayout.LayoutParams wrapperLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        wrapperLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        wrapperLp.bottomMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 80, ctx.getResources().getDisplayMetrics());
        wrapperLp.rightMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, ctx.getResources().getDisplayMetrics());
        wrapper.setLayoutParams(wrapperLp);

        // 动作气泡容器（在状态气泡上方，默认隐藏）
        LinearLayout actionContainer = new LinearLayout(ctx);
        actionContainer.setOrientation(LinearLayout.VERTICAL);
        actionContainer.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        actionContainer.setVisibility(View.GONE);
        LinearLayout.LayoutParams actionCLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionCLp.bottomMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, ctx.getResources().getDisplayMetrics());
        wrapper.addView(actionContainer, actionCLp);

        // 状态气泡（在宠物上方，带小三角箭头）
        petStatusView = new TextView(ctx);
        petStatusView.setTypeface(android.graphics.Typeface.MONOSPACE);
        petStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        petStatusView.setTextColor(0xFFEEEEEE);
        petStatusView.setGravity(android.view.Gravity.CENTER);
        petStatusView.setVisibility(View.GONE);
        int statusPad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6, ctx.getResources().getDisplayMetrics());
        petStatusView.setPadding(statusPad * 2, statusPad, statusPad * 2, statusPad);
        // 气泡背景（带底部三角）
        petStatusView.setBackground(new Drawable() {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            final float radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, ctx.getResources().getDisplayMetrics());
            final float arrowSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5, ctx.getResources().getDisplayMetrics());
            { paint.setColor(0xDD222222); }
            @Override public void draw(Canvas c) {
                RectF r = new RectF(getBounds());
                r.bottom -= arrowSize;
                c.drawRoundRect(r, radius, radius, paint);
                float cx = r.centerX();
                Path arrow = new Path();
                arrow.moveTo(cx - arrowSize, r.bottom);
                arrow.lineTo(cx, r.bottom + arrowSize);
                arrow.lineTo(cx + arrowSize, r.bottom);
                arrow.close();
                c.drawPath(arrow, paint);
            }
            @Override public void setAlpha(int a) { paint.setAlpha(a); }
            @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        });
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.bottomMargin = 0;
        wrapper.addView(petStatusView, statusLp);

        // 权限气泡（独立于状态气泡，不被覆盖）
        petPermView = new TextView(ctx);
        petPermView.setTypeface(android.graphics.Typeface.MONOSPACE);
        petPermView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        petPermView.setTextColor(0xFFFFD54F);
        petPermView.setGravity(android.view.Gravity.CENTER);
        petPermView.setVisibility(View.GONE);
        petPermView.setPadding(statusPad * 2, statusPad, statusPad * 2, statusPad);
        petPermView.setBackground(new Drawable() {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            final float radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, ctx.getResources().getDisplayMetrics());
            final float arrowSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5, ctx.getResources().getDisplayMetrics());
            { paint.setColor(0xDD332200); }
            @Override public void draw(Canvas c) {
                RectF r = new RectF(getBounds());
                r.bottom -= arrowSize;
                c.drawRoundRect(r, radius, radius, paint);
                // 边框
                Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
                border.setStyle(Paint.Style.STROKE);
                border.setColor(0xFFFFD54F);
                border.setStrokeWidth(2);
                c.drawRoundRect(r, radius, radius, border);
                float cx = r.centerX();
                Path arrow = new Path();
                arrow.moveTo(cx - arrowSize, r.bottom);
                arrow.lineTo(cx, r.bottom + arrowSize);
                arrow.lineTo(cx + arrowSize, r.bottom);
                arrow.close();
                c.drawPath(arrow, paint);
                c.drawPath(arrow, border);
            }
            @Override public void setAlpha(int a) { paint.setAlpha(a); }
            @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        });
        LinearLayout.LayoutParams permLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        permLp.bottomMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2, ctx.getResources().getDisplayMetrics());
        wrapper.addView(petPermView, permLp);

        // 点击权限气泡 → 显示同意/拒绝
        final LinearLayout fActionContainer = actionContainer;
        petPermView.setOnClickListener(v -> {
            if (fActionContainer.getVisibility() == View.VISIBLE) {
                fActionContainer.setVisibility(View.GONE);
                fActionContainer.removeAllViews();
                return;
            }
            fActionContainer.removeAllViews();
            if (pendingPermissionId != null) {
                addActionBubble(ctx, fActionContainer, "✅ 同意", 0xFF4CAF50, () -> {
                    sendPermissionResponse(pendingPermissionId, true);
                    fActionContainer.setVisibility(View.GONE);
                    fActionContainer.removeAllViews();
                });
                addActionBubble(ctx, fActionContainer, "❌ 拒绝", 0xFFF44336, () -> {
                    sendPermissionResponse(pendingPermissionId, false);
                    fActionContainer.setVisibility(View.GONE);
                    fActionContainer.removeAllViews();
                });
            }
            if (fActionContainer.getChildCount() > 0) {
                fActionContainer.setVisibility(View.VISIBLE);
            }
        });

        // 点击状态气泡 → 显示停止按钮
        petStatusView.setOnClickListener(v -> {
            if (actionContainer.getVisibility() == View.VISIBLE) {
                actionContainer.setVisibility(View.GONE);
                actionContainer.removeAllViews();
                return;
            }
            actionContainer.removeAllViews();
            if (petStatusMode == 1 || petStatusMode == 2) {
                addActionBubble(ctx, actionContainer, "⏹ 停止生成", 0xFFF44336, () -> {
                    sendStopGeneration();
                    updatePetStatus("⏹ 已停止", 4);
                    actionContainer.setVisibility(View.GONE);
                    actionContainer.removeAllViews();
                });
            }
            if (actionContainer.getChildCount() > 0) {
                actionContainer.setVisibility(View.VISIBLE);
            }
        });

        // ASCII 宠物 — 单行动画（固定宽度）
        final int[] frameIdx = {0};
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, ctx.getResources().getDisplayMetrics());

        petView = new TextView(ctx);
        petView.setTypeface(android.graphics.Typeface.MONOSPACE);
        petView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        petView.setTextColor(0xFFFFFFFF);
        petView.setText(PET_FRAMES[0][0]);
        petView.setShadowLayer(4, 0, 1, 0x80000000);
        petView.setSingleLine(true);
        // 固定宽度：9个字符宽（等宽字体）
        float charWidth = petView.getPaint().measureText("M");
        int fixedWidth = (int) (charWidth * 9) + pad;
        petView.setMinWidth(fixedWidth);
        petView.setMaxWidth(fixedWidth);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, ctx.getResources().getDisplayMetrics()));
        bg.setColor(0xCC000000);
        petView.setPadding(pad, pad / 2, pad, pad / 2);
        petView.setBackground(bg);
        petView.setElevation(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6, ctx.getResources().getDisplayMetrics()));

        // 动画：每 600ms 换帧（跟随 petStatusMode）
        Runnable animRunnable = new Runnable() {
            @Override
            public void run() {
                frameIdx[0] = (frameIdx[0] + 1) % PET_FRAMES[0].length;
                String[] frames = PET_FRAMES[petStatusMode];
                petView.setText(frames[frameIdx[0] % frames.length]);
                petHandler.postDelayed(this, 600);
            }
        };
        petHandler.postDelayed(animRunnable, 600);

        // 拖动 + 点击（用 translation 平移，不改 layout params）
        final float[] downX = {0}, downY = {0};
        final float[] startTx = {0}, startTy = {0};
        final boolean[] isDragging = {false};

        petView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX[0] = event.getRawX();
                    downY[0] = event.getRawY();
                    startTx[0] = wrapper.getTranslationX();
                    startTy[0] = wrapper.getTranslationY();
                    isDragging[0] = false;
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX[0];
                    float dy = event.getRawY() - downY[0];
                    if (!isDragging[0] && (Math.abs(dx) > 15 || Math.abs(dy) > 15)) {
                        isDragging[0] = true;
                    }
                    if (isDragging[0]) {
                        wrapper.setTranslationX(startTx[0] + dx);
                        wrapper.setTranslationY(startTy[0] + dy);
                        return true;
                    }
                    return false;
                case android.view.MotionEvent.ACTION_UP:
                    if (!isDragging[0]) {
                        v.performClick();
                    } else {
                        // 拖动结束，保存位置
                        savePetPos(wrapper.getTranslationX(), wrapper.getTranslationY());
                    }
                    return true;
            }
            return false;
        });

        petView.setOnClickListener(v -> showRobotChat(ctx, cl));

        // 用固定宽度 LayoutParams，不受状态气泡影响
        LinearLayout.LayoutParams petLp = new LinearLayout.LayoutParams(fixedWidth, LinearLayout.LayoutParams.WRAP_CONTENT);
        petLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        wrapper.addView(petView, petLp);

        // 恢复保存的位置
        float[] savedPos = loadPetPos();
        if (savedPos != null) {
            wrapper.setTranslationX(savedPos[0]);
            wrapper.setTranslationY(savedPos[1]);
        }

        if (parent instanceof FrameLayout) {
            parent.addView(wrapper);
        } else if (parent instanceof ViewGroup) {
            FrameLayout container = new FrameLayout(ctx);
            container.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            container.addView(wrapper);
            parent.addView(container);
        }
    }

    // 更新宠物状态显示
    private static void updatePetStatus(String text, int mode) {
        petStatusText = text;
        petStatusMode = mode;
        petHandler.post(() -> {
            // 权限提示 → 只更新权限气泡，隐藏状态气泡避免重复
            if (mode == 3 && !text.isEmpty()) {
                pendingPermissionText = text;
                if (petPermView != null) {
                    petPermView.setText(text);
                    petPermView.setVisibility(View.VISIBLE);
                }
                if (petStatusView != null) petStatusView.setVisibility(View.GONE);
                return;
            }
            // 普通状态 → 状态气泡
            if (petStatusView != null) {
                if (text.isEmpty()) {
                    petStatusView.setVisibility(View.GONE);
                } else {
                    petStatusView.setText(text);
                    petStatusView.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    // 清除权限气泡
    private static void clearPermView() {
        petHandler.post(() -> {
            if (petPermView != null) petPermView.setVisibility(View.GONE);
        });
    }

    // 添加动作气泡按钮
    private static void addActionBubble(Context ctx, LinearLayout container, String text, int color, Runnable action) {
        TextView bubble = new TextView(ctx);
        bubble.setText(text);
        bubble.setTypeface(android.graphics.Typeface.MONOSPACE);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        bubble.setTextColor(0xFFFFFFFF);
        bubble.setGravity(android.view.Gravity.CENTER);
        int p = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6, ctx.getResources().getDisplayMetrics());
        bubble.setPadding(p * 2, p, p * 2, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, ctx.getResources().getDisplayMetrics()));
        bg.setColor(0xDD000000);
        bg.setStroke(1, color);
        bubble.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2, ctx.getResources().getDisplayMetrics());
        bubble.setOnClickListener(v -> action.run());
        container.addView(bubble, lp);
    }

    // 发送权限响应
    private static void sendPermissionResponse(String requestId, boolean allowed) {
        cchExecutor.execute(() -> {
            try {
                if (cchSocket != null && !cchSocket.isClosed() && cchOut != null) {
                    String msg = "{\"type\":\"permission_response\",\"requestId\":\"" + requestId + "\",\"allowed\":" + allowed + "}";
                    byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_8);
                    byte[] mask = new byte[4];
                    new SecureRandom().nextBytes(mask);
                    byte[] frame = new byte[msgBytes.length + 6];
                    frame[0] = (byte) 0x81;
                    frame[1] = (byte) (msgBytes.length | 0x80);
                    System.arraycopy(mask, 0, frame, 2, 4);
                    for (int i = 0; i < msgBytes.length; i++)
                        frame[i + 6] = (byte) (msgBytes[i] ^ mask[i % 4]);
                    synchronized (cchOut) { cchOut.write(frame); cchOut.flush(); }
                    log("PERM_SENT id=" + requestId + " allowed=" + allowed);
                    pendingPermissionId = null;
                    pendingPermissionText = "";
                    clearPermView();
                    // 清除权限状态显示
                    if (allowed) {
                        updatePetStatus("✅ 已授权", 0);
                    } else {
                        updatePetStatus("❌ 已拒绝", 4);
                    }
                }
            } catch (Exception e) {
                log("PERM_ERR=" + e.getMessage());
            }
        });
    }

    // Claude Code 服务器配置
    private static String cchServerUrl = "https://cc.haha";
    private static String cchH5Token = "atom_qwMo0RN79sejN3ZDQo6mFYoEhsRwsC5jvXlRI9giUX5-_q2eUOul-FdqzVVP7CnSSwvJQx-eGS6wRG3Z2oXNIA";

    // 自动打卡配置
    private static String CHECKIN_FILE = "/data/local/tmp/wps-miuix-checkin.txt";
    private static String CHECKIN_LOG_FILE = "/data/local/tmp/wps-miuix-checkin-log.txt";
    private static String FORM_URL_FILE = "/data/local/tmp/wps-miuix-form-url.txt";
    // 动态表单 URL（从"学生打卡入口"跳转捕获，可跟随链接变化）
    private static volatile String CHECKIN_FORM_URL = "https://f.wps.cn/ksform/cw/w/Ds1zAQtq";
    // 手动锁定表单链接：为 true 时 WebView 自动捕获不再覆盖（用户在打卡设置里手动填写）
    private static volatile boolean checkinManualFormUrl = false;
    private static volatile boolean checkinEnabled = false; // 实际自动打卡调度开关（由配置文件控制）
    private static volatile int checkinHour = 8;        // 打卡小时 (0-23)
    private static volatile int checkinMinute = 0;       // 打卡分钟
    private static volatile boolean checkinWeekly = false; // false=每天, true=每周一
    private static volatile double checkinLat = 23.1317;   // 默认: 五邑大学纬度
    private static volatile double checkinLng = 113.1085;  // 默认: 五邑大学经度
    private static volatile String checkinLocationName = "广东省江门市五邑大学";
    private static volatile String checkinCustomPresets = ""; // 自定义预设: "name|lat|lng;name|lat|lng"
    private static volatile boolean checkinUseRealGps = false; // 使用真实GPS，不伪装
    private static volatile int checkinRetryCount = 0; // 自动重试次数
    private static volatile boolean checkinSpoofing = false; // 定位注入中
    private static volatile boolean checkinSubmitted = false; // 本次打卡已提交
    private static volatile boolean refreshingCookies = false; // 正在刷新 Cookie 中
    private static volatile boolean firstSetupPending = false; // 首次配置：捕获Cookie后自动打卡并关闭
    private static volatile boolean checkinHasRoot = false;  // 是否有root权限
    private static volatile boolean allowRootAccess = false; // 临时允许 su 调用（绕过自身隐藏hook）
    private static volatile String checkinApiEndpoint = "";  // 捕获的表单提交API
    private static volatile String checkinApiPayload = "";   // 捕获的提交数据模板
    private static volatile String capturedCookies = "";     // OkHttp 捕获的 Cookie
    private static volatile String capturedCsrf = "";        // OkHttp 捕获的 CSRF token
    private static volatile String checkinInputName = "";    // 表单预设姓名（preset key）
    private static volatile String checkinDepartment = "";   // 部门/院系（clockinDepartment），从历史记录自动提取
    private static volatile String checkinStudentId = "";    // 学号（clockinStudentId），从历史记录自动提取
    // 动态字段列表：从历史打卡记录反推表单打卡字段（逗号分隔，适配不同表单模板）
    private static volatile String checkinClockinFields = "";
    // 历史打卡值快照：最近一次 clockinInfoValue 的完整 JSON，未知字段值从这回填
    private static volatile String checkinClockinValues = "";

    private static void saveCheckinConfig() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(checkinEnabled).append("\n");
            sb.append(checkinHour).append("\n");
            sb.append(checkinMinute).append("\n");
            sb.append(checkinWeekly).append("\n");
            sb.append(checkinLat).append("\n");
            sb.append(checkinLng).append("\n");
            sb.append(checkinLocationName).append("\n");
            sb.append(checkinCustomPresets).append("\n");
            sb.append(checkinUseRealGps).append("\n");
            sb.append(checkinApiEndpoint).append("\n");
            sb.append(checkinApiPayload).append("\n");
            sb.append(checkinInputName).append("\n");
            sb.append(checkinDepartment).append("\n");
            sb.append(checkinStudentId).append("\n");
            sb.append(checkinManualFormUrl).append("\n");
            sb.append(checkinClockinFields).append("\n");
            sb.append(checkinClockinValues).append("\n");
            allowRootAccess = true;
            saveRootFile(CHECKIN_FILE, sb.toString());
            allowRootAccess = false;
            log("CHECKIN_SAVED");
            // 保存后自动重新注册定时器
            cancelCheckin();
            if (checkinEnabled) scheduleCheckin(null);
            if (appContext != null) {
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(appContext, "✅ 配置已保存", Toast.LENGTH_SHORT).show()
                );
            }
        } catch (Throwable t) {
            allowRootAccess = false;
            log("CHECKIN_SAVE=" + t.getMessage());
            if (appContext != null) {
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(appContext, "❌ 保存失败: " + t.getMessage(), Toast.LENGTH_SHORT).show()
                );
            }
        }
    }

    private static void saveCheckinRecord() {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA);
            String time = sdf.format(new java.util.Date());
            String mode = checkinUseRealGps ? "GPS" : "伪装(" + checkinLat + "," + checkinLng + ")";
            String entry = time + " | " + mode + " | " + checkinLocationName + "\n";
            java.io.FileWriter fw = new java.io.FileWriter(CHECKIN_LOG_FILE, true);
            fw.write(entry);
            fw.close();
            log("CHECKIN_RECORD saved");
        } catch (Throwable t) { log("CHECKIN_RECORD=" + t.getMessage()); }
    }

    private static void loadCheckinConfig() {
        try {
            File f = new File(CHECKIN_FILE);
            if (!f.exists()) return;
            BufferedReader br = new BufferedReader(new java.io.FileReader(f));
            checkinEnabled = Boolean.parseBoolean(br.readLine());
            checkinHour = Integer.parseInt(br.readLine());
            checkinMinute = Integer.parseInt(br.readLine());
            checkinWeekly = Boolean.parseBoolean(br.readLine());
            checkinLat = Double.parseDouble(br.readLine());
            checkinLng = Double.parseDouble(br.readLine());
            String name = br.readLine();
            if (name != null && !name.isEmpty()) checkinLocationName = name;
            String presets = br.readLine();
            if (presets != null && !presets.isEmpty()) checkinCustomPresets = presets;
            String useReal = br.readLine();
            if (useReal != null) checkinUseRealGps = Boolean.parseBoolean(useReal);
            String apiEp = br.readLine();
            if (apiEp != null && !apiEp.isEmpty()) checkinApiEndpoint = apiEp;
            String apiPl = br.readLine();
            if (apiPl != null && !apiPl.isEmpty()) checkinApiPayload = apiPl;
            String inName = br.readLine();
            if (inName != null && !inName.isEmpty()) checkinInputName = inName;
            String dept = br.readLine();
            if (dept != null && !dept.isEmpty()) checkinDepartment = dept;
            String sid = br.readLine();
            if (sid != null && !sid.isEmpty()) checkinStudentId = sid;
            String manualUrl = br.readLine();
            if (manualUrl != null && !manualUrl.isEmpty()) checkinManualFormUrl = Boolean.parseBoolean(manualUrl);
            String fields = br.readLine();
            if (fields != null && !fields.isEmpty()) checkinClockinFields = fields;
            String values = br.readLine();
            if (values != null && !values.isEmpty()) checkinClockinValues = values;
            br.close();
            log("CHECKIN_LOADED enabled=" + checkinEnabled + " dept=" + checkinDepartment + " sid=" + checkinStudentId);
        } catch (Throwable t) { log("CHECKIN_LOAD=" + t.getMessage()); }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static String bytesToHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            hex[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            hex[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(hex);
    }

    // 配置群聊监控
    private void showRobotChat(Context ctx, ClassLoader cl) {
        try {
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle("Claude连接配置");

            LinearLayout layout = new LinearLayout(ctx);
            layout.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, ctx.getResources().getDisplayMetrics());
            layout.setPadding(pad, pad, pad, pad);

            TextView l1 = new TextView(ctx);
            l1.setText("Webhook地址：");
            l1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            layout.addView(l1);

            EditText webhookInput = new EditText(ctx);
            webhookInput.setHint("https://woa.wps.cn/api/v1/webhook/send?key=...");
            webhookInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            webhookInput.setSingleLine(true);
            // 显示已保存的值
            if (WEBHOOK_URL != null && !WEBHOOK_URL.isEmpty()) webhookInput.setText(WEBHOOK_URL);
            webhookInput.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            layout.addView(webhookInput);

            TextView l2 = new TextView(ctx);
            l2.setText("H5地址：");
            l2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            l2.setPadding(0, pad, 0, 0);
            layout.addView(l2);

            EditText h5Input = new EditText(ctx);
            h5Input.setHint("http://h5.wps.cn/cc_atom/s/?serverUrl=...&h5Token=...");
            h5Input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            h5Input.setSingleLine(false);
            h5Input.setMinLines(2);
            // 显示已保存的服务器地址
            if (cchServerUrl != null && !cchServerUrl.isEmpty()) h5Input.setText(cchServerUrl);
            h5Input.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            layout.addView(h5Input);

            // 保存按钮
            Button saveBtn = new Button(ctx);
            saveBtn.setText("保存");
            saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            btnLp.topMargin = pad;
            layout.addView(saveBtn, btnLp);

            builder.setView(layout);
            android.app.AlertDialog dialog = builder.create();

            saveBtn.setOnClickListener(v -> {
                String wh = webhookInput.getText().toString().trim();
                String h5 = h5Input.getText().toString().trim();
                if (!wh.isEmpty() && wh.startsWith("http")) WEBHOOK_URL = wh;
                if (h5.contains("serverUrl=") || h5.contains("h5Token=")) {
                    try {
                        String q = h5; int qi = h5.indexOf("?"); if (qi >= 0) q = h5.substring(qi + 1);
                        for (String p : q.split("&")) {
                            String[] kv = p.split("=", 2);
                            if (kv.length == 2) {
                                String val = java.net.URLDecoder.decode(kv[1], "UTF-8");
                                if ("serverUrl".equals(kv[0])) cchServerUrl = val;
                                else if ("h5Token".equals(kv[0])) cchH5Token = val;
                            }
                        }
                    } catch (Throwable ignored) {}
                } else if (!h5.isEmpty() && h5.startsWith("http")) {
                    // 直接输入服务器地址
                    cchServerUrl = h5.endsWith("/") ? h5.substring(0, h5.length() - 1) : h5;
                }
                cchSessionId = null;
                try { new File(SESSION_FILE).delete(); } catch (Throwable ignored) {}
                if (cchSocket != null) { try { cchSocket.close(); } catch (Throwable ignored) {} cchSocket = null; }
                saveConfig();
                log("CONFIGURED server=" + cchServerUrl);
                Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });

            dialog.show();
        } catch (Throwable t) {
            log("CONFIG_DIALOG_ERR=" + t.getMessage());
        }
    }

    private static void addLabel(LinearLayout layout, String text, int sp) {
        TextView tv = new TextView(layout.getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (layout.getChildCount() > 0) {
            int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, layout.getContext().getResources().getDisplayMetrics());
            tv.setPadding(0, pad, 0, 0);
        }
        layout.addView(tv);
    }

    private static EditText addInput(LinearLayout layout, String hint, boolean singleLine) {
        EditText et = new EditText(layout.getContext());
        et.setHint(hint);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        et.setSingleLine(singleLine);
        if (!singleLine) et.setMinLines(2);
        et.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        layout.addView(et);
        return et;
    }

    private static void paste(Context ctx, EditText target) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
                if (text != null) target.setText(text);
            }
        } catch (Throwable ignored) {}
    }

    // 解析 H5 链接并连接
    private void parseAndConnect(String url, Context ctx) {
        try {
            // 解析 serverUrl 和 h5Token
            if (url.contains("serverUrl=") && url.contains("h5Token=")) {
                java.net.URI uri = new java.net.URI(url);
                String query = uri.getQuery();
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] kv = param.split("=", 2);
                        if (kv.length == 2) {
                            String key = kv[0];
                            String value = java.net.URLDecoder.decode(kv[1], "UTF-8");
                            if ("serverUrl".equals(key)) {
                                cchServerUrl = value;
                            } else if ("h5Token".equals(key)) {
                                cchH5Token = value;
                            }
                        }
                    }
                }
            } else {
                // 直接作为服务器地址
                cchServerUrl = url;
            }

            log("CONFIGURED server=" + cchServerUrl);
            Toast.makeText(ctx, "已配置: " + cchServerUrl, Toast.LENGTH_SHORT).show();

            // 测试连接
            testConnection(ctx);

        } catch (Throwable t) {
            log("PARSE_ERR=" + t.getMessage());
            Toast.makeText(ctx, "解析失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // 测试连接
    private void testConnection(Context ctx) {
        new Thread(() -> {
            try {
                String resp = httpGet("/api/status");
                if (resp.contains("\"ok\"") || resp.contains("\"status\"")) {
                    log("CONNECT_OK");
                    new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(ctx, "连接成功！", Toast.LENGTH_SHORT).show());
                } else {
                    log("CONNECT_FAIL resp=" + resp.substring(0, Math.min(50, resp.length())));
                    new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(ctx, "连接失败: " + resp, Toast.LENGTH_LONG).show());
                }
            } catch (Throwable t) {
                log("CONNECT_ERR=" + t.getMessage());
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(ctx, "连接错误: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // 异步回复缓冲区
    private static volatile StringBuilder asyncResponse = null;
    private static volatile boolean asyncCollecting = false;
    private static WebhookCallback asyncCallback = null;
    // REST 轮询控制（用于实时显示思考过程）
    private static volatile boolean restPollingActive = false;
    private static volatile int lastPolledLen = 0;

    // 发送消息到 Claude Code 服务器（异步模式：发完不等，后台收回复）
    private void sendToCCH(String message, ClassLoader cl, WebhookCallback callback) {
        cchExecutor.execute(() -> {
            try {
                log("SEND_TO_CCH server=" + cchServerUrl + " session=" + cchSessionId);
                // 确保 WebSocket 连接
                if (cchSocket == null || cchSocket.isClosed() || cchOut == null) {
                    if (cchSessionId != null) {
                        log("WS_RECONNECT session=" + cchSessionId);
                        if (!connectWS()) {
                            log("WS_RECONNECT_FAILED");
                            cchSessionId = null;
                        }
                    }
                    if (cchSessionId == null) {
                        String resp = httpPost("/api/sessions",
                            "{\"message\":\"" + escapeJson(message) + "\"}");
                        log("CCH_RESP=" + (resp.isEmpty() ? "empty" : resp.substring(0, Math.min(80, resp.length()))));
                        String sessionId = jsonStr(resp, "sessionId");
                        if (sessionId.isEmpty()) {
                            if (callback != null) callback.onResult("创建会话失败: " + resp);
                            return;
                        }
                        cchSessionId = sessionId;
                        saveSessionId();
                        log("NEW_SESSION=" + sessionId);
                        if (!connectWS()) {
                            if (callback != null) callback.onResult("WS连接失败");
                            return;
                        }
                    }
                }
                // 设置异步收集器（WebSocket message_complete 触发最终回调）
                asyncResponse = new StringBuilder();
                asyncCallback = callback;
                asyncCollecting = true;
                lastPolledLen = 0;
                // 发送消息
                sendWSMessageAsync(message);
                log("MSG_SENT_ASYNC");
                // 启动 REST 轮询，在状态气泡显示思考过程
                startThinkingPoll();
            } catch (Exception e) {
                log("CCH_ERR=" + e.getMessage());
                asyncCollecting = false;
                restPollingActive = false;
                if (callback != null) callback.onResult("请求失败: " + e.getMessage());
            }
        });
    }

    // REST 轮询：定时拉取最新回复内容，在宠物上方显示思考过程
    private void startThinkingPoll() {
        restPollingActive = true;
        new Thread(() -> {
            try {
                // 获取基线
                String msgsJson = httpGet("/api/sessions/" + cchSessionId + "/messages");
                int baselineCount = countMessages(msgsJson);
                log("THINK_POLL baseline=" + baselineCount);
                int maxIter = 180;
                int iter = 0;
                while (restPollingActive && asyncCollecting && iter < maxIter) {
                    Thread.sleep(2000);
                    iter++;
                    try {
                        String curJson = httpGet("/api/sessions/" + cchSessionId + "/messages");
                        int curCount = countMessages(curJson);
                        if (curCount > baselineCount) {
                            // 有新的 assistant 内容，提取并显示
                            String partial = extractLastAssistantContent(curJson);
                            if (partial != null && partial.length() > lastPolledLen) {
                                // 显示最新一段内容（截取末尾）
                                String display = partial.length() > 80
                                    ? "..." + partial.substring(partial.length() - 80)
                                    : partial;
                                // 取最后一行作为状态显示
                                String[] lines = partial.split("\n");
                                String lastLine = lines[lines.length - 1].trim();
                                if (!lastLine.isEmpty()) {
                                    String statusDisplay = lastLine.length() > 30
                                        ? lastLine.substring(lastLine.length() - 30)
                                        : lastLine;
                                    updatePetStatus("📝 " + statusDisplay, 1);
                                }
                                lastPolledLen = partial.length();
                            }
                        }
                    } catch (Exception e) {
                        log("THINK_POLL_ERR=" + e.getMessage());
                    }
                }
            } catch (Exception e) {
                log("THINK_POLL_ERR=" + e.getMessage());
            } finally {
                restPollingActive = false;
            }
        }, "CCH-THINK").start();
    }

    // 计算消息数组中的 role 数量
    private static int countMessages(String json) {
        if (json == null || json.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = json.indexOf("\"role\":", idx)) >= 0) { count++; idx += 7; }
        return count;
    }

    // 提取最后一条 assistant 消息的完整文本（含中间过程）
    private static String extractLastAssistantContent(String json) {
        if (json == null || json.isEmpty()) return null;
        int lastAsst = json.lastIndexOf("\"role\":\"assistant\"");
        if (lastAsst < 0) lastAsst = json.lastIndexOf("\"role\": \"assistant\"");
        if (lastAsst < 0) return null;
        int ci = json.indexOf("\"content\":", lastAsst);
        if (ci < 0) return null;
        int colon = ci + 10;
        while (colon < json.length() && json.charAt(colon) == ' ') colon++;
        if (colon >= json.length()) return null;
        if (json.charAt(colon) == '"') {
            int s = colon + 1, e = json.indexOf("\"", s);
            if (e > s) return json.substring(s, e).replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\").replace("\\\"", "\"");
        } else if (json.charAt(colon) == '[') {
            StringBuilder sb = new StringBuilder();
            int from = colon + 1;
            int arrEnd = json.indexOf("]", colon);
            while (true) {
                int ti = json.indexOf("\"text\":\"", from);
                if (ti < 0 || (arrEnd >= 0 && ti > arrEnd)) break;
                int s = ti + 8, e = json.indexOf("\"", s);
                if (e > s) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(json.substring(s, e).replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\").replace("\\\"", "\""));
                }
                from = e + 1;
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
        return null;
    }

    // 异步发送 WebSocket 消息（不等回复）
    private void sendWSMessageAsync(String message) {
        try {
            String msgPayload = "{\"type\":\"user_message\",\"content\":\"" +
                escapeJson(message) + "\"}";
            byte[] msgBytes = msgPayload.getBytes(StandardCharsets.UTF_8);
            byte[] mask = new byte[4];
            new SecureRandom().nextBytes(mask);

            byte[] frame;
            if (msgBytes.length < 126) {
                frame = new byte[msgBytes.length + 6];
                frame[0] = (byte) 0x81;
                frame[1] = (byte) (msgBytes.length | 0x80);
                System.arraycopy(mask, 0, frame, 2, 4);
                for (int i = 0; i < msgBytes.length; i++)
                    frame[i + 6] = (byte) (msgBytes[i] ^ mask[i % 4]);
            } else {
                frame = new byte[msgBytes.length + 8];
                frame[0] = (byte) 0x81;
                frame[1] = (byte) (126 | 0x80);
                frame[2] = (byte) ((msgBytes.length >> 8) & 0xFF);
                frame[3] = (byte) (msgBytes.length & 0xFF);
                System.arraycopy(mask, 0, frame, 4, 4);
                for (int i = 0; i < msgBytes.length; i++)
                    frame[i + 8] = (byte) (msgBytes[i] ^ mask[i % 4]);
            }
            synchronized (cchOut) {
                cchOut.write(frame);
                cchOut.flush();
            }
            log("WS_SENT_ASYNC len=" + msgBytes.length);
        } catch (Exception e) {
            log("WS_SEND_ASYNC_ERR=" + e.getMessage());
            try { cchSocket.close(); } catch (Throwable ignored) {}
            cchSocket = null;
        }
    }

    // WebSocket 连接
    private boolean connectWS() {
        try {
            boolean isSecure = cchServerUrl.startsWith("https");
            URI uri = new URI(cchServerUrl.replace("https", "wss").replace("http", "ws") + "/ws/" + cchSessionId + "?token=" + cchH5Token);
            String host = uri.getHost();
            int port = uri.getPort();
            if (port < 0) port = isSecure ? 443 : 80;
            String path = uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");

            // 支持 HTTP 和 HTTPS
            if (isSecure) {
                javax.net.ssl.SSLSocketFactory factory = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
                cchSocket = factory.createSocket(host, port);
            } else {
                cchSocket = new Socket();
                cchSocket.connect(new InetSocketAddress(host, port), 10000);
            }
            cchSocket.setSoTimeout(90000);
            cchOut = cchSocket.getOutputStream();
            final InputStream in = cchSocket.getInputStream();

            // WebSocket 握手
            byte[] keyBytes = new byte[16];
            new SecureRandom().nextBytes(keyBytes);
            String wsKey = Base64.getEncoder().encodeToString(keyBytes);
            String handshake = "GET " + path + " HTTP/1.1\r\n" +
                "Host: " + host + ((!isSecure && port != 80) || (isSecure && port != 443) ? ":" + port : "") + "\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + wsKey + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";
            cchOut.write(handshake.getBytes(StandardCharsets.US_ASCII));
            cchOut.flush();

            // 读取握手响应
            StringBuilder hdr = new StringBuilder();
            int p = 0, p2 = 0;
            while (true) {
                int b = in.read();
                if (b < 0) { log("WS_EOF"); cchSocket.close(); return false; }
                hdr.append((char) b);
                if (p2 == 13 && p == 10 && b == 13) {
                    in.read(); // 消费最后的 \n
                    if (!hdr.toString().contains("101")) {
                        log("WS_FAIL hdr=" + hdr);
                        cchSocket.close();
                        return false;
                    }
                    break;
                }
                p2 = p; p = b;
            }
            log("WS_CONNECTED");

            // 启动读取线程
            new Thread(() -> wsReadLoop(in), "CCH-WS").start();
            return true;
        } catch (Exception e) {
            log("WS_CONNECT_ERR=" + e.getMessage());
            try { if (cchSocket != null) cchSocket.close(); } catch (Throwable ignored) {}
            cchSocket = null;
            return false;
        }
    }

    // WebSocket 读取循环
    private void wsReadLoop(InputStream in) {
        if (in == null) { log("WS_READ_LOOP in=null"); return; }
        int n;
        log("WS_READ_LOOP started");
        while (cchSocket != null && !cchSocket.isClosed()) {
            try {
                int b1 = in.read();
                if (b1 < 0) { log("WS_READ b1=-1 EOF"); break; }
                int b2 = in.read();
                if (b2 < 0) { log("WS_READ b2=-1 EOF"); break; }

                int opcode = b1 & 0x0F;
                long payloadLen = b2 & 0x7F;
                if (payloadLen == 126) {
                    int hi = in.read(), lo = in.read();
                    payloadLen = (hi << 8) | lo;
                } else if (payloadLen == 127) {
                    byte[] lb = new byte[8];
                    in.read(lb);
                    payloadLen = 0;
                    for (int i = 0; i < 8; i++)
                        payloadLen = (payloadLen << 8) | (lb[i] & 0xFF);
                }
                // 服务器帧不 mask，跳过 mask 字节（如果有）
                if ((b2 & 0x80) != 0) { byte[] m = new byte[4]; in.read(m); }

                byte[] payload = new byte[(int) payloadLen];
                int rd = 0;
                while (rd < payload.length && (n = in.read(payload, rd, payload.length - rd)) >= 0)
                    rd += n;

                if (opcode == 1) { // text
                    String msg = new String(payload, StandardCharsets.UTF_8);
                    log("WS_RECV op=1 len=" + payloadLen + " msg=" + msg.substring(0, Math.min(80, msg.length())));
                    if (msg.contains("\"type\":\"content_delta\"")) {
                        // 有 text 字段的才是回复内容（toolInput 是工具调用，忽略）
                        int ti = msg.indexOf("\"text\":\"");
                        if (ti >= 0) {
                            int s = ti + 8;
                            int e = msg.indexOf("\"", s);
                            if (e > s) {
                                String delta = msg.substring(s, e)
                                    .replace("\\n", "\n").replace("\\t", "\t")
                                    .replace("\\\\", "\\").replace("\\\"", "\"");
                                // 同步模式
                                if (currentResponse != null) currentResponse.append(delta);
                                // 异步模式
                                if (asyncCollecting && asyncResponse != null) asyncResponse.append(delta);
                            }
                        }
                    } else if (msg.contains("\"type\":\"message_complete\"")) {
                        // 同步模式
                        if (currentLatch != null) currentLatch.countDown();
                        // 异步模式：有实际文本内容才发送
                        if (asyncCollecting && asyncResponse != null) {
                            String fullResponse = asyncResponse.toString().trim();
                            if (!fullResponse.isEmpty()) {
                                asyncCollecting = false;
                                restPollingActive = false;
                                log("ASYNC_REPLY len=" + fullResponse.length());
                                if (asyncCallback != null) asyncCallback.onResult(fullResponse);
                                asyncResponse = null;
                                asyncCallback = null;
                            }
                            // 回复为空说明是桌面端的 turn 结束，不是手机的，继续等
                        }
                        updatePetStatus("", 0);
                    } else if (msg.contains("\"type\":\"error\"")) {
                        String errMsg = jsonStr(msg, "message");
                        updatePetStatus("❌ " + (errMsg.isEmpty() ? "错误" : errMsg.substring(0, Math.min(20, errMsg.length()))), 4);
                        // 同步模式
                        if (currentResponse != null) currentResponse.append("\n[错误] ").append(msg);
                        if (currentLatch != null) currentLatch.countDown();
                        // 异步模式
                        if (asyncCollecting) {
                            asyncCollecting = false;
                            if (asyncCallback != null) asyncCallback.onResult("错误: " + errMsg);
                            asyncResponse = null;
                            asyncCallback = null;
                        }
                    } else if (msg.contains("\"type\":\"status\"")) {
                        String state = jsonStr(msg, "state");
                        String verb = jsonStr(msg, "verb");
                        if ("thinking".equals(state)) {
                            updatePetStatus("🤔 " + (verb.isEmpty() ? "思考中..." : verb), 1);
                        } else if ("tool_executing".equals(state)) {
                            updatePetStatus("🔧 " + (verb.isEmpty() ? "执行工具..." : verb), 2);
                        } else if ("streaming".equals(state)) {
                            updatePetStatus("📝 生成中...", 1);
                        } else if ("permission_pending".equals(state)) {
                            updatePetStatus("🔑 等待授权", 3);
                        } else if ("compacting".equals(state)) {
                            updatePetStatus("📦 压缩上下文...", 1);
                        } else if ("idle".equals(state)) {
                            updatePetStatus("", 0);
                        }
                    } else if (msg.contains("\"type\":\"permission_request\"")) {
                        String toolName = jsonStr(msg, "toolName");
                        String desc = jsonStr(msg, "description");
                        String reqId = jsonStr(msg, "requestId");
                        String display = toolName.isEmpty() ? desc : toolName;
                        // 自动批准模式
                        if (autoApprovePerm) {
                            if (!reqId.isEmpty()) {
                                sendPermissionResponse(reqId, true);
                                log("AUTO_PERMIT id=" + reqId + " tool=" + toolName);
                            }
                            updatePetStatus("✅ 自动批准: " + (display.isEmpty() ? "操作" : display.substring(0, Math.min(15, display.length()))), 0);
                        } else {
                        // 手动确认模式
                        if (!reqId.isEmpty()) pendingPermissionId = reqId;
                        updatePetStatus("🔑 " + (display.isEmpty() ? "需要授权" : display.substring(0, Math.min(20, display.length()))), 3);
                        // 在群聊中发送授权通知
                        String permMsg = "\u200B🔑 需要授权: " + (display.isEmpty() ? "未知操作" : display);
                        if (!desc.isEmpty() && !desc.equals(toolName)) {
                            permMsg += "\n" + desc;
                        }
                        permMsg += "\n点击宠物上方气泡允许/拒绝";
                        sendWebhookMessage(permMsg, null);
                        } // end else (手动确认)
                    } else if (msg.contains("\"type\":\"content_start\"")) {
                        if (msg.contains("\"blockType\":\"tool_use\"")) {
                            String toolName = jsonStr(msg, "toolName");
                            updatePetStatus("🔧 " + (toolName.isEmpty() ? "使用工具" : toolName), 2);
                        }
                    }
                } else if (opcode == 8) {
                    break;
                } else if (opcode == 9) { // ping -> pong
                    byte[] pong = new byte[payload.length + 2];
                    pong[0] = (byte) 0x8A;
                    pong[1] = (byte) payload.length;
                    System.arraycopy(payload, 0, pong, 2, payload.length);
                    synchronized (cchOut) { cchOut.write(pong); cchOut.flush(); }
                }
            } catch (Throwable t) {
                log("WS_READ_ERR=" + t.getMessage());
            }
        }
        if (currentLatch != null) currentLatch.countDown();
        try { cchSocket.close(); } catch (Throwable ignored) {}
        cchSocket = null;
        log("WS_DISCONNECTED");
    }

    // 通过 WebSocket 发送消息并等待回复
    private String sendWSMessage(String message, int timeoutSecs) {
        StringBuilder response = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        currentResponse = response;
        currentLatch = latch;
        try {
            String msgPayload = "{\"type\":\"user_message\",\"content\":\"" +
                escapeJson(message) + "\"}";
            byte[] msgBytes = msgPayload.getBytes(StandardCharsets.UTF_8);
            byte[] mask = new byte[4];
            new SecureRandom().nextBytes(mask);

            byte[] frame;
            if (msgBytes.length < 126) {
                frame = new byte[msgBytes.length + 6];
                frame[0] = (byte) 0x81; // FIN + text
                frame[1] = (byte) (msgBytes.length | 0x80); // masked
                System.arraycopy(mask, 0, frame, 2, 4);
                for (int i = 0; i < msgBytes.length; i++)
                    frame[i + 6] = (byte) (msgBytes[i] ^ mask[i % 4]);
            } else {
                frame = new byte[msgBytes.length + 8];
                frame[0] = (byte) 0x81;
                frame[1] = (byte) (126 | 0x80);
                frame[2] = (byte) ((msgBytes.length >> 8) & 0xFF);
                frame[3] = (byte) (msgBytes.length & 0xFF);
                System.arraycopy(mask, 0, frame, 4, 4);
                for (int i = 0; i < msgBytes.length; i++)
                    frame[i + 8] = (byte) (msgBytes[i] ^ mask[i % 4]);
            }

            synchronized (cchOut) {
                cchOut.write(frame);
                cchOut.flush();
            }
            log("WS_SENT len=" + msgBytes.length);

            boolean completed = latch.await(timeoutSecs, TimeUnit.SECONDS);
            if (!completed) log("CCH_TIMEOUT " + timeoutSecs + "s");
        } catch (Exception e) {
            log("WS_SEND_ERR=" + e.getMessage());
            try { cchSocket.close(); } catch (Throwable ignored) {}
            cchSocket = null;
        }
        currentResponse = null;
        currentLatch = null;
        return response.toString();
    }


    // 发送 webhook 消息
    private void sendWebhookMessage(String content, WebhookCallback callback) {
        webhookExecutor.execute(() -> {
            try {
                URL url = new URL(WEBHOOK_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                String json = "{\"msgtype\":\"text\",\"text\":{\"content\":\"" + escapeJson(content) + "\"}}";
                try (OutputStream os = conn.getOutputStream()) { os.write(json.getBytes(StandardCharsets.UTF_8)); }

                int code = conn.getResponseCode();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                        code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = br.readLine()) != null) sb.append(line);
                }
                conn.disconnect();

                String response = code == 200 ? "已发送" : "发送失败 (" + code + ")";
                if (callback != null) callback.onResult(response);
            } catch (Exception e) {
                log("WEBHOOK_ERR=" + e.getMessage());
                if (callback != null) callback.onResult("发送失败: " + e.getMessage());
            }
        });
    }

    // ── CCH API 通用 HTTP 工具 ──────────────────────────────────

    private static String httpGet(String path) {
        try {
            java.net.URL url = new java.net.URL(cchServerUrl + path);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + cchH5Token);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private static String httpPost(String path, String json) {
        try {
            java.net.URL url = new java.net.URL(cchServerUrl + path);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + cchH5Token);
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            if (json != null) {
                try (OutputStream os = conn.getOutputStream()) { os.write(json.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = conn.getResponseCode();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    // POST 到完整 URL（用于同步服务等外部 API）

    private static String httpPut(String path, String json) {
        try {
            java.net.URL url = new java.net.URL(cchServerUrl + path);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + cchH5Token);
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            if (json != null) {
                try (OutputStream os = conn.getOutputStream()) { os.write(json.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = conn.getResponseCode();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    // JSON 简单提取工具
    private static String jsonStr(String json, String key) {
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return "";
        i += k.length();
        // 跳过冒号和空格
        while (i < json.length() && (json.charAt(i) == ':' || json.charAt(i) == ' ')) i++;
        if (i >= json.length() || json.charAt(i) != '"') return "";
        i++; // 跳过开头引号
        int e = json.indexOf("\"", i);
        if (e < 0) return "";
        return json.substring(i, e);
    }

    private static int jsonInt(String json, String key) {
        String k = "\"" + key + "\":";
        int i = json.indexOf(k);
        if (i < 0) return -1;
        i += k.length();
        int e = i;
        while (e < json.length() && (Character.isDigit(json.charAt(e)) || json.charAt(e) == '-')) e++;
        try { return Integer.parseInt(json.substring(i, e)); } catch (Throwable t) { return -1; }
    }

    // ── CCH API 端点 ──────────────────────────────────────────

    /** GET /api/status — 服务器状态 */
    private static String cchGetStatus() {
        return httpGet("/api/status");
    }

    /** GET /api/sessions — 列出所有会话 */
    private static String cchListSessions() {
        // 优先使用 mobile API
        try {
            String resp = httpGet("/api/mobile/sessions?limit=50");
            if (!resp.contains("error")) return resp;
        } catch (Exception e) {
            log("MOBILE_LIST_ERR=" + e.getMessage());
        }
        // 回退到原 API
        return httpGet("/api/sessions");
    }

    /** POST /api/sessions — 创建新会话 */
    private static String cchCreateSession(String message) {
        // 优先使用 mobile API
        try {
            String body = "{\"title\":\"" + escapeJson(message.substring(0, Math.min(50, message.length()))) + "\"}";
            String resp = httpPost("/api/mobile/sessions", body);
            if (!resp.contains("error")) return resp;
        } catch (Exception e) {
            log("MOBILE_CREATE_ERR=" + e.getMessage());
        }
        // 回退到原 API
        String json = "{\"message\":\"" + escapeJson(message) + "\"}";
        return httpPost("/api/sessions", json);
    }

    /** GET /api/sessions/{id} — 获取会话详情+消息 */
    private static String cchGetSession(String sessionId) {
        // 优先使用 mobile API
        try {
            String resp = httpGet("/api/mobile/sessions/" + sessionId);
            if (!resp.contains("error")) return resp;
        } catch (Exception e) {
            log("MOBILE_SESSION_ERR=" + e.getMessage());
        }
        return httpGet("/api/sessions/" + sessionId);
    }

    /** GET /api/sessions/{id}/messages — 获取会话消息 */
    private static String cchGetMessages(String sessionId) {
        // 优先使用 mobile API
        try {
            String resp = httpGet("/api/mobile/sessions/" + sessionId + "/messages");
            if (!resp.contains("error")) return resp;
        } catch (Exception e) {
            log("MOBILE_MSG_ERR=" + e.getMessage());
        }
        return httpGet("/api/sessions/" + sessionId + "/messages");
    }

    /** GET /api/models — 可用模型列表 */
    private static String cchGetModels() {
        return httpGet("/api/models");
    }

    /** GET /api/providers — 模型供应商配置 */
    private static String cchGetProviders() {
        return httpGet("/api/providers");
    }

    /** GET /api/settings — 系统设置 */
    private static String cchGetSettings() {
        return httpGet("/api/settings");
    }

    // 格式化 API 响应为可读文本（用于聊天界面显示）
    private static String formatStatus(String json) {
        String status = jsonStr(json, "status");
        String version = jsonStr(json, "version");
        long uptime = jsonInt(json, "uptime");
        String upStr = uptime > 0 ? (uptime / 3600) + "h " + ((uptime % 3600) / 60) + "m" : "N/A";
        return "状态: " + status + "\n版本: " + version + "\n运行: " + upStr;
    }

    private static String formatSessions(String json) {
        StringBuilder sb = new StringBuilder();
        int total = jsonInt(json, "total");
        sb.append("共 ").append(total).append(" 个会话\n");
        sb.append("当前: ").append(cchSessionId != null ? cchSessionId.substring(0, 8) + "..." : "无").append("\n\n");
        int idx = 0;
        int seq = 0;
        while (true) {
            int si = json.indexOf("\"id\":\"", idx);
            if (si < 0) break;
            String id = jsonStr(json.substring(si), "id");
            int ti = json.indexOf("\"title\":\"", si);
            String title = "";
            if (ti >= 0 && ti < si + 800) {
                title = jsonStr(json.substring(ti), "title");
                if (title.length() > 35) title = title.substring(0, 35) + "...";
            }
            int mi = json.indexOf("\"messageCount\":", si);
            int msgCount = -1;
            if (mi >= 0 && mi < si + 800) msgCount = jsonInt(json.substring(mi), "messageCount");
            int ri = json.indexOf("\"runtimeModelId\":\"", si);
            String model = "";
            if (ri >= 0 && ri < si + 800) model = jsonStr(json.substring(ri), "runtimeModelId");
            seq++;
            boolean isCurrent = id.equals(cchSessionId);
            sb.append(isCurrent ? "→ " : "  ").append(seq).append(". ").append(title.isEmpty() ? "(无标题)" : title);
            if (msgCount >= 0) sb.append(" [").append(msgCount).append("条]");
            if (!model.isEmpty()) sb.append(" (").append(model).append(")");
            sb.append("\n   ID: ").append(id.substring(0, 8)).append("...");
            sb.append(isCurrent ? " ← 当前" : "").append("\n");
            idx = si + 10;
            if (sb.length() > 1500) { sb.append("...(更多省略)"); break; }
        }
        sb.append("\n切换: /switch <id前缀>");
        return sb.toString();
    }

    private static String formatModels(String json) {
        StringBuilder sb = new StringBuilder();
        String providerName = "";
        int pi = json.indexOf("\"name\":\"");
        if (pi >= 0) providerName = jsonStr(json.substring(pi), "name");
        sb.append("供应商: ").append(providerName).append("\n");
        int idx = 0;
        while (true) {
            int mi = json.indexOf("\"id\":\"", idx);
            if (mi < 0) break;
            String modelId = jsonStr(json.substring(mi), "id");
            sb.append("- ").append(modelId).append("\n");
            idx = mi + 10;
        }
        return sb.toString();
    }

    private static String formatProviders(String json) {
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (true) {
            int si = json.indexOf("\"name\":\"", idx);
            if (si < 0) break;
            String name = jsonStr(json.substring(si), "name");
            int bi = json.indexOf("\"baseUrl\":\"", si);
            String baseUrl = "";
            if (bi >= 0 && bi < si + 500) baseUrl = jsonStr(json.substring(bi), "baseUrl");
            int mi = json.indexOf("\"main\":\"", si);
            String mainModel = "";
            if (mi >= 0 && mi < si + 500) mainModel = jsonStr(json.substring(mi), "main");
            sb.append("- ").append(name).append("\n  模型: ").append(mainModel).append("\n  地址: ").append(baseUrl).append("\n");
            idx = si + 10;
        }
        return sb.toString();
    }

    private static String formatSettings(String json) {
        StringBuilder sb = new StringBuilder();
        String lang = jsonStr(json, "language");
        String mode = jsonStr(json, "defaultMode");
        sb.append("语言: ").append(lang).append("\n");
        sb.append("权限: ").append(mode).append("\n");
        int timeout = jsonInt(json, "aiRequestTimeoutMs");
        if (timeout > 0) sb.append("超时: ").append(timeout / 1000).append("s\n");
        // 插件
        int pi = json.indexOf("\"enabledPlugins\":{");
        if (pi >= 0) {
            sb.append("插件:\n");
            int idx = pi;
            while (true) {
                int qi = json.indexOf("\"@idx", idx);
                if (qi < 0) {
                    // 找下一个 "xxx":true
                    int next = json.indexOf("\":true", idx);
                    if (next < 0 || next > pi + 500) break;
                    int ks = json.lastIndexOf("\"", next - 1);
                    if (ks > pi) {
                        String plugin = json.substring(ks + 1, next);
                        sb.append("  - ").append(plugin).append("\n");
                    }
                    idx = next + 5;
                } else break;
            }
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }

    // 从 sessions JSON 中根据 id 前缀查找完整 id
    private static String findSessionId(String sessionsJson, String prefix) {
        String p = prefix.toLowerCase();
        int idx = 0;
        while (true) {
            int si = sessionsJson.indexOf("\"id\":\"", idx);
            if (si < 0) return "";
            String id = jsonStr(sessionsJson.substring(si), "id");
            if (id.toLowerCase().startsWith(p)) return id;
            idx = si + 10;
        }
    }

    // 从 sessions JSON 中根据完整 id 查找 title
    private static String findSessionTitle(String sessionsJson, String id) {
        int si = sessionsJson.indexOf("\"id\":\"" + id + "\"");
        if (si < 0) return "";
        int ti = sessionsJson.indexOf("\"title\":\"", si);
        if (ti < 0 || ti > si + 500) return "";
        return jsonStr(sessionsJson.substring(ti), "title");
    }

    private interface WebhookCallback {
        void onResult(String response);
    }

    private int sysA(Context c, boolean d) { if (Build.VERSION.SDK_INT < 31) return 0xFF888888; try { return c.getColor(d ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600); } catch (Throwable t) { return 0xFF888888; } }
    private static int blend(int fg, int bg, float a) { return Color.rgb((int)(Color.red(fg)*(1-a)+Color.red(bg)*a), (int)(Color.green(fg)*(1-a)+Color.green(bg)*a), (int)(Color.blue(fg)*(1-a)+Color.blue(bg)*a)); }
    private boolean dark(Context c) { return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }

    // ========== 自动打卡系统 ==========
    private static Context appContext = null;

    // 检测 root 权限（读取 MainActivity 写入的标记文件）
    private static void detectRoot() {
        try {
            File flag = new File("/data/local/tmp/wyu-monet-root");
            if (flag.exists()) {
                BufferedReader br = new BufferedReader(new java.io.FileReader(flag));
                String val = br.readLine();
                br.close();
                checkinHasRoot = "1".equals(val) || "granted".equals(val);
            } else {
                checkinHasRoot = false;
            }
            log("ROOT detect: " + checkinHasRoot + " (from flag file)");
        } catch (Throwable t) {
            checkinHasRoot = false;
            log("ROOT: " + t.getMessage());
        }
    }

    // 检测 root 权限（仅读取标记文件，不主动请求）
    private static void requestRoot(android.content.Context ctx) {
        detectRoot();
        if (checkinHasRoot) {
            log("ROOT: already granted");
        } else {
            log("ROOT: not granted (open WYU-Monet app to authorize)");
        }
    }

    // 打卡成功后清理：返回用户之前的 app，关闭 WPS（如果之前没在运行）
    private static void cleanupAfterCheckin() {
        new Thread(() -> {
            try {
                Thread.sleep(1000);
                // 如果启动前 WPS 没在运行，关掉它（无需 root，通过 Activity 回退）
                File wasRunning = new File("/data/local/tmp/wyu-was-running");
                if (wasRunning.exists()) {
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(wasRunning));
                    String val = r.readLine();
                    r.close();
                    wasRunning.delete();
                    if ("0".equals(val)) {
                        Thread.sleep(1000);
                        log("CLEANUP: WPS was not running");
                    }
                }
            } catch (Throwable t) { log("CLEANUP=" + t.getMessage()); }
        }).start();
    }

    // 用 root 唤醒屏幕 + 启动表单
    private static void doCheckin() {
        allowRootAccess = true;
        try {
            log("DO_CHECKIN start root=" + checkinHasRoot);

            // 先检查是否已打卡（每天 → 查今天；每周一 → 查本周一）
            String checkDate;
            if (checkinWeekly) {
                java.util.Calendar mon = java.util.Calendar.getInstance();
                mon.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY);
                checkDate = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(mon.getTime());
            } else {
                checkDate = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
            }
            try {
                java.io.File logFile = new java.io.File(CHECKIN_LOG_FILE);
                if (logFile.exists()) {
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(logFile));
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith(checkDate)) {
                            log("DO_CHECKIN skipped: already checked in (" + (checkinWeekly ? "this Monday" : "today") + ")");
                            br.close();
                            return;
                        }
                    }
                    br.close();
                }
            } catch (Throwable t) { log("DO_CHECKIN log check=" + t.getMessage()); }

            checkinSpoofing = true;
            checkinSubmitted = false;
            if (appContext == null) { log("DO_CHECKIN no context"); return; }

            // 尝试 API 静默打卡（无需 root，HttpURLConnection 直连；失败则降级到 WebView）
            new Thread(() -> doSilentCheckinAPI()).start();
        } catch (Throwable t) { log("DO_CHECKIN=" + t.getMessage()); }
    }

    // API 静默打卡：直接调用金山表单 API，不需要打开 WebView
    private static volatile String CAMPAIGN_ID = "Ds1zAQtq";
    private static final String KDOCS_API_BASE = "https://f-api.kdocs.cn/ksform/api/v3/campaign/";
    private static final String KDOCS_AUTH_CHECK = "https://account.kdocs.cn/p/auth/check";
    private static final String KDOCS_REFERER = "https://f.kdocs.cn/ksform/cw/w/";

    private static void doSilentCheckinAPI() {
        try {
            // 从 CookieManager 读取 cookie（WebView 加载表单后自动保存）
            String cookies = "";
            String csrf = "";
            try {
                android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                String[] domains = {
                    "https://f-api.kdocs.cn",
                    "https://account.kdocs.cn",
                    "https://f.kdocs.cn",
                    "https://account.wps.cn",
                    "https://www.kdocs.cn"
                };
                StringBuilder allCookies = new StringBuilder();
                for (String d : domains) {
                    String c = cm.getCookie(d);
                    if (c != null && !c.isEmpty()) {
                        allCookies.append(c).append("; ");
                    }
                }
                cookies = allCookies.toString();
                if (!cookies.isEmpty()) {
                    int csrfIdx = cookies.indexOf("csrf=");
                    if (csrfIdx >= 0) {
                        int start = csrfIdx + 5;
                        int end = cookies.indexOf(";", start);
                        if (end < 0) end = cookies.length();
                        csrf = cookies.substring(start, end);
                    }
                    // 保存到文件供 root curl 使用（用 su 写入，WPS 进程无 /data/local/tmp 写权限）
                    capturedCookies = cookies;
                    capturedCsrf = csrf;
                    saveCheckinBackup(cookies, csrf);
                }
            } catch (Throwable t) { log("API_COOKIE_READ=" + t.getMessage()); }

            // 如果 CookieManager 没有，用之前捕获的
            if (cookies.isEmpty() && !capturedCookies.isEmpty()) {
                cookies = capturedCookies;
                csrf = capturedCsrf;
            }

            if (cookies.isEmpty()) {
                log("API_CHECKIN: no cookies available, skip");
                fallbackToWebView();
                return;
            }
            log("API_CHECKIN: cookies=" + cookies.substring(0, Math.min(50, cookies.length())) + " csrf=" + (csrf.isEmpty() ? "none" : csrf.substring(0, Math.min(8, csrf.length())) + "..."));

            String ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 WPS-Miuix/1.0";

            // 如果姓名未配置，从历史打卡记录提取（用户已打过卡时表单页取不到）
            if (checkinInputName.isEmpty()) {
                log("API_CHECKIN: inputName empty, fetching from history...");
                ensureCheckinUserInfoFromAnswers(cookies, csrf);
                if (checkinInputName.isEmpty()) {
                    log("API_CHECKIN: no name in history, using locationName");
                }
            }

            String inputName = checkinInputName.isEmpty() ? checkinLocationName : checkinInputName;
            long ts = System.currentTimeMillis();

            // STEP 1: 认证检查
            log("[API 1/6] 认证检查");
            String authJson = "{\"_t\":" + ts + "}";
            String authResp = httpPost(KDOCS_AUTH_CHECK, authJson, cookies, csrf, ua);
            if (authResp.isEmpty() || (!authResp.contains("userid") && !authResp.contains("nickname"))) {
                log("API_CHECKIN: auth failed, trying cookie refresh...");
                // Cookie 过期，用 WPS 后台刷新 Cookie 后重试
                if (refreshCookiesAndRetry()) {
                    // 刷新成功，重新读取 Cookie
                    try {
                        android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                        String[] domains = {"https://f-api.kdocs.cn","https://account.kdocs.cn","https://f.kdocs.cn","https://account.wps.cn","https://www.kdocs.cn"};
                        StringBuilder all = new StringBuilder();
                        for (String d : domains) { String ck = cm.getCookie(d); if (ck != null && !ck.isEmpty()) all.append(ck).append("; "); }
                        cookies = all.toString();
                        int ci = cookies.indexOf("csrf=");
                        if (ci >= 0) { int s = ci+5; int e = cookies.indexOf(";",s); if(e<0)e=cookies.length(); csrf = cookies.substring(s,e); }
                    } catch (Throwable t) {}
                    // 重试认证
                    authResp = httpPost(KDOCS_AUTH_CHECK, "{\"_t\":" + System.currentTimeMillis() + "}", cookies, csrf, ua);
                    if (authResp.isEmpty() || (!authResp.contains("userid") && !authResp.contains("nickname"))) {
                        log("API_CHECKIN: auth still failed after refresh");
                        fallbackToWebView();
                        return;
                    }
                    log("API_CHECKIN: auth OK after refresh");
                } else {
                    fallbackToWebView();
                    return;
                }
            }
            log("API_CHECKIN: auth OK");

            // STEP 2: 获取表单信息（clockinFieldID + commitOptionID）
            log("[API 2/6] 获取表单信息");
            String formResp = httpGet(KDOCS_API_BASE + CAMPAIGN_ID, cookies, csrf, ua);
            String clockinFieldID = "";
            String commitOptionID = "";
            String commitOptionText = "";
            try {
                org.json.JSONObject formJson = new org.json.JSONObject(formResp);
                if (formJson.optInt("code") == 0) {
                    org.json.JSONObject data = formJson.getJSONObject("data");
                    org.json.JSONObject qMap = data.optJSONObject("questionMap");
                    if (qMap != null) {
                        java.util.Iterator<String> keys = qMap.keys();
                        StringBuilder fieldDump = new StringBuilder();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            org.json.JSONObject q = qMap.getJSONObject(k);
                            String type = q.optString("type");
                            String title = q.optString("title", q.optString("name", ""));
                            fieldDump.append(k).append(":").append(type).append("(").append(title).append(") ");
                            if ("clockinInfo".equals(type)) {
                                clockinFieldID = k;
                                // 没打过卡时没有历史记录，从当前表单定义解析子字段（总会有结构）
                                if (checkinClockinFields.isEmpty()) {
                                    String fromForm = extractClockinFieldsFromQuestion(q);
                                    if (!fromForm.isEmpty()) {
                                        checkinClockinFields = fromForm;
                                        saveCheckinConfig();
                                        log("CLOCKIN_FIELDS_FROM_FORM: " + fromForm);
                                    } else {
                                        log("CLOCKIN_QUESTION_RAW: " + q.toString().substring(0, Math.min(300, q.toString().length())));
                                    }
                                }
                            }
                        }
                        log("FORM_FIELDS: " + fieldDump);
                    }
                    org.json.JSONArray opts = data.optJSONObject("setting")
                        .optJSONObject("baseSetting")
                        .optJSONObject("commitConfig")
                        .optJSONArray("options");
                    if (opts != null && opts.length() > 0) {
                        commitOptionID = opts.getJSONObject(0).optString("id");
                        commitOptionText = opts.getJSONObject(0).optString("text");
                    }
                }
            } catch (Throwable t) { log("API_FORM_PARSE=" + t.getMessage()); }
            if (clockinFieldID.isEmpty() || commitOptionID.isEmpty()) {
                log("API_CHECKIN: missing fields clockin=" + clockinFieldID + " commit=" + commitOptionID);
                fallbackToWebView();
                return;
            }
            log("API_CHECKIN: field=" + clockinFieldID + " commit=" + commitOptionID);

            // STEP 3: 预检查
            log("[API 3/6] 预检查");
            String preResp = httpPost(KDOCS_API_BASE + CAMPAIGN_ID + "/precheck", "{}", cookies, csrf, ua);
            if (preResp.contains("时间") || preResp.contains("周期")) {
                log("API_CHECKIN: precheck blocked: " + preResp);
                showCheckinNotification("打卡时间未到", preResp.substring(0, Math.min(50, preResp.length())));
                return;
            }

            // STEP 4: 验证姓名（表单可能未启用预设名单，失败时降级为空 keyId 继续提交）
            log("[API 4/6] 验证姓名: " + inputName);
            String keyID = "";
            try {
                org.json.JSONObject presetPayload = new org.json.JSONObject();
                presetPayload.put("key", inputName);
                String presetResp = httpPost(KDOCS_API_BASE + CAMPAIGN_ID + "/preset/key/check",
                    presetPayload.toString(), cookies, csrf, ua);
                try {
                    org.json.JSONObject pj = new org.json.JSONObject(presetResp);
                    if (pj.optInt("code") == 0) {
                        keyID = pj.optJSONObject("data").optString("keyId");
                    }
                } catch (Throwable t) {}
                if (keyID.isEmpty()) {
                    log("API_CHECKIN: preset key not available, using empty keyId");
                }
            } catch (Throwable t) {
                log("API_CHECKIN: preset check skip: " + t.getMessage());
            }
            log("API_CHECKIN: keyID='" + keyID + "'");

            // STEP 5: 检查今日是否已打卡
            log("[API 5/6] 检查打卡状态");
            String answersResp = httpPost(KDOCS_API_BASE + CAMPAIGN_ID + "/answers/list",
                "{\"page\":1,\"pageSize\":5}", cookies, csrf, ua);
            try {
                org.json.JSONObject aj = new org.json.JSONObject(answersResp);
                if (aj.optInt("code") == 0) {
                    org.json.JSONArray answers = aj.optJSONObject("data").optJSONArray("answers");
                    String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date());
                    if (answers != null) {
                        for (int i = 0; i < answers.length(); i++) {
                            if (answers.getJSONObject(i).optString("aid").startsWith(today)) {
                                log("API_CHECKIN: already clocked in today");
                                saveCheckinRecord();
                                showCheckinNotification("今日已打卡", checkinLocationName);
                                return;
                            }
                        }
                    }
                }
            } catch (Throwable t) { log("API_ANSWERS_PARSE=" + t.getMessage()); }

            // STEP 6: 提交打卡
            log("[API 6/6] 提交打卡");
            org.json.JSONObject clockinVal = buildClockinInfoValue(inputName, checkinLocationName, checkinDepartment, checkinStudentId);
            org.json.JSONObject fieldAnswer = new org.json.JSONObject();
            fieldAnswer.put("type", "clockinInfo");
            fieldAnswer.put("clockinInfoValue", clockinVal);
            org.json.JSONObject answers = new org.json.JSONObject();
            answers.put(clockinFieldID, fieldAnswer);
            org.json.JSONObject commitInfo = new org.json.JSONObject();
            commitInfo.put("optionId", commitOptionID);
            commitInfo.put("optionText", commitOptionText);
            org.json.JSONObject clockinInfoProp = new org.json.JSONObject();
            clockinInfoProp.put("clockinStatus", "normal");
            clockinInfoProp.put("outOfPeriodDescription", "");
            org.json.JSONObject answersProp = new org.json.JSONObject();
            answersProp.put("presetKeyId", keyID);
            answersProp.put("presetKeyValue", inputName);
            answersProp.put("commitInfo", commitInfo);
            answersProp.put("clockinInfo", clockinInfoProp);
            org.json.JSONObject answerJson = new org.json.JSONObject();
            answerJson.put("answers", answers);
            answerJson.put("consumeTime", 10);
            answerJson.put("answersProperty", answersProp);
            org.json.JSONObject payload = new org.json.JSONObject();
            payload.put("answerJson", answerJson);
            payload.put("_t", System.currentTimeMillis());

            String submitResp = httpPost(KDOCS_API_BASE + CAMPAIGN_ID, payload.toString(), cookies, csrf, ua);
            log("API_SUBMIT resp=" + submitResp.substring(0, Math.min(200, submitResp.length())));

            boolean success = submitResp.contains("\"code\":0") || submitResp.contains("\"code\": 0");
            if (success) {
                saveCheckinRecord();
                checkinRetryCount = 0;
                showCheckinNotification("静默打卡成功", checkinLocationName + " (" + checkinLat + "," + checkinLng + ")");
                log("API_CHECKIN: SUCCESS");
            } else {
                log("API_CHECKIN: submit failed");
                showCheckinNotification("静默打卡失败", "API返回: " + submitResp.substring(0, Math.min(50, submitResp.length())));
                // 降级到 WebView
                fallbackToWebView();
            }
        } catch (Throwable t) {
            log("API_CHECKIN=" + t.getMessage());
            fallbackToWebView();
        }
    }

    // 后台捕获 Cookie：用 HttpURLConnection 请求表单页，从 Set-Cookie 头获取
    private static boolean captureCookiesBackground() {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(CHECKIN_FORM_URL).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 WPS-Miuix/1.0");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.connect();

            StringBuilder allCookies = new StringBuilder();
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            // 从所有响应头收集 Set-Cookie
            java.util.Map<String, java.util.List<String>> headers = conn.getHeaderFields();
            for (java.util.Map.Entry<String, java.util.List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase("Set-Cookie")) {
                    for (String cookie : entry.getValue()) {
                        // "name=value; path=...; domain=..." → 取 "name=value" 部分
                        String nameVal = cookie.split(";")[0].trim();
                        if (!nameVal.isEmpty()) {
                            allCookies.append(nameVal).append("; ");
                            // 同步写入 CookieManager
                            String domain = "account.kdocs.cn";
                            if (cookie.contains("f-api.kdocs.cn")) domain = "f-api.kdocs.cn";
                            else if (cookie.contains("f.kdocs.cn")) domain = "f.kdocs.cn";
                            try { cm.setCookie("https://" + domain, nameVal); } catch (Throwable ignored) {}
                        }
                    }
                }
            }
            conn.disconnect();

            if (allCookies.length() > 0) {
                capturedCookies = allCookies.toString();
                int csrfIdx = capturedCookies.indexOf("csrf=");
                if (csrfIdx >= 0) {
                    int start = csrfIdx + 5;
                    int end = capturedCookies.indexOf(";", start);
                    if (end < 0) end = capturedCookies.length();
                    capturedCsrf = capturedCookies.substring(start, end);
                }
                // 保存到文件
                saveCheckinBackup(capturedCookies, capturedCsrf);
                log("BG_COOKIE_CAPTURED len=" + capturedCookies.length());
                return true;
            }
            log("BG_COOKIE_CAPTURED: no cookies in response");
            return false;
        } catch (Throwable t) {
            log("BG_COOKIE_CAPTURE=" + t.getMessage());
            return false;
        }
    }

    // 动态构造打卡子字段（clockinInfoValue）：按 checkinClockinFields 驱动，
    // 适配不同表单模板（寒假表单字段变化时，历史记录反推的字段列表会自动跟随）
    private static org.json.JSONObject buildClockinInfoValue(String inputName, String locationName, String department, String studentId) {
        org.json.JSONObject val = new org.json.JSONObject();
        // 默认字段模板；历史记录可用时会被 checkinClockinFields 覆盖（更贴近真实表单）
        String fields = checkinClockinFields;
        if (fields == null || fields.isEmpty()) {
            fields = "clockinName,clockinLocation,clockinDepartment,clockinStudentId,clockinAcademicGraduates,clockinMajor";
        }
        for (String f : fields.split(",")) {
            f = f.trim();
            if (f.isEmpty()) continue;
            try {
                org.json.JSONObject sub = new org.json.JSONObject();
                sub.put("type", "input");
                // 已知语义字段用当前配置（用户可在打卡设置里改）；未知字段用历史值回填
                if ("clockinName".equals(f)) sub.put("strValue", inputName);
                else if ("clockinLocation".equals(f)) sub.put("strValue", locationName);
                else if ("clockinDepartment".equals(f)) sub.put("strValue", department);
                else if ("clockinStudentId".equals(f)) sub.put("strValue", studentId);
                else {
                    String hv = clockinValueFromHistory(f);
                    if (!hv.isEmpty()) sub.put("strValue", hv);
                    else { val.put(f, sub); continue; }
                }
                sub.put("isManualInput", false);
                val.put(f, sub);
            } catch (Throwable ignored) {}
        }
        return val;
    }

    // 从历史值快照（checkinClockinValues JSON）提取某字段的值；无则返回空串
    private static String clockinValueFromHistory(String field) {
        try {
            if (checkinClockinValues == null || checkinClockinValues.isEmpty()) return "";
            org.json.JSONObject o = new org.json.JSONObject(checkinClockinValues);
            org.json.JSONObject f = o.optJSONObject(field);
            return f != null ? f.optString("strValue") : "";
        } catch (Throwable t) { return ""; }
    }

    // 从表单定义（questionMap 中 clockinInfo 字段）尽力提取子字段列表
    // 没打过卡时没有历史记录，但当前表单定义里总会有子字段结构，从这里兜底
    private static String extractClockinFieldsFromQuestion(org.json.JSONObject q) {
        try {
            StringBuilder sb = new StringBuilder();
            collectClockinNames(q, sb);
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }

    private static void collectClockinNames(Object node, StringBuilder sb) {
        if (node instanceof org.json.JSONObject) {
            org.json.JSONObject o = (org.json.JSONObject) node;
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String key = it.next();
                // 收集 key（子字段名通常是 JSONObject 的 key）
                addClockinName(key, sb);
                Object val = o.opt(key);
                if (val instanceof String) addClockinName((String) val, sb);
                else collectClockinNames(val, sb);
            }
        } else if (node instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                Object item = a.opt(i);
                if (item instanceof String) addClockinName((String) item, sb);
                else collectClockinNames(item, sb);
            }
        }
    }

    // 只收有效子字段名：clockin 开头 + 首字母大写，排除结构关键词
    private static void addClockinName(String s, StringBuilder sb) {
        if (s == null || !s.startsWith("clockin")) return;
        if (s.equals("clockinInfo") || s.equals("clockinInfoValue") || s.equals("clockinStatus")) return;
        if (s.length() <= 7) return; // 仅 "clockin"
        char c = s.charAt(7);
        if (c < 'A' || c > 'Z') return; // 必须 clockinXxx 形式
        if (sb.indexOf(s) >= 0) return;
        if (sb.length() > 0) sb.append(",");
        sb.append(s);
    }

    // 写文件到 WPS 私有目录（owner 始终是 WPS UID，可靠读写）
    private static void saveRootFile(String path, String content) throws Exception {
        java.io.FileWriter fw = new java.io.FileWriter(path);
        fw.write(content);
        fw.close();
    }

    // 保存 cookie/CSRF/打卡参数备份，供独立 CheckinWorker 使用
    private static void saveCheckinBackup(String cookies, String csrf) {
        try {
            saveRootFile(COOKIE_FILE, cookies);
            saveRootFile(CSRF_FILE, csrf);
            // 若姓名/部门/学号为空（用户已打过卡，表单页取不到），从历史打卡记录提取
            // 异步执行避免阻塞 WebView 线程
            final String fCookies = cookies;
            final String fCsrf = csrf;
            new Thread(() -> {
                try {
                    ensureCheckinUserInfoFromAnswers(fCookies, fCsrf);
                    String params = "inputName=" + checkinInputName + "\n"
                        + "locationName=" + checkinLocationName + "\n"
                        + "department=" + checkinDepartment + "\n"
                        + "studentId=" + checkinStudentId + "\n"
                        + "lat=" + checkinLat + "\n"
                        + "lng=" + checkinLng + "\n"
                        + "ua=Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 WPS-Miuix/1.0\n"
                        + "campaign=" + CAMPAIGN_ID + "\n"
                        + "cachedCampaign=" + CAMPAIGN_ID + "\n"
                        + "fields=" + checkinClockinFields + "\n"
                        + "values=" + checkinClockinValues + "\n";
                    saveRootFile(PARAMS_FILE, params);
                    writeCheckinScript(fCookies, fCsrf);
                    deployCheckinWorker();
                    log("BACKUP_SAVED len=" + fCookies.length() + " name=" + checkinInputName + " dept=" + (checkinDepartment.isEmpty() ? "empty" : "ok") + " sid=" + checkinStudentId);
                } catch (Throwable t) { log("BACKUP_SAVE=" + t.getMessage()); }
            }).start();
        } catch (Throwable t) { log("BACKUP_SAVE=" + t.getMessage()); }
    }

    // 从历史打卡记录(answers/list)提取姓名/部门/学号，填充到配置（用户打过卡后表单页取不到）
    private static void ensureCheckinUserInfoFromAnswers(String cookies, String csrf) {
        try {
            String answersResp = httpPost(KDOCS_API_BASE + CAMPAIGN_ID + "/answers/list",
                "{\"page\":1,\"pageSize\":5}", cookies, csrf,
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 WPS-Miuix/1.0");
            if (answersResp.isEmpty() || !answersResp.contains("\"clockinName\"")) return;
            org.json.JSONObject aj = new org.json.JSONObject(answersResp);
            if (aj.optInt("code") != 0) return;
            org.json.JSONArray answers = aj.optJSONObject("data").optJSONArray("answers");
            if (answers == null || answers.length() == 0) return;
            // 取第一条有值的记录
            for (int i = 0; i < answers.length(); i++) {
                org.json.JSONObject answer = answers.getJSONObject(i);
                org.json.JSONObject answersObj = answer.optJSONObject("answerJson");
                if (answersObj == null) continue;
                org.json.JSONObject answersMap = answersObj.optJSONObject("answers");
                if (answersMap == null) continue;
                for (java.util.Iterator<String> it = answersMap.keys(); it.hasNext(); ) {
                    String key = it.next();
                    org.json.JSONObject field = answersMap.optJSONObject(key);
                    if (field == null || !"clockinInfo".equals(field.optString("type"))) continue;
                    org.json.JSONObject val = field.optJSONObject("clockinInfoValue");
                    if (val == null) continue;
                    // 动态字段列表：从历史记录反推表单打卡子字段（含顺序），适配不同表单模板
                    StringBuilder fieldsSb = new StringBuilder();
                    for (java.util.Iterator<String> vit = val.keys(); vit.hasNext(); ) {
                        String vk = vit.next();
                        if (fieldsSb.length() > 0) fieldsSb.append(",");
                        fieldsSb.append(vk);
                    }
                    if (fieldsSb.length() > 0) {
                        checkinClockinFields = fieldsSb.toString();
                        // 保存完整值快照：新字段的值也从历史记录带回，避免提交时丢值
                        checkinClockinValues = val.toString();
                        log("CLOCKIN_FIELDS_FROM_HISTORY: " + checkinClockinFields);
                        log("CLOCKIN_VALUES_FROM_HISTORY: " + checkinClockinValues.substring(0, Math.min(200, checkinClockinValues.length())));
                    }
                    String name = val.optJSONObject("clockinName") != null ? val.optJSONObject("clockinName").optString("strValue") : "";
                    String dept = val.optJSONObject("clockinDepartment") != null ? val.optJSONObject("clockinDepartment").optString("strValue") : "";
                    String sid = val.optJSONObject("clockinStudentId") != null ? val.optJSONObject("clockinStudentId").optString("strValue") : "";
                    if (checkinInputName.isEmpty() && !name.isEmpty()) checkinInputName = name;
                    if (checkinDepartment.isEmpty() && !dept.isEmpty()) checkinDepartment = dept;
                    if (checkinStudentId.isEmpty() && !sid.isEmpty()) checkinStudentId = sid;
                    saveCheckinConfig();
                    log("INFO_FROM_ANSWERS name=" + checkinInputName + " dept_ok=" + !checkinDepartment.isEmpty() + " sid=" + checkinStudentId);
                    return;
                }
            }
        } catch (Throwable t) {
            log("INFO_FROM_ANSWERS_ERR class=" + t.getClass().getSimpleName() + " msg=" + t.getMessage());
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            log("INFO_FROM_ANSWERS_STACK=" + sw.toString().substring(0, Math.min(300, sw.toString().length())));
        }
    }

    private static String readFlagFile(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return "0";
            BufferedReader br = new BufferedReader(new java.io.FileReader(f));
            String val = br.readLine();
            br.close();
            return val != null ? val.trim() : "0";
        } catch (Throwable t) { return "0"; }
    }

    // 从模块 APK assets 部署 CheckinWorker.dex 到设备
    // 注意：WPS 进程无 /data/local/tmp 写权限，此处不再尝试（文件由模块 app 预部署）
    private static void deployCheckinWorker() {
        try {
            java.io.File target = new java.io.File("/data/local/tmp/CheckinWorker.dex");
            if (target.exists() && target.length() > 8000) {
                log("DEPLOY_WORKER: already exists");
            } else {
                log("DEPLOY_WORKER: missing, skip (module app should deploy)");
            }
        } catch (Throwable t) { log("DEPLOY_WORKER=" + t.getMessage()); }
    }

    // 写完整的 curl 打卡脚本到 /data/local/tmp/wyu-checkin.sh
    private static void writeCheckinScript(String cookies, String csrf) throws Exception {
        String name = checkinInputName;
        String loc = checkinLocationName;
        String ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 WPS-Miuix/1.0";
        String cid = CAMPAIGN_ID;
        String referer = KDOCS_REFERER + cid;
        String logFile = getDataDir() + "/wps-miuix.log";
        String recordFile = getDataDir() + "/wps-miuix-checkin-log.txt";

        // 用单引号包裹 cookie（cookie 里不会有单引号）
        String script =
            "#!/system/bin/sh\n" +
            "LOG='" + logFile + "'\n" +
            "COOKIES=$(cat '" + COOKIE_FILE + "')\n" +
            "CSRF=$(cat '" + CSRF_FILE + "')\n" +
            "UA='" + ua + "'\n" +
            "CID='" + cid + "'\n" +
            "NAME='" + name + "'\n" +
            "LOC='" + loc + "'\n" +
            "TS=$(date +%s%3N)\n" +
            "TODAY=$(date +%Y%m%d)\n" +
            "echo \"$(date +%H:%M:%S) ROOT_CHECKIN start\" >> $LOG\n" +
            // 检查今日是否已打卡
            "ANSWERS=$(curl -s -X POST 'https://f-api.kdocs.cn/ksform/api/v3/campaign/'$CID'/answers/list' " +
            "-H 'Cookie: '$COOKIES -H 'X-CSRF-Token: '$CSRF -H 'Content-Type: application/json;charset=UTF-8' " +
            "-H 'User-Agent: '$UA --referer '" + referer + "' -d '{\"page\":1,\"pageSize\":5}')\n" +
            "if echo \"$ANSWERS\" | grep -q '\"aid\":\"'$TODAY; then\n" +
            "  echo \"$(date +%H:%M:%S) ROOT_CHECKIN: already done today\" >> $LOG\n" +
            "  exit 0\n" +
            "fi\n" +
            // 认证检查
            "AUTH=$(curl -s -X POST 'https://account.kdocs.cn/p/auth/check' " +
            "-H 'Cookie: '$COOKIES -H 'X-CSRF-Token: '$CSRF -H 'Content-Type: application/json;charset=UTF-8' " +
            "-H 'User-Agent: '$UA -d '{\"_t\":'$TS'}')\n" +
            "echo \"$(date +%H:%M:%S) ROOT_AUTH: $AUTH\" >> $LOG\n" +
            "if ! echo \"$AUTH\" | grep -q 'nickname'; then\n" +
            "  echo \"$(date +%H:%M:%S) ROOT_AUTH failed\" >> $LOG\n" +
            "  exit 1\n" +
            "fi\n" +
            // 获取表单信息
            "FORM=$(curl -s 'https://f-api.kdocs.cn/ksform/api/v3/campaign/'$CID " +
            "-H 'Cookie: '$COOKIES -H 'X-CSRF-Token: '$CSRF -H 'User-Agent: '$UA --referer '" + referer + "')\n" +
            "FIELD=$(echo \"$FORM\" | grep -oE '\"[a-z0-9]+\":\\{\"type\":\"clockinInfo\"' | grep -oE '\"[a-z0-9]+\"' | tr -d '\"')\n" +
            "OPTID=$(echo \"$FORM\" | sed 's/.*\"commitConfig\":{\"options\":\\[{\"id\":\"//' | cut -d'\"' -f1)\n" +
            "OPTTEXT=$(echo \"$FORM\" | sed 's/.*\"commitConfig\":{\"options\":\\[{\"id\":\"[^\"]*\",\"text\":\"//' | cut -d'\"' -f1)\n" +
            "echo \"$(date +%H:%M:%S) ROOT_FORM field=$FIELD opt=$OPTID\" >> $LOG\n" +
            "if [ -z \"$FIELD\" ]; then\n" +
            "  echo \"$(date +%H:%M:%S) ROOT_FORM no field found\" >> $LOG\n" +
            "  exit 1\n" +
            "fi\n" +
            // 预检查
            "PRE=$(curl -s -X POST 'https://f-api.kdocs.cn/ksform/api/v3/campaign/'$CID'/precheck' " +
            "-H 'Cookie: '$COOKIES -H 'X-CSRF-Token: '$CSRF -H 'Content-Type: application/json;charset=UTF-8' " +
            "-H 'User-Agent: '$UA --referer '" + referer + "' -d '{}')\n" +
            "echo \"$(date +%H:%M:%S) ROOT_PRE: $PRE\" >> $LOG\n" +
            "if echo \"$PRE\" | grep -qE '时间|周期|限制'; then\n" +
            "  echo \"$(date +%H:%M:%S) ROOT_PRE blocked\" >> $LOG\n" +
            "  exit 0\n" +
            "fi\n" +
            // 验证姓名
            "KEY=$(curl -s -X POST 'https://f-api.kdocs.cn/ksform/api/v3/campaign/'$CID'/preset/key/check' " +
            "-H 'Cookie: '$COOKIES -H 'X-CSRF-Token: '$CSRF -H 'Content-Type: application/json;charset=UTF-8' " +
            "-H 'User-Agent: '$UA --referer '" + referer + "' -d '{\"key\":\"'$NAME'\"}')\n" +
            "KEYID=$(echo \"$KEY\" | sed 's/.*\"keyId\":\"//' | cut -d'\"' -f1)\n" +
            "echo \"$(date +%H:%M:%S) ROOT_KEY: $KEY\" >> $LOG\n" +
            "if [ -z \"$KEYID\" ]; then\n" +
            "  echo \"$(date +%H:%M:%S) ROOT_KEY failed for $NAME\" >> $LOG\n" +
            "  exit 1\n" +
            "fi\n" +
            // 提交打卡
            "BODY=$(cat <<'JSONEOF'\n" +
            "{\"answerJson\":{\"answers\":{\"%FIELD%\":{\"type\":\"clockinInfo\",\"clockinInfoValue\":{\"clockinLocation\":{\"type\":\"input\",\"strValue\":\"%LOC%\",\"isManualInput\":false},\"clockinName\":{\"type\":\"input\",\"strValue\":\"%NAME%\",\"isManualInput\":false}}}},\"consumeTime\":10,\"answersProperty\":{\"presetKeyId\":\"%KEYID%\",\"presetKeyValue\":\"%NAME%\",\"commitInfo\":{\"optionId\":\"%OPTID%\",\"optionText\":\"%OPTTEXT%\"},\"clockinInfo\":{\"clockinStatus\":\"normal\",\"outOfPeriodDescription\":\"\"}}},\"_t\":%TS%}\n" +
            "JSONEOF\n)\n" +
            "BODY=$(echo \"$BODY\" | sed \"s/%FIELD%/$FIELD/g\" | sed \"s/%LOC%/$LOC/g\" | sed \"s/%NAME%/$NAME/g\" | sed \"s/%KEYID%/$KEYID/g\" | sed \"s/%OPTID%/$OPTID/g\" | sed \"s/%OPTTEXT%/$OPTTEXT/g\" | sed \"s/%TS%/$TS/g\")\n" +
            "RESULT=$(curl -s -X POST 'https://f-api.kdocs.cn/ksform/api/v3/campaign/'$CID " +
            "-H 'Cookie: '$COOKIES -H 'X-CSRF-Token: '$CSRF -H 'Content-Type: application/json;charset=UTF-8' " +
            "-H 'User-Agent: '$UA -H 'Origin: https://f.kdocs.cn' --referer '" + referer + "' " +
            "-d \"$BODY\")\n" +
            "echo \"$(date +%H:%M:%S) ROOT_SUBMIT: $RESULT\" >> $LOG\n" +
            "if echo \"$RESULT\" | grep -qE '\"code\":\\s*0'; then\n" +
            "  echo \"$(date '+%Y-%m-%d %H:%M:%S') ROOT | $LOC\" >> '" + recordFile + "'\n" +
            "  echo \"$(date +%H:%M:%S) ROOT_CHECKIN: SUCCESS\" >> $LOG\n" +
            "else\n" +
            "  echo \"$(date +%H:%M:%S) ROOT_CHECKIN: FAILED\" >> $LOG\n" +
            "fi\n";

        saveRootFile("/data/local/tmp/wyu-checkin.sh", script);
    }

    // HTTP GET 请求（Java HttpURLConnection，不依赖 curl）
    private static String httpGet(String url, String cookies, String csrf, String ua) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Cookie", cookies);
            conn.setRequestProperty("X-CSRF-Token", csrf);
            conn.setRequestProperty("User-Agent", ua);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Referer", KDOCS_REFERER + CAMPAIGN_ID);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            java.io.InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            conn.disconnect();
            return sb.toString();
        } catch (Throwable t) { log("HTTP_GET=" + t.getMessage()); return ""; }
    }

    // HTTP POST 请求（Java HttpURLConnection，不依赖 curl）
    private static String httpPost(String url, String jsonBody, String cookies, String csrf, String ua) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Cookie", cookies);
            conn.setRequestProperty("X-CSRF-Token", csrf);
            conn.setRequestProperty("User-Agent", ua);
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Origin", "https://f.kdocs.cn");
            conn.setRequestProperty("Referer", KDOCS_REFERER + CAMPAIGN_ID);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            byte[] body = jsonBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            java.io.OutputStream os = conn.getOutputStream();
            os.write(body);
            os.close();
            int code = conn.getResponseCode();
            java.io.InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            conn.disconnect();
            return sb.toString();
        } catch (Throwable t) { log("HTTP_POST=" + t.getMessage()); return ""; }
    }

    // Cookie 过期时：用 WPS 进程后台加载表单刷新 Cookie
    private static boolean refreshCookiesAndRetry() {
        try {
            log("COOKIE_REFRESH: opening form to refresh cookies...");
            refreshingCookies = true;
            checkinSubmitted = false;
            checkinSpoofing = true;

            // 用 WPS 打开表单（触发 onPageFinished → 捕获新 Cookie）
            Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(CHECKIN_FORM_URL));
            intent.setClassName("com.wps.koa", "com.wps.koa.ui.router.RouterActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            appContext.startActivity(intent);

            // 等待 Cookie 刷新完成（最多 15 秒）
            for (int i = 0; i < 30; i++) {
                Thread.sleep(500);
                if (!refreshingCookies) {
                    log("COOKIE_REFRESH: success, new cookies captured");
                    // 关闭表单页面
                    try {
                        if (appContext instanceof Activity) {
                            ((Activity) appContext).finish();
                        }
                    } catch (Throwable t) {}
                    return !capturedCookies.isEmpty();
                }
            }
            log("COOKIE_REFRESH: timeout");
            refreshingCookies = false;
            return false;
        } catch (Throwable t) {
            log("COOKIE_REFRESH=" + t.getMessage());
            refreshingCookies = false;
            return false;
        }
    }

    // 降级到 WebView 方式
    private static void fallbackToWebView() {
        try {
            if (appContext == null) return;
            Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(CHECKIN_FORM_URL));
            intent.setClassName("com.wps.koa", "com.wps.koa.ui.router.RouterActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            appContext.startActivity(intent);
            log("FALLBACK: opened WebView form");
        } catch (Throwable t) { log("FALLBACK=" + t.getMessage()); }
    }

    // [旧方案-已注释] 静默打卡：用 root 直接 POST 提交（捕获的API数据）
    // private static void doSilentCheckin() {
    //     try {
    //         log("SILENT_CHECKIN start endpoint=" + checkinApiEndpoint);
    //         String payload = checkinApiPayload
    //             .replace("__LAT__", String.valueOf(checkinLat))
    //             .replace("__LNG__", String.valueOf(checkinLng));
    //         String cmd = "curl -s -X POST '" + checkinApiEndpoint + "'"
    //             + " -H 'Content-Type: application/json'"
    //             + " -d '" + payload.replace("'", "'\\''") + "'";
    //         Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
    //         ...
    //     } catch (Throwable t) { ... }
    // }

    // 打卡结果通知
    private static void showCheckinNotification(String title, String text) {
        // 同时弹 Toast 提醒
        if (appContext != null) {
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(appContext, title + ": " + text, Toast.LENGTH_LONG).show()
            );
        }
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    "checkin", "打卡通知", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            android.app.Notification n = new android.app.Notification.Builder(appContext, "checkin")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build();
            nm.notify(9901, n);
        } catch (Throwable t) { log("NOTIFY=" + t.getMessage()); }
    }

    // 解析捕获的 API 数据
    private static void parseCapturedApi(String json) {
        try {
            // 简单解析：查找第一个 POST 请求的 url 和 body
            int urlIdx = json.indexOf("\"url\":\"");
            int bodyIdx = json.indexOf("\"body\":\"");
            if (urlIdx >= 0 && bodyIdx >= 0) {
                int urlStart = urlIdx + 7;
                int urlEnd = json.indexOf("\"", urlStart);
                checkinApiEndpoint = json.substring(urlStart, urlEnd);

                int bodyStart = bodyIdx + 8;
                int bodyEnd = json.indexOf("\"", bodyStart);
                String body = json.substring(bodyStart, bodyEnd);
                // 尝试解析 body JSON，替换坐标
                body = body.replace("\\\"", "\"");
                checkinApiPayload = body;

                log("API_PARSED endpoint=" + checkinApiEndpoint);
                log("API_PARSED payload=" + (body.length() > 100 ? body.substring(0, 100) + "..." : body));
            }
        } catch (Throwable t) { log("API_PARSE=" + t.getMessage()); }
    }
    private static void scheduleCheckin(Object executor) {
        try {
            if (appContext == null) { log("SCHEDULE no context"); return; }

            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, checkinHour);
            cal.set(java.util.Calendar.MINUTE, checkinMinute);
            cal.set(java.util.Calendar.SECOND, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
            }
            if (checkinWeekly) {
                while (cal.get(java.util.Calendar.DAY_OF_WEEK) != java.util.Calendar.MONDAY) {
                    cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
                }
            }

            // 用 AlarmManager 定时（不需要 root）
            try {
                android.app.AlarmManager am = (android.app.AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
                Intent intent = new Intent("com.wps.enhancer.CHECKIN_ACTION");
                intent.setPackage(appContext.getPackageName());
                android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    appContext, 0, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                try {
                    // Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限，可能失败
                    am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
                    log("SCHEDULE exact at " + cal.getTime());
                } catch (SecurityException e) {
                    // 无精确闹钟权限，降级 setAlarmClock（无需权限、精确）
                    am.setAlarmClock(new android.app.AlarmManager.AlarmClockInfo(cal.getTimeInMillis(), null), pi);
                    log("SCHEDULE via AlarmClock at " + cal.getTime());
                }
            } catch (Throwable t) {
                log("SCHEDULE alarm failed: " + t.getMessage());
            }
        } catch (Throwable t) { log("SCHEDULE=" + t.getMessage()); }
    }

    private static void cancelCheckin() {
        try {
            if (appContext == null) return;
            android.app.AlarmManager am = (android.app.AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent("com.wps.enhancer.CHECKIN_ACTION");
            intent.setPackage(appContext.getPackageName());
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                appContext, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
            log("CHECKIN_CANCELLED");
        } catch (Throwable t) { log("CANCEL=" + t.getMessage()); }
    }

    // ===== Miuix/HyperOS 风格 UI 辅助 =====
    private static final int MIUI_BLUE = 0xFF0A84FF;
    private static final int MIUI_CARD_RADIUS = 16;

    // 根据深色模式返回颜色
    private static boolean isDarkMode(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
    private static int textMain(Context ctx) { return isDarkMode(ctx) ? 0xFFE5E5EA : 0xFF1A1A1A; }
    private static int textSub(Context ctx) { return isDarkMode(ctx) ? 0xFF98989F : 0xFF8A8A8E; }
    private static int cardBg(Context ctx) { return isDarkMode(ctx) ? 0xFF2C2C2E : 0xFFFFFFFF; }
    private static int inputBg(Context ctx) { return isDarkMode(ctx) ? 0xFF3A3A3C : 0xFFF2F2F7; }
    private static int hintColor(Context ctx) { return isDarkMode(ctx) ? 0xFF6E6E73 : 0xFFB0B0B8; }
    private static int presetRowBg(Context ctx) { return isDarkMode(ctx) ? 0xFF3A3A3C : 0xFFF2F2F7; }

    // 创建圆角卡片容器
    private static LinearLayout makeMiuixCard(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(cardBg(ctx));
        bg.setCornerRadius(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, MIUI_CARD_RADIUS, ctx.getResources().getDisplayMetrics()));
        card.setBackground(bg);
        int cp = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, ctx.getResources().getDisplayMetrics());
        card.setPadding(cp, cp, cp, cp);
        return card;
    }

    // 创建分组标题
    private static TextView makeMiuixSectionTitle(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(textSub(ctx));
        int padL = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, ctx.getResources().getDisplayMetrics());
        tv.setPadding(padL, 0, 0, 0);
        return tv;
    }

    // 创建圆角输入框（Miuix 风格）
    private static EditText makeMiuixInput(Context ctx) {
        EditText et = new EditText(ctx);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(inputBg(ctx));
        bg.setCornerRadius(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, ctx.getResources().getDisplayMetrics()));
        et.setBackground(bg);
        et.setTextColor(textMain(ctx));
        et.setHintTextColor(hintColor(ctx));
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        int hp = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, ctx.getResources().getDisplayMetrics());
        int vp = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, ctx.getResources().getDisplayMetrics());
        et.setPadding(hp, vp, hp, vp);
        return et;
    }

    // 创建 Miuix 风格小标签
    private static TextView makeMiuixLabel(Context ctx, String text, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(color);
        return tv;
    }

    // 打卡设置对话框
    private static void showCheckinSettingsDialog(Context ctx) {
        try {
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle("自动打卡设置");

            // 外层：可滚动容器（Miuix 卡片风格）
            android.widget.ScrollView rootScroll = new android.widget.ScrollView(ctx);
            LinearLayout layout = new LinearLayout(ctx);
            layout.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, ctx.getResources().getDisplayMetrics());
            layout.setPadding(pad, pad/2, pad, pad/2);

            // ===== 卡片1：启用自动打卡 =====
            LinearLayout card1 = makeMiuixCard(ctx);
            android.widget.Switch sw = new android.widget.Switch(ctx);
            sw.setText("启用自动打卡");
            sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            sw.setTextColor(textMain(ctx));
            sw.setChecked(checkinEnabled);
            LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            card1.addView(sw, swLp);
            layout.addView(card1);
            // 卡片间距
            LinearLayout.LayoutParams cardSpacing = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardSpacing.topMargin = pad/2;

            // ===== 卡片1.5：打卡表单链接（打开锁定 = 用此链接并锁定，自动捕获不覆盖）=====
            LinearLayout card15 = makeMiuixCard(ctx);
            TextView formLabel = makeMiuixLabel(ctx, "打卡表单链接", textMain(ctx));
            card15.addView(formLabel);
            EditText formUrlInput = makeMiuixInput(ctx);
            formUrlInput.setText(CHECKIN_FORM_URL);
            formUrlInput.setHint("https://f.wps.cn/ksform/cw/w/xxxxxx");
            formUrlInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
            LinearLayout.LayoutParams formInputLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            formInputLp.topMargin = pad/2;
            card15.addView(formUrlInput, formInputLp);
            // 锁定开关：打开时解析并保存输入框链接；失败自动弹回
            android.widget.Switch manualSw = new android.widget.Switch(ctx);
            manualSw.setText("使用此链接");
            manualSw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            manualSw.setTextColor(textMain(ctx));
            manualSw.setChecked(checkinManualFormUrl);
            final boolean[] resetting = {false};
            manualSw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (resetting[0]) return;
                if (isChecked) {
                    if (!applyManualFormUrl(formUrlInput.getText().toString(), ctx)) {
                        // 解析失败，弹回开关（guard 防止重入）
                        resetting[0] = true;
                        manualSw.setChecked(false);
                        resetting[0] = false;
                    }
                } else {
                    checkinManualFormUrl = false;
                    saveCheckinConfig();
                    Toast.makeText(ctx, "已解锁，自动捕获生效", Toast.LENGTH_SHORT).show();
                }
            });
            LinearLayout.LayoutParams manualSwLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            manualSwLp.topMargin = pad/2;
            card15.addView(manualSw, manualSwLp);
            layout.addView(card15, cardSpacing);

            // ===== 卡片2：打卡时间与频率 =====
            LinearLayout card2 = makeMiuixCard(ctx);
            TextView timeLabel = makeMiuixLabel(ctx, "打卡时间", textMain(ctx));
            card2.addView(timeLabel);
            android.widget.TimePicker timePicker = new android.widget.TimePicker(ctx);
            timePicker.setIs24HourView(true);
            timePicker.setCurrentHour(checkinHour);
            timePicker.setCurrentMinute(checkinMinute);
            timePicker.setOnTimeChangedListener((view, hourOfDay, minute) -> {
                checkinHour = hourOfDay;
                checkinMinute = minute;
                saveCheckinConfig();
                if (checkinEnabled) {
                    scheduleCheckin(null);
                    Toast.makeText(ctx, "打卡时间已更新 " + hourOfDay + ":" + String.format("%02d", minute), Toast.LENGTH_SHORT).show();
                }
            });
            card2.addView(timePicker);
            // 频率
            TextView freqLabel = makeMiuixLabel(ctx, "打卡频率", textMain(ctx));
            LinearLayout.LayoutParams freqLabelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            freqLabelLp.topMargin = pad/2;
            card2.addView(freqLabel, freqLabelLp);
            android.widget.RadioGroup freqGroup = new android.widget.RadioGroup(ctx);
            freqGroup.setOrientation(android.widget.RadioGroup.HORIZONTAL);
            android.widget.RadioButton daily = new android.widget.RadioButton(ctx);
            daily.setText("每天");
            daily.setTextColor(textMain(ctx));
            android.widget.RadioButton weekly = new android.widget.RadioButton(ctx);
            weekly.setText("每周一");
            weekly.setTextColor(textMain(ctx));
            freqGroup.addView(daily);
            freqGroup.addView(weekly);
            freqGroup.check(checkinWeekly ? weekly.getId() : daily.getId());
            freqGroup.setOnCheckedChangeListener((group, checkedId) -> {
                checkinWeekly = checkedId == weekly.getId();
                saveCheckinConfig();
                if (checkinEnabled) scheduleCheckin(null);
            });
            card2.addView(freqGroup);
            layout.addView(card2, cardSpacing);

            // ===== 卡片3：打卡定位 =====
            LinearLayout card3 = makeMiuixCard(ctx);
            TextView locLabel = makeMiuixLabel(ctx, "打卡定位（伪装）", textMain(ctx));
            card3.addView(locLabel);
            EditText latInput = makeMiuixInput(ctx);
            latInput.setText(String.valueOf(checkinLat));
            latInput.setHint("纬度");
            latInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            inputLp.topMargin = pad/2;
            card3.addView(latInput, inputLp);
            EditText lngInput = makeMiuixInput(ctx);
            lngInput.setText(String.valueOf(checkinLng));
            lngInput.setHint("经度");
            lngInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            card3.addView(lngInput, inputLp);
            EditText locNameInput = makeMiuixInput(ctx);
            locNameInput.setText(checkinLocationName);
            locNameInput.setHint("地址名称");
            card3.addView(locNameInput, inputLp);
            layout.addView(card3, cardSpacing);

            // 输入框失焦时自动保存（表单姓名/部门/学号自动从历史记录获取，无需手动填写）
            android.view.View.OnFocusChangeListener saveOnBlur = (v, hasFocus) -> {
                if (!hasFocus) {
                    try {
                        checkinLat = Double.parseDouble(latInput.getText().toString());
                        checkinLng = Double.parseDouble(lngInput.getText().toString());
                    } catch (Throwable ignored) {}
                    checkinLocationName = locNameInput.getText().toString();
                    saveCheckinConfig();
                }
            };
            latInput.setOnFocusChangeListener(saveOnBlur);
            lngInput.setOnFocusChangeListener(saveOnBlur);
            locNameInput.setOnFocusChangeListener(saveOnBlur);

            // ===== 卡片4：快捷选地址 =====
            LinearLayout card4 = makeMiuixCard(ctx);
            // 标题行 + 操作按钮
            LinearLayout headerRow = new LinearLayout(ctx);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView presetLabel = makeMiuixLabel(ctx, "快捷选地址", textMain(ctx));
            presetLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            LinearLayout.LayoutParams presetLabelLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            headerRow.addView(presetLabel, presetLabelLp);
            // 保存常用地址
            TextView savePresetBtn = new TextView(ctx);
            savePresetBtn.setText("+ 保存常用");
            savePresetBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            savePresetBtn.setTextColor(MIUI_BLUE);
            savePresetBtn.setPadding(pad/2, 0, 0, 0);
            savePresetBtn.setOnClickListener(v -> {
                String lat = latInput.getText().toString().trim();
                String lng = lngInput.getText().toString().trim();
                String name = locNameInput.getText().toString().trim();
                if (lat.isEmpty() || lng.isEmpty()) {
                    Toast.makeText(ctx, "请先填写经纬度", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (name.isEmpty()) name = lat + "," + lng;
                String entry = name + "|" + lat + "|" + lng;
                if (checkinCustomPresets.isEmpty()) {
                    checkinCustomPresets = entry;
                } else {
                    checkinCustomPresets += ";" + entry;
                }
                saveCheckinConfig();
                Toast.makeText(ctx, "已保存: " + name, Toast.LENGTH_SHORT).show();
            });
            headerRow.addView(savePresetBtn);
            // 使用真实GPS
            TextView gpsBtn = new TextView(ctx);
            gpsBtn.setText("取消伪装");
            gpsBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            gpsBtn.setTextColor(MIUI_BLUE);
            LinearLayout.LayoutParams gpsLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            gpsLp.leftMargin = pad;
            gpsBtn.setOnClickListener(v -> {
                checkinUseRealGps = true;
                checkinSpoofing = false;
                saveCheckinConfig();
                Toast.makeText(ctx, "已取消伪装，使用真实GPS", Toast.LENGTH_SHORT).show();
            });
            headerRow.addView(gpsBtn, gpsLp);
            card4.addView(headerRow);

            String[][] presets = {
                {"五邑大学北主楼", "22.60176", "113.0810"},
                {"广州塔", "23.1066", "113.3245"},
                {"斗界", "5.0600", "201.5000"},
            };
            // 合并自定义预设
            java.util.List<String[]> allPresets = new java.util.ArrayList<>();
            for (String[] p : presets) allPresets.add(p);
            if (!checkinCustomPresets.isEmpty()) {
                for (String entry : checkinCustomPresets.split(";")) {
                    String[] parts = entry.split("\\|");
                    if (parts.length == 3) allPresets.add(parts);
                }
            }
            // 垂直列表（用 ScrollView 包裹，选项多时可滑动）
            android.widget.ScrollView presetScroll = new android.widget.ScrollView(ctx);
            presetScroll.setFillViewport(false);
            presetScroll.setVerticalScrollBarEnabled(true);
            LinearLayout presetList = new LinearLayout(ctx);
            presetList.setOrientation(LinearLayout.VERTICAL);
            for (int pi = 0; pi < allPresets.size(); pi++) {
                final String[] p = allPresets.get(pi);
                final boolean isCustom = pi >= presets.length;
                // 行容器
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
                rowBg.setColor(presetRowBg(ctx));
                rowBg.setCornerRadius(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, ctx.getResources().getDisplayMetrics()));
                row.setBackground(rowBg);
                int itemPadH = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, ctx.getResources().getDisplayMetrics());
                int itemPadV = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, ctx.getResources().getDisplayMetrics());
                row.setPadding(itemPadH, itemPadV, itemPadH, itemPadV);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = pad/4;
                // 地址名
                TextView item = new TextView(ctx);
                item.setText(p[0]);
                item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                item.setTextColor(MIUI_BLUE);
                LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                row.addView(item, itemLp);
                // 自定义预设加删除按钮
                if (isCustom) {
                    TextView delBtn = new TextView(ctx);
                    delBtn.setText("✕");
                    delBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                    delBtn.setTextColor(0xFFE53935);
                    delBtn.setPadding(pad/2, 0, 0, 0);
                    delBtn.setOnClickListener(v -> {
                        // 从 checkinCustomPresets 中删除
                        java.util.List<String> remaining = new java.util.ArrayList<>();
                        for (String entry : checkinCustomPresets.split(";")) {
                            String[] parts = entry.split("\\|");
                            if (parts.length == 3 && !parts[0].equals(p[0])) remaining.add(entry);
                        }
                        checkinCustomPresets = String.join(";", remaining);
                        saveCheckinConfig();
                        presetList.removeView(row);
                        Toast.makeText(ctx, "已删除: " + p[0], Toast.LENGTH_SHORT).show();
                    });
                    row.addView(delBtn);
                }
                row.setOnClickListener(v -> {
                    latInput.setText(p[1]);
                    lngInput.setText(p[2]);
                    locNameInput.setText(p[0]);
                    try {
                        checkinLat = Double.parseDouble(p[1]);
                        checkinLng = Double.parseDouble(p[2]);
                    } catch (Throwable ignored) {}
                    checkinLocationName = p[0];
                    checkinUseRealGps = false;
                    saveCheckinConfig();
                    Toast.makeText(ctx, "已保存: " + p[0], Toast.LENGTH_SHORT).show();
                    // 斗界彩蛋：Toast 提示
                    if ("斗界".equals(p[0])) {
                        Toast.makeText(ctx, "斗界 —— 5.0600, 201.5000", Toast.LENGTH_SHORT).show();
                    }
                });
                presetList.addView(row, rowLp);
            }
            // 限制最大高度约 200dp，超出可滑动
            presetScroll.addView(presetList, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
            android.widget.FrameLayout.LayoutParams scrollLp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200, ctx.getResources().getDisplayMetrics()));
            LinearLayout.LayoutParams card4Spacing = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            card4Spacing.topMargin = pad/2;
            card4.addView(presetScroll, scrollLp);
            layout.addView(card4, card4Spacing);

            // 外层滚动容器包裹所有卡片
            rootScroll.addView(layout, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));

            builder.setView(rootScroll);
            // 开关切换时立即保存并生效
            sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                checkinEnabled = isChecked;
                checkinHour = timePicker.getCurrentHour();
                checkinMinute = timePicker.getCurrentMinute();
                checkinWeekly = freqGroup.getCheckedRadioButtonId() == weekly.getId();
                try {
                    checkinLat = Double.parseDouble(latInput.getText().toString());
                    checkinLng = Double.parseDouble(lngInput.getText().toString());
                } catch (Throwable ignored) {}
                checkinLocationName = locNameInput.getText().toString();
                saveCheckinConfig();
                if (isChecked) {
                    scheduleCheckin(null);
                    // 一键配置：开启时自动打开表单 → 捕获Cookie → API打卡 → 关闭页面
                    Toast.makeText(ctx, "首次配置中：自动打卡...", Toast.LENGTH_LONG).show();
                    deployCheckinWorker();
                    firstSetupPending = true;
                    checkinSubmitted = false;
                    // 写标记文件（跨进程：WPS 多进程 static 不共享，用私有目录文件）
                    try { saveRootFile(getDataDir() + "/wps-first-setup", "true"); } catch (Throwable t) {}
                    try {
                        Intent formIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(CHECKIN_FORM_URL));
                        formIntent.setClassName("com.wps.koa", "com.wps.koa.ui.router.RouterActivity");
                        formIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        ctx.startActivity(formIntent);
                    } catch (Throwable t) { log("FIRST_SETUP=" + t.getMessage()); }
                } else {
                    cancelCheckin();
                    Toast.makeText(ctx, "自动打卡已关闭", Toast.LENGTH_SHORT).show();
                }
            });
            builder.setPositiveButton(null, null);
            builder.show();
        } catch (Throwable t) { log("CHECKIN_DIALOG=" + t.getMessage()); }
    }

    // 广播接收器：接收定时打卡触发
    public static class CheckinReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, android.content.Intent intent) {
            log("CHECKIN_TRIGGERED ctx=" + (appContext != null));
            if (!checkinEnabled) return;

            if (appContext != null) {
                // WPS 进程活着，直接打卡
                new Thread(() -> doCheckin()).start();
            } else {
                // WPS 进程不可用（无法在此进程内重启，等待下次 WPS 启动触发）
                log("CHECKIN: appContext null, skip (wait for next WPS launch)");
            }

            // 5分钟后重试检查
            new Thread(() -> {
                try { Thread.sleep(5 * 60 * 1000); } catch (InterruptedException e) { return; }
                if (!checkinEnabled) return;
                try {
                    File logFile = new File(CHECKIN_LOG_FILE);
                    if (logFile.exists()) {
                        BufferedReader br = new BufferedReader(new java.io.FileReader(logFile));
                        String lastLine = null, line;
                        while ((line = br.readLine()) != null) lastLine = line;
                        br.close();
                        if (lastLine != null) {
                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA);
                            String nowPrefix = sdf.format(new java.util.Date());
                            if (!lastLine.startsWith(nowPrefix.substring(0, 13))) {
                                log("CHECKIN_RETRY: no recent record, retrying");
                                doCheckin();
                            }
                        }
                    } else {
                        log("CHECKIN_RETRY: no log file, retrying");
                        doCheckin();
                    }
                } catch (Throwable t) { log("CHECKIN_RETRY=" + t.getMessage()); }
            }).start();

            // 重新调度下一次
            scheduleCheckin(null);
        }
    }

    // 静默打卡 Service：am startservice 启动，无 UI，直接调 API
    public static class CheckinService extends android.app.Service {
        @Override
        public int onStartCommand(android.content.Intent intent, int flags, int startId) {
            log("CHECKIN_SERVICE triggered");
            if (!checkinEnabled) { stopSelf(); return START_NOT_STICKY; }
            new Thread(() -> {
                try {
                    // 等模块初始化完成（appContext 可能还没设好）
                    for (int i = 0; i < 20 && appContext == null; i++) Thread.sleep(500);
                    if (appContext == null) { log("CHECKIN_SERVICE: no context"); stopSelf(); return; }
                    doCheckin();
                } catch (Throwable t) { log("CHECKIN_SERVICE=" + t.getMessage()); }
                finally { stopSelf(); }
            }).start();
            return START_NOT_STICKY;
        }
        @Override
        public android.os.IBinder onBind(android.content.Intent intent) { return null; }
    }
}
