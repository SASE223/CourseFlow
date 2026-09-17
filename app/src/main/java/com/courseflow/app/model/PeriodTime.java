package com.courseflow.app.model;

import org.json.JSONException;
import org.json.JSONObject;

public final class PeriodTime {
    public String start;
    public String end;

    public PeriodTime(String start, String end) {
        this.start = start;
        this.end = end;
    }

    public PeriodTime copy() {
        return new PeriodTime(start, end);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("start", start);
        object.put("end", end);
        return object;
    }

    public static PeriodTime fromJson(JSONObject object) {
        return new PeriodTime(object.optString("start", "08:30"), object.optString("end", "09:10"));
    }
}
