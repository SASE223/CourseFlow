package com.courseflow.app.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Course {
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String teacher = "";
    public String room = "";
    public int day = 1;
    public int startPeriod = 1;
    public int endPeriod = 2;
    public final ArrayList<Integer> weeks = new ArrayList<>();
    public int color = 0xFF5B8DEF;

    public boolean isActiveInWeek(int week) {
        return weeks.isEmpty() || weeks.contains(week);
    }

    public String weeksText() {
        if (weeks.isEmpty()) return "全学期";
        StringBuilder out = new StringBuilder();
        int start = weeks.get(0);
        int previous = start;
        for (int index = 1; index <= weeks.size(); index++) {
            int current = index < weeks.size() ? weeks.get(index) : Integer.MIN_VALUE;
            if (current == previous + 1) {
                previous = current;
                continue;
            }
            if (out.length() > 0) out.append(',');
            out.append(start);
            if (previous != start) out.append('-').append(previous);
            start = current;
            previous = current;
        }
        return out.toString();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("name", name);
        object.put("teacher", teacher);
        object.put("room", room);
        object.put("day", day);
        object.put("start", startPeriod);
        object.put("end", endPeriod);
        object.put("color", color);
        JSONArray values = new JSONArray();
        for (Integer week : weeks) values.put(week);
        object.put("weeks", values);
        return object;
    }

    public static Course fromJson(JSONObject object) throws JSONException {
        Course course = new Course();
        course.id = object.optString("id", UUID.randomUUID().toString());
        course.name = object.optString("name", "未命名课程");
        course.teacher = object.optString("teacher", "");
        course.room = object.optString("room", "");
        course.day = clamp(object.optInt("day", 1), 1, 7);
        course.startPeriod = clamp(object.optInt("start", 1), 1, 12);
        course.endPeriod = clamp(object.optInt("end", course.startPeriod), course.startPeriod, 12);
        course.color = object.optInt("color", colorFor(course.name));
        JSONArray values = object.optJSONArray("weeks");
        if (values != null) {
            for (int i = 0; i < values.length(); i++) {
                int week = values.optInt(i, -1);
                if (week > 0 && week <= 30 && !course.weeks.contains(week)) course.weeks.add(week);
            }
        }
        course.weeks.sort(Integer::compareTo);
        return course;
    }

    public static ArrayList<Integer> parseWeeks(String value) {
        ArrayList<Integer> weeks = new ArrayList<>();
        if (value == null) return weeks;
        String normalized = value.replace('，', ',').replace('、', ',')
                .replace("周", "").replace("第", "").replace(" ", "");
        for (String token : normalized.split(",")) {
            if (token.isEmpty()) continue;
            String[] range = token.split("[-—~至]");
            try {
                int first = Integer.parseInt(range[0].replaceAll("[^0-9]", ""));
                int last = range.length > 1
                        ? Integer.parseInt(range[range.length - 1].replaceAll("[^0-9]", "")) : first;
                if (first > last) { int swap = first; first = last; last = swap; }
                for (int week = Math.max(1, first); week <= Math.min(30, last); week++) {
                    if (!weeks.contains(week)) weeks.add(week);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        weeks.sort(Integer::compareTo);
        return weeks;
    }

    public static int colorFor(String name) {
        int[] palette = {
                0xFF5B8DEF, 0xFF8BC34A, 0xFF9C6ADE, 0xFF13A389,
                0xFFF5A623, 0xFFEE6A76, 0xFF46A6C8, 0xFF5BBE68,
                0xFFEF7FA2, 0xFF7C8CF8
        };
        int hash = name == null ? 0 : name.hashCode();
        return palette[Math.floorMod(hash, palette.length)];
    }

    public static int countConflicts(List<Course> courses, Course target, int week) {
        int count = 0;
        for (Course other : courses) {
            if (other == target || other.day != target.day) continue;
            if (!other.isActiveInWeek(week) || !target.isActiveInWeek(week)) continue;
            if (other.startPeriod <= target.endPeriod && target.startPeriod <= other.endPeriod) count++;
        }
        return count;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
