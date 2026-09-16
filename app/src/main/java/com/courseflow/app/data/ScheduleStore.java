package com.courseflow.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.courseflow.app.model.ScheduleData;

import org.json.JSONObject;

public final class ScheduleStore {
    private static final String FILE = "courseflow_store";
    private static final String KEY_DATA = "schedule_json";
    private static final String KEY_WEEK = "selected_week";

    private final SharedPreferences preferences;

    public ScheduleStore(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public ScheduleData load() {
        String value = preferences.getString(KEY_DATA, "");
        if (value == null || value.isEmpty()) return ScheduleData.empty();
        try {
            ScheduleData data = ScheduleData.fromJson(new JSONObject(value));
            migrateLegacyDemo(data);
            return data;
        } catch (Exception ignored) {
            return ScheduleData.empty();
        }
    }

    private void migrateLegacyDemo(ScheduleData data) {
        if (!data.className.startsWith("示例课表")) return;
        data.courses.removeIf(course -> isLegacyDemoCourse(course.name));
        data.className = data.className.replace("示例课表 + ", "");
        if (data.className.equals("示例课表")) data.className = "尚未导入课表";
        save(data);
    }

    private boolean isLegacyDemoCourse(String name) {
        return name.equals("数字电子技术Ⅰ")
                || name.equals("马克思主义基本原理")
                || name.equals("普通物理Ⅰ（下）")
                || name.equals("创新与创业基础")
                || name.equals("大学英语Ⅲ")
                || name.equals("人工智能基础")
                || name.equals("复变函数与积分变换");
    }

    public void save(ScheduleData data) {
        try {
            preferences.edit().putString(KEY_DATA, data.toJson().toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public int loadWeek(int fallback) {
        return Math.max(1, Math.min(24, preferences.getInt(KEY_WEEK, fallback)));
    }

    public void saveWeek(int week) {
        preferences.edit().putInt(KEY_WEEK, week).apply();
    }
}


