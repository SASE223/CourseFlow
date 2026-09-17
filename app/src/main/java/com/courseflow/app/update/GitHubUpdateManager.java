package com.courseflow.app.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.courseflow.app.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GitHubUpdateManager {
    private static final String API_URL = "https://api.github.com/repos/SASE223/CourseFlow/releases/latest";
    private static final String RELEASES_URL = "https://github.com/SASE223/CourseFlow/releases/latest";
    private static final String PREFS = "courseflow_updates";
    private static final String KEY_AUTO = "auto_check";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_LAST_CHECK_APP_VERSION = "last_check_app_version";
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_DOWNLOAD_DIGEST = "download_digest";
    private static final long CHECK_INTERVAL = 5L * 60L * 1000L;

    private final Activity activity;
    private final SharedPreferences preferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean checking;
    private boolean receiverRegistered;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            long completed = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (completed == preferences.getLong(KEY_DOWNLOAD_ID, -2L)) resumePendingInstall();
        }
    };

    public GitHubUpdateManager(Activity activity) {
        this.activity = activity;
        this.preferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void start() {
        String lastCheckedAppVersion = preferences.getString(KEY_LAST_CHECK_APP_VERSION, "");
        if (!BuildConfig.VERSION_NAME.equals(lastCheckedAppVersion)) {
            preferences.edit()
                    .remove(KEY_LAST_CHECK)
                    .putString(KEY_LAST_CHECK_APP_VERSION, BuildConfig.VERSION_NAME)
                    .apply();
        }
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            ContextCompat.registerReceiver(activity, downloadReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        handler.postDelayed(() -> check(false), 2100L);
    }

    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            try { activity.unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        executor.shutdownNow();
    }

    public void onResume() {
        resumePendingInstall();
    }

    public String statusText() {
        return (preferences.getBoolean(KEY_AUTO, true) ? "自动提醒已开启" : "仅检查强制更新")
                + " · 当前 " + BuildConfig.VERSION_NAME;
    }

    public void showOptions() {
        boolean auto = preferences.getBoolean(KEY_AUTO, true);
        new AlertDialog.Builder(activity)
                .setTitle("软件更新")
                .setMessage("启动后立即检查 GitHub。关闭自动提醒后，仍会检查不可跳过的强制更新；不会上传课表、图片或识别文字。")
                .setItems(new String[]{"立即检查更新", auto ? "关闭自动检查" : "开启自动检查", "打开 GitHub Releases"},
                        (dialog, which) -> {
                            if (which == 0) check(true);
                            else if (which == 1) {
                                preferences.edit().putBoolean(KEY_AUTO, !auto).apply();
                                Toast.makeText(activity, !auto ? "自动检查更新已开启" : "自动检查更新已关闭", Toast.LENGTH_SHORT).show();
                            } else openBrowser(RELEASES_URL);
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    public void check(boolean manual) {
        if (checking) {
            if (manual) Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean autoEnabled = preferences.getBoolean(KEY_AUTO, true);
        if (!manual) {
            long last = preferences.getLong(KEY_LAST_CHECK, 0L);
            if (System.currentTimeMillis() - last < CHECK_INTERVAL) return;
        }
        checking = true;
        if (manual) Toast.makeText(activity, "正在连接 GitHub 检查更新…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                ReleaseInfo release = fetchLatestRelease();
                preferences.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
                activity.runOnUiThread(() -> {
                    checking = false;
                    boolean newer = isNewer(release.version, BuildConfig.VERSION_NAME);
                    boolean mandatory = newer && isMandatory(release, BuildConfig.VERSION_NAME);
                    if (newer && (manual || autoEnabled || mandatory)) showUpdate(release, mandatory);
                    else if (manual) new AlertDialog.Builder(activity)
                            .setTitle("已是最新版本")
                            .setMessage("当前版本：" + BuildConfig.VERSION_NAME)
                            .setPositiveButton("知道了", null)
                            .show();
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    checking = false;
                    if (manual) new AlertDialog.Builder(activity)
                            .setTitle("检查更新失败")
                            .setMessage("无法连接 GitHub。请检查网络，或稍后重试。\n\n" + safeMessage(error))
                            .setNegativeButton("取消", null)
                            .setPositiveButton("打开发布页", (dialog, which) -> openBrowser(RELEASES_URL))
                            .show();
                });
            }
        });
    }

    private ReleaseInfo fetchLatestRelease() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
        connection.setConnectTimeout(9000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "CourseFlow-Android/" + BuildConfig.VERSION_NAME);
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        int code = connection.getResponseCode();
        if (code != 200) throw new IllegalStateException("GitHub 返回状态码 " + code);
        StringBuilder json = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) json.append(line);
        } finally {
            connection.disconnect();
        }
        JSONObject root = new JSONObject(json.toString());
        ReleaseInfo info = new ReleaseInfo();
        info.version = normalizeVersion(root.optString("tag_name", ""));
        info.title = root.optString("name", "v" + info.version);
        info.notes = root.optString("body", "本次更新未填写说明。");
        info.force = containsDirective(info.notes, "force_update");
        info.minSupportedVersion = directiveValue(info.notes, "min_supported_version");
        info.pageUrl = root.optString("html_url", RELEASES_URL);
        JSONArray assets = root.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;
                String name = asset.optString("name", "");
                if (!name.toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
                info.downloadUrl = asset.optString("browser_download_url", "");
                info.fileName = name;
                info.size = asset.optLong("size", 0L);
                info.digest = asset.optString("digest", "");
                break;
            }
        }
        if (info.version.isEmpty()) throw new IllegalStateException("发行版没有有效版本号");
        return info;
    }

    private void showUpdate(ReleaseInfo release, boolean mandatory) {
        String sizeText = release.size > 0 ? String.format(Locale.CHINA, "%.1f MB", release.size / 1024d / 1024d) : "未知";
        String message = (mandatory ? "这是必须安装的兼容性更新，更新前无法继续使用。\n\n" : "")
                + "当前版本：" + BuildConfig.VERSION_NAME + "\n最新版本：" + release.version
                + "\n安装包：" + sizeText + "\n\n" + trimNotes(release.notes);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle((mandatory ? "必须更新 · " : "发现新版本 · ") + release.title)
                .setMessage(message)
                .setCancelable(!mandatory);
        if (!mandatory) {
            builder.setNegativeButton("以后再说", null)
                    .setNeutralButton("查看发布页", (d, which) -> openBrowser(release.pageUrl));
        }
        if (release.downloadUrl.isEmpty()) {
            builder.setPositiveButton("打开发布页", (d, which) -> openBrowser(release.pageUrl));
        } else {
            builder.setPositiveButton(mandatory ? "下载并更新" : "下载更新", (d, which) -> download(release));
        }
        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(!mandatory);
        dialog.show();
    }

    private void download(ReleaseInfo release) {
        try {
            DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(release.downloadUrl));
            request.setTitle("课表流 " + release.version);
            request.setDescription("正在下载更新安装包");
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS,
                    release.fileName.isEmpty() ? "CourseFlow-" + release.version + ".apk" : release.fileName);
            long id = manager.enqueue(request);
            preferences.edit()
                    .putLong(KEY_DOWNLOAD_ID, id)
                    .putString(KEY_DOWNLOAD_DIGEST, release.digest)
                    .apply();
            Toast.makeText(activity, "已开始下载，完成后会打开系统安装界面", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            new AlertDialog.Builder(activity)
                    .setTitle("无法开始下载")
                    .setMessage("将改为在浏览器中打开发布页。")
                    .setPositiveButton("继续", (dialog, which) -> openBrowser(release.pageUrl))
                    .show();
        }
    }

    private void resumePendingInstall() {
        long id = preferences.getLong(KEY_DOWNLOAD_ID, -1L);
        if (id < 0) return;
        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
        try (android.database.Cursor cursor = manager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) return;
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_FAILED) {
                preferences.edit().remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_DIGEST).apply();
                Toast.makeText(activity, "更新下载失败，请在设置中重试", Toast.LENGTH_LONG).show();
                return;
            }
            if (status != DownloadManager.STATUS_SUCCESSFUL) return;
        }
        Uri uri = manager.getUriForDownloadedFile(id);
        if (uri == null) return;
        String expected = preferences.getString(KEY_DOWNLOAD_DIGEST, "");
        executor.execute(() -> {
            boolean valid = verifyDigest(uri, expected);
            activity.runOnUiThread(() -> {
                if (!valid) {
                    preferences.edit().remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_DIGEST).apply();
                    new AlertDialog.Builder(activity)
                            .setTitle("安装包校验失败")
                            .setMessage("下载文件与 GitHub 提供的 SHA-256 不一致，已阻止安装。请重新下载。")
                            .setPositiveButton("知道了", null)
                            .show();
                    return;
                }
                requestInstall(uri);
            });
        });
    }

    private boolean verifyDigest(Uri uri, String expected) {
        if (expected == null || !expected.toLowerCase(Locale.ROOT).startsWith("sha256:")) return true;
        String wanted = expected.substring("sha256:".length()).trim();
        try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
            if (input == null) return false;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            StringBuilder actual = new StringBuilder();
            for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value));
            return wanted.equalsIgnoreCase(actual.toString());
        } catch (Exception error) {
            return false;
        }
    }

    private void requestInstall(Uri uri) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("允许安装更新")
                    .setMessage("Android 需要你为“课表流”开启一次“允许安装未知应用”。返回后会继续打开安装界面。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("去设置", (dialog, which) -> activity.startActivity(new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()))))
                    .show();
            return;
        }
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            preferences.edit().remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_DIGEST).apply();
            activity.startActivity(install);
        } catch (Exception error) {
            openBrowser(RELEASES_URL);
        }
    }

    private void openBrowser(String url) {
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception ignored) { Toast.makeText(activity, url, Toast.LENGTH_LONG).show(); }
    }

    private static boolean isMandatory(ReleaseInfo release, String current) {
        if (release.force) return true;
        if (!release.minSupportedVersion.isEmpty() && isNewer(release.minSupportedVersion, current)) return true;
        int[] latest = versionParts(release.version);
        int[] installed = versionParts(current);
        return latest.length > 0 && installed.length > 0 && latest[0] > installed[0];
    }

    private static boolean containsDirective(String notes, String key) {
        String normalized = notes == null ? "" : notes.toLowerCase(Locale.ROOT).replace(" ", "");
        return normalized.contains(key.toLowerCase(Locale.ROOT) + "=true")
                || normalized.contains("[" + key.toLowerCase(Locale.ROOT) + "]");
    }

    private static String directiveValue(String notes, String key) {
        if (notes == null) return "";
        for (String line : notes.split("\\r?\\n")) {
            String compact = line.trim().replace("<!--", "").replace("-->", "").trim();
            int separator = Math.max(compact.indexOf('='), compact.indexOf(':'));
            if (separator <= 0) continue;
            String name = compact.substring(0, separator).trim();
            if (name.equalsIgnoreCase(key)) return normalizeVersion(compact.substring(separator + 1).trim());
        }
        return "";
    }

    private static boolean isNewer(String latest, String current) {
        int[] left = versionParts(latest);
        int[] right = versionParts(current);
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    private static int[] versionParts(String value) {
        String normalized = normalizeVersion(value);
        String[] parts = normalized.split("\\.");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { values[i] = Integer.parseInt(parts[i].replaceAll("[^0-9]", "")); }
            catch (Exception ignored) { values[i] = 0; }
        }
        return values;
    }

    private static String normalizeVersion(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) normalized = normalized.substring(1);
        return normalized;
    }

    private static String trimNotes(String notes) {
        if (notes == null || notes.trim().isEmpty()) return "本次更新未填写说明。";
        StringBuilder cleaned = new StringBuilder();
        for (String line : notes.trim().split("\\r?\\n")) {
            String compact = line.toLowerCase(Locale.ROOT).replace(" ", "");
            if (compact.contains("force_update") || compact.contains("min_supported_version")) continue;
            if (cleaned.length() > 0) cleaned.append('\n');
            cleaned.append(line);
        }
        String value = cleaned.toString().trim();
        if (value.isEmpty()) value = "这是维护兼容性与稳定性的必要更新。";
        return value.length() <= 1200 ? value : value.substring(0, 1200) + "…";
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "网络连接异常" : message;
    }

    private static final class ReleaseInfo {
        String version = "";
        String title = "";
        String notes = "";
        String pageUrl = RELEASES_URL;
        String downloadUrl = "";
        String fileName = "";
        String digest = "";
        String minSupportedVersion = "";
        boolean force;
        long size;
    }
}


