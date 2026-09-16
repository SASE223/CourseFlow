package com.courseflow.app.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public final class InboxItem {
    public String id = UUID.randomUUID().toString();
    public String title = "待整理图片";
    public String ocrText = "";
    public String sourceUri = "";
    public String sourceLabel = "图片导入";
    public long createdAt = System.currentTimeMillis();
    public int suggestedWeek = 1;
    public int suggestedDay = 1;
    public int suggestedStartPeriod = 1;
    public int suggestedEndPeriod = 1;

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("title", title);
        object.put("ocrText", ocrText);
        object.put("sourceUri", sourceUri);
        object.put("sourceLabel", sourceLabel);
        object.put("createdAt", createdAt);
        object.put("suggestedWeek", suggestedWeek);
        object.put("suggestedDay", suggestedDay);
        object.put("suggestedStart", suggestedStartPeriod);
        object.put("suggestedEnd", suggestedEndPeriod);
        return object;
    }

    public static InboxItem fromJson(JSONObject object) {
        InboxItem item = new InboxItem();
        item.id = object.optString("id", UUID.randomUUID().toString());
        item.title = object.optString("title", "待整理图片");
        item.ocrText = object.optString("ocrText", "");
        item.sourceUri = object.optString("sourceUri", "");
        item.sourceLabel = object.optString("sourceLabel", "图片导入");
        item.createdAt = object.optLong("createdAt", System.currentTimeMillis());
        item.suggestedWeek = clamp(object.optInt("suggestedWeek", 1), 1, 30);
        item.suggestedDay = clamp(object.optInt("suggestedDay", 1), 1, 7);
        item.suggestedStartPeriod = clamp(object.optInt("suggestedStart", 1), 1, 12);
        item.suggestedEndPeriod = clamp(object.optInt("suggestedEnd", item.suggestedStartPeriod), item.suggestedStartPeriod, 12);
        return item;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
