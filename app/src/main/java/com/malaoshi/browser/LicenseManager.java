package com.malaoshi.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 「马老师浏览器」的试用与授权。
 *
 * 试用期由服务端按「设备 + 应用」记录，本地只做缓存，因此清数据 / 重装都不会
 * 重新开始三天倒计时。设备码优先取 ANDROID_ID（卸载重装不变）。
 */
final class LicenseManager {

    static final String PRODUCT = "malaoshi-browser";
    static final String BASE_URL = "https://sink.8818618.xyz";
    static final int TRIAL_DAYS = 3;

    private static final String PREFS = "malaoshi_license";
    private static final String KEY_DEVICE = "device_id";
    private static final String KEY_TOKEN = "license_token";
    private static final String KEY_ACTIVATED = "activated";
    private static final String KEY_TRIAL_ENDS = "trial_ends_at";
    private static final String KEY_CHECKED_AT = "checked_at";

    private LicenseManager() {
    }

    // ---------------------------------------------------------------- 本地状态

    static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 设备码：ANDROID_ID 拿不到时退回本地随机 UUID。 */
    static String deviceId(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String saved = p.getString(KEY_DEVICE, null);
        if (!TextUtils.isEmpty(saved)) return saved;

        String id = null;
        try {
            id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception ignored) {
        }
        if (TextUtils.isEmpty(id) || "9774d56d682e549c".equals(id)) {
            id = "uuid" + UUID.randomUUID().toString().replace("-", "");
        }
        p.edit().putString(KEY_DEVICE, id).apply();
        return id;
    }

    static boolean isActivated(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ACTIVATED, false);
    }

    static String token(Context ctx) {
        return prefs(ctx).getString(KEY_TOKEN, null);
    }

    static long trialEndsAt(Context ctx) {
        return prefs(ctx).getLong(KEY_TRIAL_ENDS, 0L);
    }

    static boolean trialValid(Context ctx) {
        long ends = trialEndsAt(ctx);
        return ends > 0 && System.currentTimeMillis() / 1000L < ends;
    }

    static int trialDaysLeft(Context ctx) {
        long remains = trialEndsAt(ctx) - System.currentTimeMillis() / 1000L;
        if (remains <= 0) return 0;
        return (int) Math.ceil(remains / 86400.0);
    }

    // ------------------------------------------------------------------ 结果类

    static final class Status {
        boolean reachable;      // 是否成功连上授权服务器
        boolean activated;      // 已授权
        boolean trialExpired;   // 试用已到期
        int daysLeft;           // 剩余试用天数
        String message;         // 出错信息
    }

    static final class ActivateResult {
        boolean ok;
        String message;
    }

    // -------------------------------------------------------------------- 网络

    static Status fetchStatus(Context ctx) {
        Status s = new Status();
        try {
            String url = BASE_URL + "/api/license/status?product=" + PRODUCT
                    + "&deviceId=" + URLEncoder.encode(deviceId(ctx), "UTF-8");
            JSONObject json = new JSONObject(httpGet(url));
            s.reachable = true;
            s.activated = json.optBoolean("activated", false);
            prefs(ctx).edit()
                    .putBoolean(KEY_ACTIVATED, s.activated)
                    .putLong(KEY_CHECKED_AT, System.currentTimeMillis() / 1000L)
                    .apply();
            if (s.activated) {
                s.daysLeft = TRIAL_DAYS;
                return s;
            }
            JSONObject trial = json.optJSONObject("trial");
            if (trial != null) {
                long endsAt = trial.optLong("endsAt", 0L);
                s.trialExpired = trial.optBoolean("expired", false);
                s.daysLeft = trial.optInt("daysLeft", 0);
                prefs(ctx).edit().putLong(KEY_TRIAL_ENDS, endsAt).apply();
            }
        } catch (Exception e) {
            s.reachable = false;
            s.activated = isActivated(ctx);
            s.daysLeft = trialDaysLeft(ctx);
            s.trialExpired = !s.activated && trialEndsAt(ctx) > 0 && !trialValid(ctx);
            s.message = e.getMessage();
        }
        return s;
    }

    static ActivateResult activate(Context ctx, String code) {
        ActivateResult r = new ActivateResult();
        String licenseCode = code == null ? "" : code.trim();
        if (licenseCode.length() < 8) {
            r.message = "请输入完整的授权码";
            return r;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("licenseCode", licenseCode);
            payload.put("deviceId", deviceId(ctx));
            payload.put("product", PRODUCT);
            payload.put("appVersion", MainActivity.APP_VERSION);

            JSONObject json = new JSONObject(httpPost(BASE_URL + "/api/license/activate", payload.toString()));
            if (!json.optBoolean("success", false)) {
                r.message = "激活失败，请稍后再试";
                return r;
            }
            prefs(ctx).edit()
                    .putBoolean(KEY_ACTIVATED, true)
                    .putString(KEY_TOKEN, json.optString("licenseToken", null))
                    .apply();
            r.ok = true;
        } catch (HttpError e) {
            r.message = TextUtils.isEmpty(e.userMessage) ? "激活失败：" + e.getMessage() : e.userMessage;
        } catch (Exception e) {
            r.message = "网络异常，请检查网络后重试";
        }
        return r;
    }

    // ----------------------------------------------------------------- HTTP 细节

    private static final class HttpError extends Exception {
        final String userMessage;

        HttpError(String detail, String userMessage) {
            super(detail);
            this.userMessage = userMessage;
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestMethod("GET");
        return readResponse(conn);
    }

    private static String httpPost(String url, String json) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn);
    }

    private static String readResponse(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        String body = sb.toString();
        if (code < 200 || code >= 300) {
            String userMessage = null;
            if (code == 400 || code == 409) userMessage = extractUserMessage(body);
            throw new HttpError("HTTP " + code + " " + body, userMessage);
        }
        return body;
    }

    /** 服务端用 createError 返回的中文提示在 statusMessage / message 字段里。 */
    private static String extractUserMessage(String body) {
        try {
            JSONObject json = new JSONObject(body);
            for (String key : new String[]{"statusMessage", "statusText", "message"}) {
                String value = json.optString(key, null);
                if (!TextUtils.isEmpty(value)) return value;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
