package com.courseflow.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import com.courseflow.app.model.Course;
import com.courseflow.app.model.ScheduleData;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ScheduleCanvasView extends View {
    public interface Listener {
        void onCourseClick(Course course);
        void onWeekSwipe(int direction);
    }

    private static final String[] DAY_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final String[] START_TIMES = {"08:30", "09:15", "10:05", "10:50", "13:30", "14:15", "15:05", "15:50", "18:40", "19:25", "20:10", "20:55"};
    private static final String[] END_TIMES = {"09:10", "09:55", "10:45", "11:30", "14:10", "14:55", "15:45", "16:30", "19:20", "20:05", "20:50", "21:35"};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<HitTarget> targets = new ArrayList<>();
    private final ArrayList<ArrayList<Course>> visibleSlots = new ArrayList<>();
    private int activeTargetCount;
    private ScheduleData data;
    private int week = 1;
    private Listener listener;
    private float downX;
    private float downY;

    public ScheduleCanvasView(Context context) {
        super(context);
        setBackgroundColor(0xFFF7F9FC);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setMinimumHeight(Ui.dp(context, 970));
        for (int i = 0; i < 80; i++) targets.add(new HitTarget());
    }

    public void bind(ScheduleData data, int week) {
        this.data = data;
        this.week = week;
        rebuildVisibleSlots();
        requestLayout();
        postInvalidate();
    }

    private void rebuildVisibleSlots() {
        visibleSlots.clear();
        if (data == null) return;
        Map<String, ArrayList<Course>> slots = new LinkedHashMap<>();
        for (Course course : data.courses) {
            if (!course.isActiveInWeek(week)) continue;
            String key = course.day + ":" + course.startPeriod + ":" + course.endPeriod;
            slots.computeIfAbsent(key, unused -> new ArrayList<>()).add(course);
        }
        visibleSlots.addAll(slots.values());
        while (targets.size() < visibleSlots.size()) targets.add(new HitTarget());
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = Ui.dp(getContext(), 360);
        int width = resolveSize(desiredWidth, widthMeasureSpec);
        int desiredHeight = Ui.dp(getContext(), 62 + 12 * 74 + 16);
        int height = resolveSize(desiredHeight, heightMeasureSpec);
        if (height <= 0) height = desiredHeight;
        setMeasuredDimension(width, height);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        activeTargetCount = 0;
        float left = Ui.dp(getContext(), 47);
        float top = Ui.dp(getContext(), 60);
        float rowHeight = Ui.dp(getContext(), 74);
        float columnWidth = (getWidth() - left - Ui.dp(getContext(), 3)) / 7f;

        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, getWidth(), top, paint);
        drawDayHeader(canvas, left, columnWidth);

        paint.setStrokeWidth(Ui.dp(getContext(), 0.65f));
        paint.setColor(0xFFE8EDF4);
        for (int row = 0; row <= 12; row++) {
            float y = top + row * rowHeight;
            canvas.drawLine(left, y, getWidth(), y, paint);
        }
        for (int column = 0; column <= 7; column++) {
            float x = left + column * columnWidth;
            canvas.drawLine(x, top, x, top + rowHeight * 12, paint);
        }

        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextAlign(Paint.Align.CENTER);
        for (int row = 0; row < 12; row++) {
            float center = top + row * rowHeight + rowHeight / 2f;
            textPaint.setTextSize(Ui.dp(getContext(), 10));
            textPaint.setColor(0xFF9AA4B2);
            canvas.drawText(START_TIMES[row], left / 2f, center - Ui.dp(getContext(), 15), textPaint);
            textPaint.setTextSize(Ui.dp(getContext(), 15));
            textPaint.setColor(0xFF384354);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText(String.valueOf(row + 1), left / 2f, center + Ui.dp(getContext(), 4), textPaint);
            textPaint.setTypeface(Typeface.DEFAULT);
            textPaint.setTextSize(Ui.dp(getContext(), 10));
            textPaint.setColor(0xFF9AA4B2);
            canvas.drawText(END_TIMES[row], left / 2f, center + Ui.dp(getContext(), 23), textPaint);
        }

        if (data == null) return;
        if (visibleSlots.isEmpty()) {
            textPaint.setTypeface(Typeface.DEFAULT);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(Ui.dp(getContext(), 14));
            textPaint.setColor(0xFF7D8796);
            canvas.drawText("本周没有课程，请切换周次", getWidth() / 2f,
                    top + Ui.dp(getContext(), 44), textPaint);
            return;
        }
        for (ArrayList<Course> group : visibleSlots) {
            Course course = group.get(0);
            float margin = Ui.dp(getContext(), 2.5f);
            float x = left + (course.day - 1) * columnWidth + margin;
            float y = top + (course.startPeriod - 1) * rowHeight + margin;
            float right = x + columnWidth - margin * 2;
            float bottom = top + course.endPeriod * rowHeight - margin;
            HitTarget target = targets.get(activeTargetCount);
            target.rect.set(x, y, right, bottom);
            target.course = course;
            drawCourseCard(canvas, target.rect, course, group.size());
            activeTargetCount++;
        }
    }

    private void drawDayHeader(Canvas canvas, float left, float columnWidth) {
        Calendar date = Calendar.getInstance();
        date.setTimeInMillis(data == null ? System.currentTimeMillis() : data.semesterStartMillis);
        date.add(Calendar.DAY_OF_YEAR, (week - 1) * 7);
        Calendar today = Calendar.getInstance();
        SimpleDateFormat dateFormat = new SimpleDateFormat("M/d", Locale.CHINA);
        for (int day = 0; day < 7; day++) {
            float x = left + day * columnWidth;
            boolean isToday = sameDay(date, today);
            if (isToday) {
                paint.setColor(0xFF2F80ED);
                canvas.drawRoundRect(new RectF(x + Ui.dp(getContext(), 3), Ui.dp(getContext(), 5),
                        x + columnWidth - Ui.dp(getContext(), 3), Ui.dp(getContext(), 55)),
                        Ui.dp(getContext(), 9), Ui.dp(getContext(), 9), paint);
            }
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextSize(Ui.dp(getContext(), 12));
            textPaint.setColor(isToday ? Color.WHITE : 0xFF273142);
            canvas.drawText(DAY_NAMES[day], x + columnWidth / 2f, Ui.dp(getContext(), 24), textPaint);
            textPaint.setTypeface(Typeface.DEFAULT);
            textPaint.setTextSize(Ui.dp(getContext(), 11));
            textPaint.setColor(isToday ? 0xFFEAF3FF : 0xFF7D8796);
            canvas.drawText(dateFormat.format(date.getTime()), x + columnWidth / 2f, Ui.dp(getContext(), 44), textPaint);
            date.add(Calendar.DAY_OF_YEAR, 1);
        }
    }

    private void drawCourseCard(Canvas canvas, RectF card, Course course, int candidates) {
        paint.setColor(course.color);
        paint.setShadowLayer(Ui.dp(getContext(), 2), 0, Ui.dp(getContext(), 1), 0x22000000);
        canvas.drawRoundRect(card, Ui.dp(getContext(), 7), Ui.dp(getContext(), 7), paint);
        paint.clearShadowLayer();

        float padding = Ui.dp(getContext(), 4);
        float y = card.top + Ui.dp(getContext(), 15);
        float lineHeight = Ui.dp(getContext(), 14);
        int maxLines = Math.max(1, (int) ((card.height() - padding * 2) / lineHeight));
        ArrayList<String> lines = new ArrayList<>();
        lines.addAll(wrap(course.name, card.width() - padding * 2, true));
        if (!course.teacher.isEmpty()) lines.add(course.teacher);
        if (!course.room.isEmpty()) lines.add(course.room);
        if (candidates > 1) lines.add("+" + (candidates - 1) + " 候选");
        for (int i = 0; i < Math.min(maxLines, lines.size()); i++) {
            boolean title = i < wrap(course.name, card.width() - padding * 2, true).size();
            textPaint.setTypeface(title ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            textPaint.setTextSize(Ui.dp(getContext(), title ? 10.5f : 9.5f));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(Color.WHITE);
            String text = ellipsize(lines.get(i), card.width() - padding * 2);
            canvas.drawText(text, card.centerX(), y, textPaint);
            y += lineHeight;
        }
    }

    private List<String> wrap(String value, float width, boolean bold) {
        ArrayList<String> lines = new ArrayList<>();
        textPaint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        textPaint.setTextSize(Ui.dp(getContext(), 10.5f));
        String rest = value == null ? "" : value.trim();
        while (!rest.isEmpty() && lines.size() < 4) {
            int count = textPaint.breakText(rest, true, width, null);
            if (count <= 0) break;
            lines.add(rest.substring(0, count));
            rest = rest.substring(count).trim();
        }
        if (lines.isEmpty()) lines.add("未命名课程");
        return lines;
    }

    private String ellipsize(String value, float width) {
        if (textPaint.measureText(value) <= width) return value;
        String ellipsis = "…";
        int count = textPaint.breakText(value, true, width - textPaint.measureText(ellipsis), null);
        return value.substring(0, Math.max(0, count)) + ellipsis;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float dx = event.getX() - downX;
            if (Math.abs(dx) > Ui.dp(getContext(), 70)) {
                if (listener != null) listener.onWeekSwipe(dx < 0 ? 1 : -1);
                return true;
            }
            for (int i = 0; i < activeTargetCount; i++) {
                HitTarget target = targets.get(i);
                if (target.rect.contains(event.getX(), event.getY())) {
                    performClick();
                    if (listener != null) listener.onCourseClick(target.course);
                    return true;
                }
            }
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private boolean sameDay(Calendar first, Calendar second) {
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR)
                && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR);
    }

    private static final class HitTarget {
        final RectF rect = new RectF();
        Course course;
    }
}




