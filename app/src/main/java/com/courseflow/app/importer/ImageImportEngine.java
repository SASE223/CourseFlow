package com.courseflow.app.importer;

import android.content.Context;
import android.graphics.Rect;
import android.net.Uri;

import com.courseflow.app.model.Course;
import com.courseflow.app.model.InboxItem;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ImageImportEngine {
    private static final Pattern DAY_PATTERN = Pattern.compile("(?:星期|周)([一二三四五六日天])");
    private static final Pattern WEEK_PATTERN = Pattern.compile("第?\\s*(\\d{1,2})\\s*周");
    private static final Pattern TIME_PATTERN = Pattern.compile("([01]?\\d|2[0-3])\\s*[:：]\\s*([0-5]\\d)");

    private ImageImportEngine() {}

    public interface Callback {
        void onSuccess(Result result);
        void onError(Exception error);
    }

    public static final class Block {
        public final String text;
        public final Rect bounds;

        Block(String text, Rect bounds) {
            this.text = text;
            this.bounds = bounds;
        }
    }

    public static final class Result {
        public final String fullText;
        public final int imageWidth;
        public final int imageHeight;
        public final ArrayList<Block> blocks;

        Result(String fullText, int imageWidth, int imageHeight, ArrayList<Block> blocks) {
            this.fullText = fullText == null ? "" : fullText.trim();
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.blocks = blocks;
        }
    }

    public static final class TimetableResult {
        public final ArrayList<Course> courses = new ArrayList<>();
        public String warning = "图片识别可能有遗漏或节次偏差，请逐条核对后再保存。";
    }

    public static void recognize(Context context, Uri uri, Callback callback) {
        try {
            InputImage image = InputImage.fromFilePath(context, uri);
            TextRecognizer recognizer = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build());
            recognizer.process(image)
                    .addOnSuccessListener(text -> {
                        ArrayList<Block> blocks = new ArrayList<>();
                        for (Text.TextBlock textBlock : text.getTextBlocks()) {
                            Rect bounds = textBlock.getBoundingBox();
                            if (bounds != null && !textBlock.getText().trim().isEmpty()) {
                                blocks.add(new Block(textBlock.getText().trim(), new Rect(bounds)));
                            }
                        }
                        callback.onSuccess(new Result(text.getText(), image.getWidth(), image.getHeight(), blocks));
                        recognizer.close();
                    })
                    .addOnFailureListener(error -> {
                        callback.onError(error);
                        recognizer.close();
                    });
        } catch (Exception error) {
            callback.onError(error);
        }
    }

    public static InboxItem toInboxItem(Result result, Uri uri, String sourceLabel, int fallbackWeek) {
        InboxItem item = new InboxItem();
        item.ocrText = result.fullText;
        item.sourceUri = uri == null ? "" : uri.toString();
        item.sourceLabel = sourceLabel;
        item.suggestedWeek = clamp(fallbackWeek, 1, 30);
        item.title = chooseTitle(result.fullText);

        Matcher week = WEEK_PATTERN.matcher(result.fullText);
        if (week.find()) item.suggestedWeek = clamp(parseInt(week.group(1), fallbackWeek), 1, 30);
        Matcher day = DAY_PATTERN.matcher(result.fullText);
        if (day.find()) item.suggestedDay = dayNumber(day.group(1));
        Matcher time = TIME_PATTERN.matcher(result.fullText);
        if (time.find()) {
            int hour = parseInt(time.group(1), 8);
            int minute = parseInt(time.group(2), 30);
            item.suggestedStartPeriod = nearestPeriod(hour * 60 + minute);
            item.suggestedEndPeriod = item.suggestedStartPeriod;
        }
        return item;
    }

    public static TimetableResult toTimetable(Result result) {
        TimetableResult output = new TimetableResult();
        if (result.blocks.isEmpty() || result.imageWidth <= 0 || result.imageHeight <= 0) return output;

        float[] dayX = new float[8];
        int[] dayCounts = new int[8];
        int headerBottom = Integer.MAX_VALUE;
        for (Block block : result.blocks) {
            Matcher matcher = DAY_PATTERN.matcher(block.text.replace(" ", ""));
            while (matcher.find()) {
                int day = dayNumber(matcher.group(1));
                dayX[day] += block.bounds.exactCenterX();
                dayCounts[day]++;
                headerBottom = Math.min(headerBottom, block.bounds.bottom);
            }
        }
        int availableDays = 0;
        for (int day = 1; day <= 7; day++) {
            if (dayCounts[day] > 0) {
                dayX[day] /= dayCounts[day];
                availableDays++;
            }
        }
        if (availableDays < 3 || headerBottom == Integer.MAX_VALUE) {
            output.warning = "没有找到足够的星期列，未自动写入课程；已保留识别文字供待整理。";
            return output;
        }

        float bodyBottom = result.imageHeight * 0.90f;
        float rowHeight = Math.max(28f, (bodyBottom - headerBottom) / 12f);
        float averageColumnWidth = result.imageWidth / 7f;
        HashSet<String> dedupe = new HashSet<>();
        for (Block block : result.blocks) {
            String compact = block.text.replace(" ", "").trim();
            if (block.bounds.top <= headerBottom || isNoise(compact) || DAY_PATTERN.matcher(compact).find()) continue;
            int day = nearestDay(block.bounds.exactCenterX(), dayX, dayCounts);
            if (day == 0 || Math.abs(block.bounds.exactCenterX() - dayX[day]) > averageColumnWidth * 0.72f) continue;

            String name = chooseCourseName(block.text);
            if (name.length() < 2) continue;
            int start = clamp(Math.round((block.bounds.top - headerBottom) / rowHeight) + 1, 1, 12);
            int end = clamp((int) Math.ceil((block.bounds.bottom - headerBottom) / rowHeight), start, 12);
            if (end - start > 5) end = Math.min(12, start + 1);
            String key = day + ":" + start + ":" + end + ":" + name;
            if (!dedupe.add(key)) continue;
            Course course = new Course();
            course.name = name;
            course.day = day;
            course.startPeriod = start;
            course.endPeriod = end;
            for (int week = 1; week <= 20; week++) course.weeks.add(week);
            course.color = Course.colorFor(name);
            output.courses.add(course);
        }
        if (output.courses.isEmpty()) {
            output.warning = "检测到了星期列，但没有可靠的课程色块；已保留识别文字供待整理。";
        }
        return output;
    }

    private static int nearestDay(float x, float[] dayX, int[] dayCounts) {
        int bestDay = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int day = 1; day <= 7; day++) {
            if (dayCounts[day] == 0) continue;
            float distance = Math.abs(x - dayX[day]);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestDay = day;
            }
        }
        return bestDay;
    }

    private static boolean isNoise(String value) {
        if (value.isEmpty() || value.matches("[0-9:：./\\-]+") || value.length() > 90) return true;
        String lower = value.toLowerCase(Locale.ROOT);
        String[] noise = {"我的课表", "导入", "设置", "待办", "本周", "显示", "左右滑动", "课程", "其他课程"};
        for (String token : noise) if (lower.equals(token) || lower.startsWith(token + "（")) return true;
        return false;
    }

    private static String chooseCourseName(String text) {
        for (String line : text.split("\\r?\\n")) {
            String value = line.trim().replace('★', ' ').trim();
            if (value.length() < 2 || value.matches("[0-9:：./\\-]+")) continue;
            if (value.contains("教学楼") || value.contains("校区") || value.matches(".*\\d{3,}.*")) continue;
            return value.length() > 24 ? value.substring(0, 24) : value;
        }
        return "";
    }

    private static String chooseTitle(String text) {
        if (text == null || text.trim().isEmpty()) return "待整理图片";
        String fallback = "";
        for (String line : text.split("\\r?\\n")) {
            String value = line.trim();
            if (value.length() < 2 || value.matches("[0-9:：./\\-]+")) continue;
            if (fallback.isEmpty()) fallback = value;
            if (value.matches(".*(作业|提交|考试|测验|报名|讲座|活动|会议|通知|截止).*")) {
                return shorten(value, 30);
            }
        }
        return fallback.isEmpty() ? "待整理图片" : shorten(fallback, 30);
    }

    private static String shorten(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static int dayNumber(String value) {
        String days = "一二三四五六日";
        if ("天".equals(value)) return 7;
        int index = days.indexOf(value);
        return index < 0 ? 1 : index + 1;
    }

    private static int nearestPeriod(int minutes) {
        int[] starts = {510, 555, 605, 650, 810, 855, 905, 950, 1120, 1165, 1210, 1255};
        int best = 1;
        int distance = Integer.MAX_VALUE;
        for (int i = 0; i < starts.length; i++) {
            int current = Math.abs(minutes - starts[i]);
            if (current < distance) {
                distance = current;
                best = i + 1;
            }
        }
        return best;
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
