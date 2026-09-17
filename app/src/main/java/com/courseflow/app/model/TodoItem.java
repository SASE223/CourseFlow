package com.courseflow.app.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public final class TodoItem {
    public String id = UUID.randomUUID().toString();
    public String title = "";
    public String note = "";
    public int week = 1;
    public int day = 1;
    public int startPeriod = 1;
    public int endPeriod = 1;
    public boolean completed = false;
    public int color = 0xFFF59E0B;

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("title", title);
        object.put("note", note);
        object.put("week", week);
        object.put("day", day);
        object.put("start", startPeriod);
        object.put("end", endPeriod);
        object.put("completed", completed);
        object.put("color", color);
        return object;
    }

    public static TodoItem fromJson(JSONObject object) {
        TodoItem todo = new TodoItem();
        todo.id = object.optString("id", UUID.randomUUID().toString());
        todo.title = object.optString("title", "未命名待办");
        todo.note = object.optString("note", "");
        todo.week = clamp(object.optInt("week", 1), 1, 30);
        todo.day = clamp(object.optInt("day", 1), 1, 7);
        todo.startPeriod = clamp(object.optInt("start", 1), 1, 24);
        todo.endPeriod = clamp(object.optInt("end", todo.startPeriod), todo.startPeriod, 24);
        todo.completed = object.optBoolean("completed", false);
        todo.color = object.optInt("color", colorFor(todo.title));
        return todo;
    }

    public static int colorFor(String title) {
        int[] palette = {0xFFF59E0B, 0xFFEC4899, 0xFF8B5CF6, 0xFF14B8A6, 0xFFF97316, 0xFF06B6D4};
        return palette[Math.floorMod(title == null ? 0 : title.hashCode(), palette.length)];
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}


