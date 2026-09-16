package com.courseflow.app.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;

public final class ScheduleData {
    public String title = "我的课表";
    public String className = "尚未导入课表";
    public String campus = "白云校区";
    public String sourceFile = "";
    public long semesterStartMillis = defaultSemesterStart();
    public final ArrayList<Course> courses = new ArrayList<>();
    public final ArrayList<TodoItem> todos = new ArrayList<>();
    public final ArrayList<InboxItem> inboxItems = new ArrayList<>();
    public final ArrayList<String> warnings = new ArrayList<>();

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("title", title);
        object.put("className", className);
        object.put("campus", campus);
        object.put("sourceFile", sourceFile);
        object.put("semesterStart", semesterStartMillis);
        JSONArray courseValues = new JSONArray();
        for (Course course : courses) courseValues.put(course.toJson());
        object.put("courses", courseValues);
        JSONArray todoValues = new JSONArray();
        for (TodoItem todo : todos) todoValues.put(todo.toJson());
        object.put("todos", todoValues);
        JSONArray inboxValues = new JSONArray();
        for (InboxItem item : inboxItems) inboxValues.put(item.toJson());
        object.put("inboxItems", inboxValues);
        JSONArray warningValues = new JSONArray();
        for (String warning : warnings) warningValues.put(warning);
        object.put("warnings", warningValues);
        return object;
    }

    public static ScheduleData fromJson(JSONObject object) throws JSONException {
        ScheduleData data = new ScheduleData();
        data.title = object.optString("title", "我的课表");
        data.className = object.optString("className", "我的班级");
        data.campus = object.optString("campus", "白云校区");
        data.sourceFile = object.optString("sourceFile", "");
        data.semesterStartMillis = object.optLong("semesterStart", defaultSemesterStart());
        data.courses.clear();
        JSONArray courseValues = object.optJSONArray("courses");
        if (courseValues != null) {
            for (int i = 0; i < courseValues.length(); i++) {
                JSONObject item = courseValues.optJSONObject(i);
                if (item != null) data.courses.add(Course.fromJson(item));
            }
        }
        data.todos.clear();
        JSONArray todoValues = object.optJSONArray("todos");
        if (todoValues != null) {
            for (int i = 0; i < todoValues.length(); i++) {
                JSONObject item = todoValues.optJSONObject(i);
                if (item != null) data.todos.add(TodoItem.fromJson(item));
            }
        }
        data.inboxItems.clear();
        JSONArray inboxValues = object.optJSONArray("inboxItems");
        if (inboxValues != null) {
            for (int i = 0; i < inboxValues.length(); i++) {
                JSONObject item = inboxValues.optJSONObject(i);
                if (item != null) data.inboxItems.add(InboxItem.fromJson(item));
            }
        }
        JSONArray warningValues = object.optJSONArray("warnings");
        if (warningValues != null) {
            for (int i = 0; i < warningValues.length(); i++) {
                String warning = warningValues.optString(i, "");
                if (!warning.isEmpty()) data.warnings.add(warning);
            }
        }
        return data;
    }

    public int conflictCount(int week) {
        int pairs = 0;
        for (int i = 0; i < courses.size(); i++) {
            Course first = courses.get(i);
            if (!first.isActiveInWeek(week)) continue;
            for (int j = i + 1; j < courses.size(); j++) {
                Course second = courses.get(j);
                if (!second.isActiveInWeek(week) || first.day != second.day) continue;
                if (first.startPeriod <= second.endPeriod && second.startPeriod <= first.endPeriod) pairs++;
            }
        }
        return pairs;
    }

    public static ScheduleData empty() {
        ScheduleData data = new ScheduleData();
        data.courses.clear();
        data.todos.clear();
        data.inboxItems.clear();
        data.className = "尚未导入课表";
        data.sourceFile = "";
        data.warnings.clear();
        return data;
    }

    private static long defaultSemesterStart() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(2026, Calendar.SEPTEMBER, 7, 0, 0, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
