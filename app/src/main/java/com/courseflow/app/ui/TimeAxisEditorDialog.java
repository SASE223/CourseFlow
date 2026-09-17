package com.courseflow.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.courseflow.app.model.Course;
import com.courseflow.app.model.PeriodTime;
import com.courseflow.app.model.ScheduleData;
import com.courseflow.app.model.TodoItem;

import java.util.ArrayList;
import java.util.Locale;

public final class TimeAxisEditorDialog {
    private static final int BRAND = 0xFF2F80ED;
    private static final int TEXT = 0xFF202938;
    private static final int MUTED = 0xFF7D8796;
    private static final int MAX_PERIODS = 24;

    private final Activity activity;
    private final ScheduleData schedule;
    private final Runnable onSaved;
    private final ArrayList<PeriodTime> working = new ArrayList<>();
    private LinearLayout rows;
    private TextView count;

    public TimeAxisEditorDialog(Activity activity, ScheduleData schedule, Runnable onSaved) {
        this.activity = activity;
        this.schedule = schedule;
        this.onSaved = onSaved;
        for (PeriodTime period : schedule.periods) working.add(period.copy());
    }

    public void show() {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 2), Ui.dp(activity, 18), 0);

        TextView hint = Ui.text(activity, "调整节数后，点击时间即可修改。保存时会检查顺序与已有课程。", 12, MUTED, false);
        hint.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 10));
        content.addView(hint);

        LinearLayout stepper = new LinearLayout(activity);
        stepper.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = Ui.text(activity, "每天节数", 15, TEXT, true);
        stepper.addView(label, new LinearLayout.LayoutParams(0, Ui.dp(activity, 42), 1));
        TextView minus = smallButton("−");
        count = Ui.text(activity, String.valueOf(working.size()), 16, TEXT, true);
        count.setGravity(Gravity.CENTER);
        TextView plus = smallButton("+");
        stepper.addView(minus, new LinearLayout.LayoutParams(Ui.dp(activity, 42), Ui.dp(activity, 36)));
        stepper.addView(count, new LinearLayout.LayoutParams(Ui.dp(activity, 50), Ui.dp(activity, 36)));
        stepper.addView(plus, new LinearLayout.LayoutParams(Ui.dp(activity, 42), Ui.dp(activity, 36)));
        content.addView(stepper);

        rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroller = new ScrollView(activity);
        scroller.addView(rows);
        content.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 420)));

        minus.setOnClickListener(view -> removePeriod());
        plus.setOnClickListener(view -> addPeriod());
        renderRows();

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("自定义时间轴")
                .setView(content)
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复默认", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(unused -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                working.clear();
                working.addAll(ScheduleData.defaultPeriods());
                renderRows();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> save(dialog));
        });
        dialog.show();
    }

    private TextView smallButton(String value) {
        TextView button = Ui.text(activity, value, 22, BRAND, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(Ui.rounded(0xFFE6F2FF, 18, activity));
        Ui.pressable(button);
        return button;
    }

    private void addPeriod() {
        if (working.size() >= MAX_PERIODS) {
            Toast.makeText(activity, "最多支持 " + MAX_PERIODS + " 节", Toast.LENGTH_SHORT).show();
            return;
        }
        PeriodTime previous = working.get(working.size() - 1);
        int previousStart = minutes(previous.start);
        int previousEnd = minutes(previous.end);
        int duration = Math.max(20, previousEnd - previousStart);
        int start = Math.min(23 * 60, previousEnd + 5);
        int end = Math.min(23 * 60 + 59, start + duration);
        working.add(new PeriodTime(format(start), format(end)));
        renderRows();
    }

    private void removePeriod() {
        if (working.size() <= 1) return;
        int required = highestUsedPeriod();
        if (working.size() <= required) {
            Toast.makeText(activity, "第 " + required + " 节仍有课程或待办，暂不能减少", Toast.LENGTH_LONG).show();
            return;
        }
        working.remove(working.size() - 1);
        renderRows();
    }

    private void renderRows() {
        count.setText(String.valueOf(working.size()));
        rows.removeAllViews();
        for (int index = 0; index < working.size(); index++) {
            final int position = index;
            PeriodTime period = working.get(index);
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 4));
            TextView number = Ui.text(activity, "第" + (index + 1) + "节", 13, TEXT, true);
            row.addView(number, new LinearLayout.LayoutParams(Ui.dp(activity, 58), Ui.dp(activity, 42)));
            TextView start = timeButton(period.start);
            TextView dash = Ui.text(activity, "—", 14, MUTED, false);
            dash.setGravity(Gravity.CENTER);
            TextView end = timeButton(period.end);
            start.setOnClickListener(view -> pickTime(position, true));
            end.setOnClickListener(view -> pickTime(position, false));
            row.addView(start, new LinearLayout.LayoutParams(0, Ui.dp(activity, 42), 1));
            row.addView(dash, new LinearLayout.LayoutParams(Ui.dp(activity, 34), Ui.dp(activity, 42)));
            row.addView(end, new LinearLayout.LayoutParams(0, Ui.dp(activity, 42), 1));
            rows.addView(row);
        }
    }

    private TextView timeButton(String value) {
        TextView button = Ui.text(activity, value, 15, BRAND, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(Ui.roundedStroke(Color.WHITE, 0xFFD6E2F1, 10, activity));
        Ui.pressable(button);
        return button;
    }

    private void pickTime(int index, boolean start) {
        PeriodTime period = working.get(index);
        int current = minutes(start ? period.start : period.end);
        new TimePickerDialog(activity, (view, hour, minute) -> {
            if (start) period.start = format(hour * 60 + minute);
            else period.end = format(hour * 60 + minute);
            renderRows();
        }, current / 60, current % 60, true).show();
    }

    private void save(AlertDialog dialog) {
        int required = highestUsedPeriod();
        if (working.size() < required) {
            Toast.makeText(activity, "现有安排使用到第 " + required + " 节，不能保存", Toast.LENGTH_LONG).show();
            return;
        }
        int previousEnd = -1;
        for (int i = 0; i < working.size(); i++) {
            PeriodTime period = working.get(i);
            int start = minutes(period.start);
            int end = minutes(period.end);
            if (start >= end) {
                Toast.makeText(activity, "第 " + (i + 1) + " 节结束时间必须晚于开始时间", Toast.LENGTH_LONG).show();
                return;
            }
            if (previousEnd > start) {
                Toast.makeText(activity, "第 " + (i + 1) + " 节与上一节时间重叠", Toast.LENGTH_LONG).show();
                return;
            }
            previousEnd = end;
        }
        schedule.periods.clear();
        for (PeriodTime period : working) schedule.periods.add(period.copy());
        dialog.dismiss();
        onSaved.run();
        Toast.makeText(activity, "时间轴已保存", Toast.LENGTH_SHORT).show();
    }

    private int highestUsedPeriod() {
        int highest = 1;
        for (Course course : schedule.courses) highest = Math.max(highest, course.endPeriod);
        for (TodoItem todo : schedule.todos) highest = Math.max(highest, todo.endPeriod);
        return highest;
    }

    private static int minutes(String value) {
        try {
            String[] parts = value.split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String format(int value) {
        int safe = Math.max(0, Math.min(23 * 60 + 59, value));
        return String.format(Locale.CHINA, "%02d:%02d", safe / 60, safe % 60);
    }
}
