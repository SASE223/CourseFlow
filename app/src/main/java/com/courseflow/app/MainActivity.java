package com.courseflow.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.courseflow.app.data.ScheduleStore;
import com.courseflow.app.importer.ExcelScheduleImporter;
import com.courseflow.app.importer.ImageImportEngine;
import com.courseflow.app.model.Course;
import com.courseflow.app.model.InboxItem;
import com.courseflow.app.model.ScheduleData;
import com.courseflow.app.model.TodoItem;
import com.courseflow.app.ui.ScheduleCanvasView;
import com.courseflow.app.ui.Ui;
import com.courseflow.app.ui.TimeAxisEditorDialog;
import com.courseflow.app.update.GitHubUpdateManager;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements ScheduleCanvasView.Listener {
    private static final int REQUEST_EXCEL = 7001;
    private static final int REQUEST_IMAGE_TODO = 7002;
    private static final int REQUEST_IMAGE_TIMETABLE = 7003;
    private static final int REQUEST_MEDIA_PERMISSION = 7004;
    private static final String FEATURE_PREFS = "courseflow_image_features";
    private static final String KEY_DISCOVERY = "discovery_enabled";
    private static final String KEY_DISCOVERY_INTRO = "discovery_intro_v1";
    private static final String KEY_MEDIA_WATERMARK = "media_watermark";
    private static final String KEY_PROCESSED_URIS = "processed_image_uris";
    private static final int BRAND = 0xFF2F80ED;
    private static final int TEXT = 0xFF202938;
    private static final int MUTED = 0xFF7D8796;
    private static final int BACKGROUND = 0xFFF7F9FC;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler launchHandler = new Handler(Looper.getMainLooper());
    private AlertDialog launchDialog;
    private SharedPreferences featurePreferences;
    private ContentObserver imageObserver;
    private boolean scanningNewImages;
    private ScheduleStore store;
    private ScheduleData data;
    private int selectedWeek;
    private int currentPage = 0;
    private boolean batchSelectionMode = false;
    private final HashSet<String> selectedCourseIds = new HashSet<>();
    private LinearLayout root;
    private LinearLayout header;
    private FrameLayout content;
    private LinearLayout bottomNav;
    private GitHubUpdateManager updateManager;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        store = new ScheduleStore(this);
        featurePreferences = getSharedPreferences(FEATURE_PREFS, MODE_PRIVATE);
        data = store.load();
        selectedWeek = store.loadWeek(calculateCurrentWeek());
        buildApp();
        updateManager = new GitHubUpdateManager(this);
        updateManager.start();
        showLaunchDialog();
        handleIncomingShare(getIntent());
    }

    private void showLaunchDialog() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(Ui.dp(this, 28), Ui.dp(this, 24), Ui.dp(this, 28), Ui.dp(this, 24));
        card.setBackground(Ui.rounded(Color.WHITE, 24, this));
        card.setElevation(Ui.dp(this, 10));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.app_icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 92)));

        TextView message = Ui.text(this,
                "本产品免费无广\n支持天秤喵, 支持天秤谢谢喵🥳🥳🥳.\n\nv4.0 更新：修复更新入口，支持强制更新",
                15, TEXT, true);
        message.setGravity(Gravity.CENTER);
        message.setLineSpacing(Ui.dp(this, 5), 1f);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        messageParams.setMargins(0, Ui.dp(this, 16), 0, 0);
        card.addView(message, messageParams);

        launchDialog = new AlertDialog.Builder(this)
                .setView(card)
                .setCancelable(true)
                .create();
        launchDialog.setCanceledOnTouchOutside(true);
        launchDialog.setOnDismissListener(dialog -> {
            launchDialog = null;
            maybeShowDiscoveryIntro();
        });
        launchDialog.setOnShowListener(unused -> {
            Window window = launchDialog == null ? null : launchDialog.getWindow();
            if (window == null) return;
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.32f;
            window.setAttributes(attributes);
            window.setLayout(Ui.dp(this, 326), ViewGroup.LayoutParams.WRAP_CONTENT);
        });
        launchDialog.show();
        launchHandler.postDelayed(() -> {
            AlertDialog current = launchDialog;
            if (current != null && current.isShowing()) current.dismiss();
        }, 2000);
    }

    private void buildApp() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        setContentView(root);
        header = buildHeader();
        root.addView(header);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        bottomNav = buildBottomNav();
        root.addView(bottomNav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 70)));
        showPage(0);
    }

    private LinearLayout buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 18), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
        bar.setBackgroundColor(BACKGROUND);
        bar.setElevation(Ui.dp(this, 1));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, "我的课表", 24, TEXT, true);
        titleRow.addView(title);
        TextView chip = Ui.text(this, data.campus, 12, BRAND, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(Ui.dp(this, 9), 0, Ui.dp(this, 9), 0);
        chip.setBackground(Ui.rounded(0xFFE6F2FF, 13, this));
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 28));
        chipParams.setMargins(Ui.dp(this, 8), 0, 0, 0);
        titleRow.addView(chip, chipParams);
        titles.addView(titleRow);
        TextView subtitle = Ui.text(this, data.className, 12, MUTED, false);
        subtitle.setMaxLines(1);
        titles.addView(subtitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 22)));
        bar.addView(titles, new LinearLayout.LayoutParams(0, Ui.dp(this, 62), 1));

        TextView add = Ui.text(this, "+", 25, BRAND, true);
        add.setGravity(Gravity.CENTER);
        add.setContentDescription("添加课程");
        add.setBackground(Ui.rounded(0xFFE6F2FF, 21, this));
        Ui.pressable(add);
        add.setOnClickListener(view -> showCourseEditor(null));
        bar.addView(add, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));

        TextView importButton = Ui.text(this, "导入", 14, Color.WHITE, true);
        importButton.setGravity(Gravity.CENTER);
        importButton.setContentDescription("导入 Excel 或图片");
        importButton.setBackground(Ui.rounded(BRAND, 21, this));
        importButton.setOnClickListener(view -> showImportMenu());
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(Ui.dp(this, 62), Ui.dp(this, 42));
        importParams.setMargins(Ui.dp(this, 9), 0, 0, 0);
        bar.addView(importButton, importParams);
        return bar;
    }

    private LinearLayout buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setBackgroundColor(Color.WHITE);
        nav.setElevation(Ui.dp(this, 10));
        nav.addView(navItem("▦", "课表", 0), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        nav.addView(navItem("✓", "待办", 1), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        nav.addView(navItem("⚙", "设置", 2), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return nav;
    }

    private View navItem(String icon, String label, int page) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setTag(page);
        TextView iconView = Ui.text(this, icon, 24, page == currentPage ? BRAND : 0xFFB1B8C3, false);
        iconView.setGravity(Gravity.CENTER);
        iconView.setTag("icon");
        TextView labelView = Ui.text(this, label, 12, page == currentPage ? BRAND : 0xFF657080, page == currentPage);
        labelView.setGravity(Gravity.CENTER);
        labelView.setTag("label");
        item.addView(iconView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 35)));
        item.addView(labelView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 24)));
        item.setOnClickListener(view -> showPage(page));
        return item;
    }

    private void showPage(int page) {
        currentPage = page;
        content.removeAllViews();
        if (page == 0) content.addView(buildSchedulePage());
        else if (page == 1) content.addView(buildTodoPage());
        else if (page == 2) content.addView(buildSettingsPage());
        else content.addView(buildCourseListPage());
        updateNavigationColors();
    }

    private View buildSchedulePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        View banner = buildBanner();
        if (banner != null) page.addView(banner);
        int visibleCourses = activeCourseCount(data, selectedWeek);
        int visibleTodos = activeTodoCount(data, selectedWeek);
        if ((!data.courses.isEmpty() || !data.todos.isEmpty()) && visibleCourses + visibleTodos == 0) {
            selectedWeek = findBestWeek(data);
            visibleCourses = activeCourseCount(data, selectedWeek);
            visibleTodos = activeTodoCount(data, selectedWeek);
            store.saveWeek(selectedWeek);
        }
        page.addView(buildWeekPicker(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 58)));
        if (data.courses.isEmpty() && data.todos.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            TextView message = Ui.text(this, "还没有课表\n可从 Excel 或课表图片导入", 16, MUTED, false);
            message.setGravity(Gravity.CENTER);
            TextView importNow = Ui.text(this, "选择导入方式", 14, Color.WHITE, true);
            importNow.setGravity(Gravity.CENTER);
            importNow.setBackground(Ui.rounded(BRAND, 22, this));
            importNow.setOnClickListener(view -> showImportMenu());
            empty.addView(message, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 80)));
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 44));
            buttonParams.gravity = Gravity.CENTER_HORIZONTAL;
            empty.addView(importNow, buttonParams);
            page.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 420)));
            return wrapSchedulePage(page);
        }
        if (visibleCourses == 0) {
            TextView notice = Ui.text(this, "第" + selectedWeek + "周没有课程；已导入 " + data.courses.size() + " 条，请切换上方周次", 12, BRAND, false);
            notice.setGravity(Gravity.CENTER);
            notice.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
            notice.setBackgroundColor(0xFFEAF3FF);
            page.addView(notice);
        }
        TextView weekSummary = Ui.text(this,
                "第" + selectedWeek + "周 · " + visibleCourses + " 门课 · " + visibleTodos + " 项待办",
                12, 0xFF526071, false);
        weekSummary.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        weekSummary.setBackgroundColor(Color.WHITE);
        page.addView(weekSummary, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 32)));
        TextView gestureHint = Ui.text(this,
                "长按空白格添加 · 长按色块中部移动 · 按住上下边缘调整长度",
                11, BRAND, false);
        gestureHint.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 7));
        gestureHint.setBackgroundColor(0xFFEAF3FF);
        page.addView(gestureHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 30)));

        View scheduleBoard = buildNativeScheduleBoard(data, selectedWeek);
        page.addView(scheduleBoard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 62 + data.periods.size() * 74 + 16)));
        return wrapSchedulePage(page);
    }

    private View wrapSchedulePage(LinearLayout page) {
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroller;
    }

    private View buildNativeScheduleBoard(ScheduleData schedule, int week) {
        final String[] dayNames = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        final int periodCount = Math.max(1, schedule.periods.size());

        FrameLayout board = new FrameLayout(this);
        board.setBackgroundColor(0xFFF7F9FC);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int leftWidth = Ui.dp(this, 48);
        int topHeight = Ui.dp(this, 62);
        int rowHeight = Ui.dp(this, 74);
        int boardHeight = topHeight + rowHeight * periodCount + Ui.dp(this, 16);
        int columnWidth = Math.max(Ui.dp(this, 40), (screenWidth - leftWidth - Ui.dp(this, 4)) / 7);

        View headerBackground = new View(this);
        headerBackground.setBackgroundColor(Color.WHITE);
        FrameLayout.LayoutParams headerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, topHeight);
        board.addView(headerBackground, headerParams);

        Calendar date = Calendar.getInstance();
        date.setTimeInMillis(schedule.semesterStartMillis);
        date.add(Calendar.DAY_OF_YEAR, (week - 1) * 7);
        SimpleDateFormat dateFormat = new SimpleDateFormat("M/d", Locale.CHINA);
        for (int day = 0; day < 7; day++) {
            TextView dayHeader = Ui.text(this, dayNames[day] + "\n" + dateFormat.format(date.getTime()),
                    11, 0xFF273142, true);
            dayHeader.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(columnWidth, topHeight);
            params.leftMargin = leftWidth + day * columnWidth;
            board.addView(dayHeader, params);
            date.add(Calendar.DAY_OF_YEAR, 1);
        }

        int lineColor = 0xFFE3E9F1;
        for (int row = 0; row <= periodCount; row++) {
            View line = new View(this);
            line.setBackgroundColor(lineColor);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    columnWidth * 7, Math.max(1, Ui.dp(this, 0.7f)));
            params.leftMargin = leftWidth;
            params.topMargin = topHeight + row * rowHeight;
            board.addView(line, params);
        }
        for (int column = 0; column <= 7; column++) {
            View line = new View(this);
            line.setBackgroundColor(lineColor);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    Math.max(1, Ui.dp(this, 0.7f)), rowHeight * periodCount);
            params.leftMargin = leftWidth + column * columnWidth;
            params.topMargin = topHeight;
            board.addView(line, params);
        }

        for (int period = 0; period < periodCount; period++) {
            TextView time = Ui.text(this,
                    schedule.periods.get(period).start + "\n" + (period + 1) + "\n" + schedule.periods.get(period).end,
                    9, 0xFF687485, false);
            time.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(leftWidth, rowHeight);
            params.topMargin = topHeight + period * rowHeight;
            board.addView(time, params);
        }

        for (int day = 1; day <= 7; day++) {
            for (int period = 1; period <= periodCount; period++) {
                final int slotDay = day;
                final int slotPeriod = period;
                View slot = new View(this);
                slot.setContentDescription(dayNames[day - 1] + "第" + period + "节空白格");
                slot.setOnLongClickListener(view -> {
                    performCrispHaptic(view);
                    showAddAtSlotDialog(slotDay, slotPeriod);
                    return true;
                });
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(columnWidth, rowHeight);
                params.leftMargin = leftWidth + (day - 1) * columnWidth;
                params.topMargin = topHeight + (period - 1) * rowHeight;
                board.addView(slot, params);
            }
        }

        LinkedHashMap<String, ArrayList<Course>> groups = new LinkedHashMap<>();
        ArrayList<Course> active = new ArrayList<>();
        for (Course course : schedule.courses) {
            if (course.isActiveInWeek(week)) active.add(course);
        }
        active.sort(Comparator.comparingInt((Course course) -> course.day)
                .thenComparingInt(course -> course.startPeriod)
                .thenComparingInt(course -> course.endPeriod));
        for (Course course : active) {
            String key = course.day + ":" + course.startPeriod + ":" + course.endPeriod;
            groups.computeIfAbsent(key, unused -> new ArrayList<>()).add(course);
        }

        int margin = Ui.dp(this, 3);
        for (Map.Entry<String, ArrayList<Course>> entry : groups.entrySet()) {
            ArrayList<Course> group = entry.getValue();
            Course course = group.get(0);
            int day = Math.max(1, Math.min(7, course.day));
            int start = Math.max(1, Math.min(periodCount, course.startPeriod));
            int end = Math.max(start, Math.min(periodCount, course.endPeriod));
            StringBuilder label = new StringBuilder(course.name);
            if (!course.teacher.isEmpty()) label.append("\n").append(course.teacher);
            if (!course.room.isEmpty()) label.append("\n").append(course.room);
            if (group.size() > 1) label.append("\n+").append(group.size() - 1).append(" 候选");

            TextView card = Ui.text(this, label.toString(), 9.5f, Color.WHITE, true);
            card.setGravity(Gravity.CENTER);
            card.setPadding(Ui.dp(this, 2), Ui.dp(this, 3), Ui.dp(this, 2), Ui.dp(this, 3));
            card.setMaxLines(Math.max(2, (end - start + 1) * 4));
            card.setBackground(Ui.rounded(course.color, 7, this));
            card.setElevation(Ui.dp(this, 1));
            card.setOnClickListener(view -> showCourseEditor(course));
            card.setOnLongClickListener(view -> {
                performCrispHaptic(view);
                showCourseEditor(course);
                return true;
            });

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    columnWidth - margin * 2, (end - start + 1) * rowHeight - margin * 2);
            params.leftMargin = leftWidth + (day - 1) * columnWidth + margin;
            params.topMargin = topHeight + (start - 1) * rowHeight + margin;
            board.addView(card, params);
            attachBlockGesture(card, params, leftWidth, columnWidth, topHeight, rowHeight, course, null);
        }

        for (TodoItem todo : schedule.todos) {
            if (todo.completed || todo.week != week) continue;
            int day = Math.max(1, Math.min(7, todo.day));
            int start = Math.max(1, Math.min(periodCount, todo.startPeriod));
            int end = Math.max(start, Math.min(periodCount, todo.endPeriod));
            String label = "待办\n" + todo.title + (todo.note.isEmpty() ? "" : "\n" + todo.note);
            TextView card = Ui.text(this, label, 9.5f, Color.WHITE, true);
            card.setGravity(Gravity.CENTER);
            card.setPadding(Ui.dp(this, 2), Ui.dp(this, 3), Ui.dp(this, 2), Ui.dp(this, 3));
            card.setMaxLines(Math.max(2, (end - start + 1) * 4));
            card.setBackground(Ui.rounded(todo.color, 7, this));
            card.setElevation(Ui.dp(this, 2));
            card.setOnClickListener(view -> showTodoEditor(todo, todo.week, todo.day, todo.startPeriod));
            card.setOnLongClickListener(view -> {
                performCrispHaptic(view);
                showTodoEditor(todo, todo.week, todo.day, todo.startPeriod);
                return true;
            });

            int todoWidth = Math.max(Ui.dp(this, 32), columnWidth - margin * 2);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    todoWidth, (end - start + 1) * rowHeight - margin * 2);
            params.leftMargin = leftWidth + (day - 1) * columnWidth + margin;
            params.topMargin = topHeight + (start - 1) * rowHeight + margin;
            board.addView(card, params);
            attachBlockGesture(card, params, leftWidth, columnWidth, topHeight, rowHeight, null, todo);
        }

        board.setMinimumHeight(boardHeight);
        return board;
    }

    private View buildBanner() {
        int conflicts = data.conflictCount(selectedWeek);
        if (data.warnings.isEmpty() && conflicts == 0) return null;
        TextView banner = Ui.text(this,
                conflicts > 0 ? "本周有 " + conflicts + " 组课程时间重叠，点击课程可检查" : data.warnings.get(0),
                12, 0xFF9A5B00, false);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        banner.setBackgroundColor(0xFFFFF3D8);
        return banner;
    }

    private View buildWeekPicker() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setBackgroundColor(Color.WHITE);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 10), Ui.dp(this, 7));
        for (int week = 1; week <= 20; week++) {
            final int value = week;
            TextView chip = Ui.text(this, selectedWeek == week ? "第" + week + "周 · 本周" : "第" + week + "周",
                    13, selectedWeek == week ? Color.WHITE : 0xFF4D5968, selectedWeek == week);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.dp(this, 13), 0, Ui.dp(this, 13), 0);
            chip.setBackground(Ui.rounded(selectedWeek == week ? BRAND : 0xFFF0F3F7, 18, this));
            chip.setOnClickListener(view -> setWeek(value));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 38));
            params.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            row.addView(chip, params);
        }
        scroller.addView(row);
        scroller.post(() -> scroller.smoothScrollTo(Math.max(0, Ui.dp(this, (selectedWeek - 2) * 76)), 0));
        return scroller;
    }

    private View buildTodoPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), 0);

        LinearLayout summary = new LinearLayout(this);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        int pending = 0;
        for (TodoItem todo : data.todos) if (!todo.completed) pending++;
        TextView count = Ui.text(this, "待办  " + pending + " 项未完成", 18, TEXT, true);
        summary.addView(count, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        TextView add = actionButton("+ 添加", BRAND, 0xFFE6F2FF);
        add.setOnClickListener(view -> showTodoEditor(null, selectedWeek, 1, 1));
        summary.addView(add, new LinearLayout.LayoutParams(Ui.dp(this, 78), Ui.dp(this, 36)));
        page.addView(summary);

        TextView hint = Ui.text(this, "点击切换完成状态，长按可编辑或删除", 12, MUTED, false);
        hint.setPadding(Ui.dp(this, 3), 0, 0, Ui.dp(this, 8));
        page.addView(hint);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        if (!data.inboxItems.isEmpty()) {
            TextView inboxTitle = Ui.text(this, "待整理  " + data.inboxItems.size() + " 项", 15, 0xFFC26300, true);
            inboxTitle.setPadding(Ui.dp(this, 3), Ui.dp(this, 8), 0, Ui.dp(this, 5));
            list.addView(inboxTitle);
            ArrayList<InboxItem> inbox = new ArrayList<>(data.inboxItems);
            inbox.sort((first, second) -> Long.compare(second.createdAt, first.createdAt));
            for (InboxItem item : inbox) list.addView(inboxCard(item));
            TextView formalTitle = Ui.text(this, "正式待办", 15, MUTED, true);
            formalTitle.setPadding(Ui.dp(this, 3), Ui.dp(this, 14), 0, Ui.dp(this, 5));
            list.addView(formalTitle);
        }
        ArrayList<TodoItem> sorted = new ArrayList<>(data.todos);
        sorted.sort(Comparator.comparing((TodoItem todo) -> todo.completed)
                .thenComparingInt(todo -> todo.week)
                .thenComparingInt(todo -> todo.day)
                .thenComparingInt(todo -> todo.startPeriod));
        if (sorted.isEmpty() && data.inboxItems.isEmpty()) list.addView(emptyView("还没有待办\n点击右上角添加，或导入截图/照片"));
        for (TodoItem todo : sorted) list.addView(todoCard(todo));
        ScrollView scroller = new ScrollView(this);
        scroller.addView(list);
        page.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return page;
    }

    private View inboxCard(InboxItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11));
        card.setBackground(Ui.rounded(0xFFFFF8E8, 14, this));
        card.setElevation(Ui.dp(this, 1.2f));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = Ui.text(this, "待整理", 11, 0xFFC26300, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.rounded(0xFFFFE7BE, 11, this));
        top.addView(badge, new LinearLayout.LayoutParams(Ui.dp(this, 55), Ui.dp(this, 24)));
        TextView source = Ui.text(this, item.sourceLabel, 11, MUTED, false);
        source.setPadding(Ui.dp(this, 8), 0, 0, 0);
        top.addView(source, new LinearLayout.LayoutParams(0, Ui.dp(this, 24), 1));
        TextView organize = actionButton("整理", 0xFFC26300, 0xFFFFE7BE);
        top.addView(organize, new LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 30)));
        card.addView(top);
        TextView title = Ui.text(this, item.title, 15, TEXT, true);
        title.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 3));
        card.addView(title);
        String excerpt = item.ocrText.replace('\n', ' ').trim();
        if (excerpt.length() > 72) excerpt = excerpt.substring(0, 72) + "…";
        card.addView(Ui.text(this, excerpt.isEmpty() ? "未识别到可用文字" : excerpt, 12, 0xFF526071, false));
        View.OnClickListener open = view -> showInboxEditor(item);
        organize.setOnClickListener(open);
        card.setOnClickListener(open);
        card.setOnLongClickListener(view -> {
            performCrispHaptic(view);
            confirmDeleteInbox(item);
            return true;
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        card.setLayoutParams(params);
        return card;
    }

    private View todoCard(TodoItem todo) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        card.setBackground(Ui.rounded(Color.WHITE, 14, this));
        card.setElevation(Ui.dp(this, 1.5f));
        card.setAlpha(todo.completed ? 0.52f : 1f);

        TextView check = Ui.text(this, todo.completed ? "✓" : "○", 23,
                todo.completed ? 0xFF54A66A : todo.color, true);
        check.setGravity(Gravity.CENTER);
        card.addView(check, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 60)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(Ui.dp(this, 8), 0, 0, 0);
        TextView title = Ui.text(this, todo.title, 15, TEXT, true);
        TextView detail = Ui.text(this, "第" + todo.week + "周 · " + dayName(todo.day)
                + " · " + todo.startPeriod + "-" + todo.endPeriod + "节", 12, MUTED, false);
        TextView note = Ui.text(this, todo.note.isEmpty() ? "无备注" : todo.note, 12, 0xFF526071, false);
        text.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 25)));
        text.addView(detail, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 22)));
        text.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 22)));
        card.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        card.setOnClickListener(view -> {
            todo.completed = !todo.completed;
            performCrispHaptic(view);
            refreshAll();
        });
        card.setOnLongClickListener(view -> {
            performCrispHaptic(view);
            showTodoEditor(todo, todo.week, todo.day, todo.startPeriod);
            return true;
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        card.setLayoutParams(params);
        return card;
    }

    private View buildCourseListPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), 0);

        LinearLayout summary = new LinearLayout(this);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        TextView count = Ui.text(this, batchSelectionMode
                ? "已选择 " + selectedCourseIds.size() + " 项"
                : "全部课程  " + data.courses.size(), 18, TEXT, true);
        summary.addView(count, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        if (!batchSelectionMode) {
            TextView add = actionButton("+ 添加", BRAND, 0xFFE6F2FF);
            add.setOnClickListener(view -> showCourseEditor(null));
            summary.addView(add, new LinearLayout.LayoutParams(Ui.dp(this, 78), Ui.dp(this, 36)));
        }
        page.addView(summary);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        if (batchSelectionMode) {
            boolean allSelected = !data.courses.isEmpty() && selectedCourseIds.size() == data.courses.size();
            TextView selectAll = actionButton(allSelected ? "取消全选" : "全选", BRAND, 0xFFEAF3FF);
            selectAll.setOnClickListener(view -> toggleSelectAll());
            actions.addView(selectAll, actionParams(88));
            TextView delete = actionButton("删除 (" + selectedCourseIds.size() + ")", 0xFFD64545, 0xFFFFE8E8);
            delete.setAlpha(selectedCourseIds.isEmpty() ? 0.45f : 1f);
            delete.setOnClickListener(view -> confirmBatchDelete());
            actions.addView(delete, actionParams(92));
            TextView done = actionButton("完成", 0xFF526071, 0xFFF0F3F7);
            done.setOnClickListener(view -> exitBatchSelection());
            actions.addView(done, actionParams(72));
        } else {
            TextView batch = actionButton("批量选择", 0xFF526071, 0xFFF0F3F7);
            batch.setOnClickListener(view -> {
                batchSelectionMode = true;
                selectedCourseIds.clear();
                showPage(3);
            });
            actions.addView(batch, actionParams(96));
        }
        page.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ArrayList<Course> sorted = new ArrayList<>(data.courses);
        sorted.sort(Comparator.comparingInt((Course c) -> c.day).thenComparingInt(c -> c.startPeriod).thenComparing(c -> c.name));
        if (sorted.isEmpty()) list.addView(emptyView("还没有课程\n请先导入 Excel，或手动添加课程"));
        for (Course course : sorted) list.addView(courseCard(course));
        ScrollView scroller = new ScrollView(this);
        scroller.addView(list);
        page.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return page;
    }

    private LinearLayout.LayoutParams actionParams(int widthDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(this, widthDp), Ui.dp(this, 34));
        params.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        return params;
    }

    private TextView actionButton(String label, int textColor, int backgroundColor) {
        TextView button = Ui.text(this, label, 12, textColor, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(Ui.rounded(backgroundColor, 17, this));
        Ui.pressable(button);
        return button;
    }

    private View courseCard(Course course) {
        boolean selected = selectedCourseIds.contains(course.id);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        card.setBackground(Ui.rounded(selected ? 0xFFEAF3FF : Color.WHITE, 14, this));
        card.setElevation(Ui.dp(this, 1.5f));
        if (batchSelectionMode) {
            TextView checkbox = Ui.text(this, selected ? "✓" : "○", 22, selected ? BRAND : 0xFF9AA4B2, true);
            checkbox.setGravity(Gravity.CENTER);
            card.addView(checkbox, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 58)));
        }
        View stripe = new View(this);
        stripe.setBackground(Ui.rounded(course.color, 3, this));
        card.addView(stripe, new LinearLayout.LayoutParams(Ui.dp(this, 5), Ui.dp(this, 58)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(Ui.dp(this, 11), 0, 0, 0);
        TextView name = Ui.text(this, course.name, 15, TEXT, true);
        TextView detail = Ui.text(this, dayName(course.day) + "  " + course.startPeriod + "-" + course.endPeriod + "节  ·  " + course.weeksText() + "周", 12, MUTED, false);
        TextView place = Ui.text(this, joinNonEmpty(course.teacher, course.room), 12, 0xFF526071, false);
        text.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 25)));
        text.addView(detail, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 22)));
        text.addView(place, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 22)));
        card.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        int conflicts = Course.countConflicts(data.courses, course, selectedWeek);
        if (!batchSelectionMode && conflicts > 0) {
            TextView badge = Ui.text(this, "冲突", 11, 0xFFC26300, true);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(Ui.rounded(0xFFFFE7BE, 12, this));
            card.addView(badge, new LinearLayout.LayoutParams(Ui.dp(this, 45), Ui.dp(this, 25)));
        }
        card.setOnClickListener(view -> {
            if (batchSelectionMode) toggleCourseSelection(course);
            else showCourseEditor(course);
        });
        card.setOnLongClickListener(view -> {
            if (batchSelectionMode) {
                toggleCourseSelection(course);
            } else {
                performCrispHaptic(view);
                showCourseEditor(course);
            }
            return true;
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        card.setLayoutParams(params);
        return card;
    }

    private void toggleCourseSelection(Course course) {
        if (!selectedCourseIds.add(course.id)) selectedCourseIds.remove(course.id);
        showPage(3);
    }

    private void toggleSelectAll() {
        if (!data.courses.isEmpty() && selectedCourseIds.size() == data.courses.size()) {
            selectedCourseIds.clear();
        } else {
            selectedCourseIds.clear();
            for (Course course : data.courses) selectedCourseIds.add(course.id);
        }
        showPage(3);
    }

    private void exitBatchSelection() {
        batchSelectionMode = false;
        selectedCourseIds.clear();
        showPage(3);
    }

    private void confirmBatchDelete() {
        if (selectedCourseIds.isEmpty()) {
            Toast.makeText(this, "请先选择要删除的课程", Toast.LENGTH_SHORT).show();
            return;
        }
        int count = selectedCourseIds.size();
        new AlertDialog.Builder(this)
                .setTitle("删除 " + count + " 条课程？")
                .setMessage("删除后无法撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    data.courses.removeIf(course -> selectedCourseIds.contains(course.id));
                    batchSelectionMode = false;
                    selectedCourseIds.clear();
                    refreshAll();
                    Toast.makeText(this, "已删除 " + count + " 条课程", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private View buildSettingsPage() {
        ScrollView scroller = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 28));
        page.addView(sectionTitle("版本与更新"));
        page.addView(settingCard("检查软件更新", updateManager == null ? "当前版本 4.0.0" : updateManager.statusText(), "›",
                view -> { if (updateManager != null) updateManager.showOptions(); }));
        page.addView(sectionTitle("课表数据"));
        page.addView(settingCard("导入 Excel 或图片", "Excel 自动识别班级和周次；课表照片需核对", "›", view -> showImportMenu()));
        page.addView(settingCard("当前班级", data.className, "", null));
        page.addView(settingCard("学期第一周", formatDate(data.semesterStartMillis) + "（周一）", "›", view -> pickSemesterStart()));
        page.addView(settingCard("周次整体调整", "例如将 2-13 周整体调整为 3-14 周", "›", view -> showWeekShiftDialog()));
        page.addView(settingCard("自定义时间轴", data.periods.size() + " 节 · 可调整每节开始与结束时间", "›",
                view -> new TimeAxisEditorDialog(this, data, this::refreshAll).show()));
        if (!data.sourceFile.isEmpty()) page.addView(settingCard("数据来源", data.sourceFile, "", null));
        page.addView(sectionTitle("图片收件箱"));
        page.addView(settingCard("发现新图片", discoveryStatusText(), "›", view -> showDiscoverySettings()));
        page.addView(settingCard("主动导入图片", "课表照片可生成课程；普通截图进入待整理", "›", view -> showImageImportMenu()));
        page.addView(settingCard("隐私与本地处理", "暂时没钱开服务器：图片和文字不会上传，识别全部在本机", "", null));
        page.addView(sectionTitle("使用与维护"));
        page.addView(settingCard("课程管理", "查看、批量选择和删除全部课程", "›", view -> showPage(3)));
        page.addView(settingCard("课程冲突检查", "本周检测到 " + data.conflictCount(selectedWeek) + " 组重叠", "", null));
        page.addView(settingCard("清空全部数据", "删除所有课程、待办与导入记录", "›", view -> confirmReset()));
        page.addView(sectionTitle("关于"));
        page.addView(settingCard("课表流", "版本 4.0.0 · 即时与强制更新", "", null));
        scroller.addView(page);
        return scroller;
    }

    private TextView sectionTitle(String value) {
        TextView title = Ui.text(this, value, 13, MUTED, true);
        title.setPadding(Ui.dp(this, 3), Ui.dp(this, 12), 0, Ui.dp(this, 6));
        return title;
    }

    private View settingCard(String title, String subtitle, String end, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        card.setBackground(Ui.rounded(Color.WHITE, 13, this));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(Ui.text(this, title, 15, TEXT, true), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 27)));
        TextView subtitleView = Ui.text(this, subtitle, 12, MUTED, false);
        subtitleView.setMaxLines(2);
        text.addView(subtitleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 35)));
        card.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView endView = Ui.text(this, end, 25, 0xFFA7AFBA, false);
        endView.setGravity(Gravity.CENTER);
        card.addView(endView, new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 45)));
        if (listener != null) {
            card.setOnClickListener(listener);
            Ui.pressable(card);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        card.setLayoutParams(params);
        return card;
    }

    private View emptyView(String message) {
        TextView empty = Ui.text(this, message, 15, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, Ui.dp(this, 80), 0, Ui.dp(this, 80));
        return empty;
    }

    private void updateNavigationColors() {
        for (int i = 0; i < bottomNav.getChildCount(); i++) {
            LinearLayout item = (LinearLayout) bottomNav.getChildAt(i);
            boolean selected = i == currentPage;
            TextView icon = (TextView) item.getChildAt(0);
            TextView label = (TextView) item.getChildAt(1);
            icon.setTextColor(selected ? BRAND : 0xFFB1B8C3);
            label.setTextColor(selected ? BRAND : 0xFF657080);
            label.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void refreshAll() {
        store.save(data);
        root.removeView(header);
        header = buildHeader();
        root.addView(header, 0);
        showPage(currentPage);
    }

    private void setWeek(int week) {
        selectedWeek = Math.max(1, Math.min(20, week));
        store.saveWeek(selectedWeek);
        showPage(0);
    }

    @Override public void onCourseClick(Course course) {
        showCourseEditor(course);
    }

    @Override public void onWeekSwipe(int direction) {
        setWeek(selectedWeek + direction);
    }

    private void showCourseEditor(Course existing) {
        showCourseEditor(existing, 1, 1, false);
    }

    private void showCourseEditor(Course existing, int defaultDay, int defaultPeriod) {
        showCourseEditor(existing, defaultDay, defaultPeriod, true);
    }

    private void showCourseEditor(Course existing, int defaultDay, int defaultPeriod, boolean fromScheduleSlot) {
        boolean editing = existing != null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), 0);
        EditText name = input(form, "课程名称", editing ? existing.name : "");
        EditText teacher = input(form, "任课教师", editing ? existing.teacher : "");
        EditText room = input(form, "上课地点", editing ? existing.room : "");
        Spinner day = spinner(form, "星期", new String[]{"周一", "周二", "周三", "周四", "周五", "周六", "周日"},
                editing ? existing.day - 1 : Math.max(0, defaultDay - 1));
        String[] periods = periodLabels();
        Spinner start = spinner(form, "开始节次", periods,
                editing ? existing.startPeriod - 1 : Math.max(0, defaultPeriod - 1));
        Spinner end = spinner(form, "结束节次", periods,
                editing ? existing.endPeriod - 1 : (fromScheduleSlot ? Math.max(0, defaultPeriod - 1) : 1));
        EditText weeks = input(form, "周次（如 1-8,10,12-16）",
                editing ? existing.weeksText() : (fromScheduleSlot ? String.valueOf(selectedWeek) : "1-16"));
        ScrollView scroller = new ScrollView(this);
        scroller.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(editing ? "编辑课程" : "添加课程")
                .setView(scroller)
                .setNegativeButton("取消", null)
                .setNeutralButton(editing ? "删除" : null, editing ? (d, which) -> {
                    data.courses.remove(existing);
                    refreshAll();
                    Toast.makeText(this, "课程已删除", Toast.LENGTH_SHORT).show();
                } : null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String courseName = name.getText().toString().trim();
            if (courseName.isEmpty()) {
                name.setError("请输入课程名称");
                return;
            }
            int startValue = start.getSelectedItemPosition() + 1;
            int endValue = end.getSelectedItemPosition() + 1;
            if (endValue < startValue) {
                Toast.makeText(this, "结束节次不能早于开始节次", Toast.LENGTH_SHORT).show();
                return;
            }
            Course course = editing ? existing : new Course();
            course.name = courseName;
            course.teacher = teacher.getText().toString().trim();
            course.room = room.getText().toString().trim();
            course.day = day.getSelectedItemPosition() + 1;
            course.startPeriod = startValue;
            course.endPeriod = endValue;
            course.weeks.clear();
            course.weeks.addAll(Course.parseWeeks(weeks.getText().toString()));
            course.color = Course.colorFor(course.name);
            if (!editing) data.courses.add(course);
            dialog.dismiss();
            refreshAll();
        }));
        dialog.show();
    }

    private void showInboxEditor(InboxItem item) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), 0);
        EditText title = input(form, "待办名称", item.title);
        String[] weeks = new String[30];
        for (int i = 0; i < weeks.length; i++) weeks[i] = "第" + (i + 1) + "周";
        Spinner week = spinner(form, "周次", weeks, item.suggestedWeek - 1);
        Spinner day = spinner(form, "星期",
                new String[]{"周一", "周二", "周三", "周四", "周五", "周六", "周日"},
                item.suggestedDay - 1);
        String[] periods = periodLabels();
        Spinner start = spinner(form, "开始节次", periods, item.suggestedStartPeriod - 1);
        Spinner end = spinner(form, "结束节次", periods, item.suggestedEndPeriod - 1);
        TextView rawLabel = Ui.text(this, "图片识别原文", 12, MUTED, true);
        form.addView(rawLabel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 28)));
        EditText raw = new EditText(this);
        raw.setText(item.ocrText);
        raw.setTextSize(13);
        raw.setGravity(Gravity.TOP);
        raw.setMinLines(4);
        raw.setMaxLines(8);
        raw.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        raw.setBackground(Ui.roundedStroke(0xFFF8FAFD, 0xFFD9E0E9, 9, this));
        form.addView(raw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 150)));

        ScrollView scroller = new ScrollView(this);
        scroller.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("整理为正式待办")
                .setMessage("识别结果可能有误，请确认名称、周次和时间。")
                .setView(scroller)
                .setNegativeButton("取消", null)
                .setNeutralButton("删除", (d, which) -> {
                    data.inboxItems.remove(item);
                    refreshAll();
                })
                .setPositiveButton("保存为待办", null)
                .create();
        dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = title.getText().toString().trim();
            if (value.isEmpty()) {
                title.setError("请输入待办名称");
                return;
            }
            int startValue = start.getSelectedItemPosition() + 1;
            int endValue = end.getSelectedItemPosition() + 1;
            if (endValue < startValue) {
                Toast.makeText(this, "结束节次不能早于开始节次", Toast.LENGTH_SHORT).show();
                return;
            }
            TodoItem todo = new TodoItem();
            todo.title = value;
            todo.note = raw.getText().toString().trim();
            todo.week = week.getSelectedItemPosition() + 1;
            todo.day = day.getSelectedItemPosition() + 1;
            todo.startPeriod = startValue;
            todo.endPeriod = endValue;
            todo.color = TodoItem.colorFor(todo.title);
            if (hasBlockConflict(null, todo, todo.day, todo.startPeriod, todo.endPeriod)) {
                Toast.makeText(this, "这个时间与现有课程或待办冲突，请调整后再保存", Toast.LENGTH_LONG).show();
                return;
            }
            data.todos.add(todo);
            data.inboxItems.remove(item);
            dialog.dismiss();
            refreshAll();
            Toast.makeText(this, "已整理为正式待办", Toast.LENGTH_SHORT).show();
        }));
        dialog.show();
    }

    private void confirmDeleteInbox(InboxItem item) {
        new AlertDialog.Builder(this)
                .setTitle("删除这条待整理？")
                .setMessage("识别文字将一并删除，无法撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    data.inboxItems.remove(item);
                    refreshAll();
                })
                .show();
    }

    private void showTodoEditor(TodoItem existing, int defaultWeek, int defaultDay, int defaultPeriod) {
        boolean editing = existing != null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), 0);
        EditText title = input(form, "待办名称", editing ? existing.title : "");
        EditText note = input(form, "备注", editing ? existing.note : "");

        String[] weeks = new String[30];
        for (int i = 0; i < weeks.length; i++) weeks[i] = "第" + (i + 1) + "周";
        Spinner week = spinner(form, "周次", weeks,
                editing ? existing.week - 1 : Math.max(0, Math.min(29, defaultWeek - 1)));
        Spinner day = spinner(form, "星期",
                new String[]{"周一", "周二", "周三", "周四", "周五", "周六", "周日"},
                editing ? existing.day - 1 : Math.max(0, defaultDay - 1));
        String[] periods = periodLabels();
        Spinner start = spinner(form, "开始节次", periods,
                editing ? existing.startPeriod - 1 : Math.max(0, defaultPeriod - 1));
        Spinner end = spinner(form, "结束节次", periods,
                editing ? existing.endPeriod - 1 : Math.max(0, defaultPeriod - 1));
        CheckBox completed = new CheckBox(this);
        completed.setText("标记为已完成");
        completed.setTextColor(TEXT);
        completed.setChecked(editing && existing.completed);
        form.addView(completed, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));

        ScrollView scroller = new ScrollView(this);
        scroller.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(editing ? "编辑待办" : "添加待办")
                .setView(scroller)
                .setNegativeButton("取消", null)
                .setNeutralButton(editing ? "删除" : null, editing ? (d, which) -> {
                    data.todos.remove(existing);
                    refreshAll();
                    Toast.makeText(this, "待办已删除", Toast.LENGTH_SHORT).show();
                } : null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = title.getText().toString().trim();
            if (value.isEmpty()) {
                title.setError("请输入待办名称");
                return;
            }
            int startValue = start.getSelectedItemPosition() + 1;
            int endValue = end.getSelectedItemPosition() + 1;
            if (endValue < startValue) {
                Toast.makeText(this, "结束节次不能早于开始节次", Toast.LENGTH_SHORT).show();
                return;
            }
            TodoItem todo = editing ? existing : new TodoItem();
            todo.title = value;
            todo.note = note.getText().toString().trim();
            todo.week = week.getSelectedItemPosition() + 1;
            todo.day = day.getSelectedItemPosition() + 1;
            todo.startPeriod = startValue;
            todo.endPeriod = endValue;
            todo.completed = completed.isChecked();
            todo.color = TodoItem.colorFor(todo.title);
            if (!editing) data.todos.add(todo);
            dialog.dismiss();
            refreshAll();
            Toast.makeText(this, editing ? "待办已保存" : "待办已添加", Toast.LENGTH_SHORT).show();
        }));
        dialog.show();
    }

    private void showAddAtSlotDialog(int day, int period) {
        new AlertDialog.Builder(this)
                .setTitle(dayName(day) + " · 第" + period + "节")
                .setItems(new String[]{"添加课程", "添加待办"}, (dialog, which) -> {
                    if (which == 0) showCourseEditor(null, day, period);
                    else showTodoEditor(null, selectedWeek, day, period);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void performCrispHaptic(View view) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attachBlockGesture(TextView card, FrameLayout.LayoutParams params,
                                    int leftWidth, int columnWidth, int topHeight, int rowHeight,
                                    Course course, TodoItem todo) {
        final float[] downRawX = new float[1];
        final float[] downRawY = new float[1];
        final int[] originalDay = new int[1];
        final int[] originalStart = new int[1];
        final int[] originalEnd = new int[1];
        final int[] originalLeft = new int[1];
        final int[] originalTop = new int[1];
        final int[] proposedDay = new int[1];
        final int[] proposedStart = new int[1];
        final int[] proposedEnd = new int[1];
        final int[] mode = new int[1];
        final boolean[] pressed = new boolean[1];
        final boolean[] active = new boolean[1];
        final boolean[] moved = new boolean[1];
        final boolean[] valid = new boolean[1];
        final Runnable[] activation = new Runnable[1];
        final int edgeSize = Ui.dp(this, 15);
        final int margin = Ui.dp(this, 3);
        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        final int originalColor = course != null ? course.color : todo.color;
        final float originalElevation = card.getElevation();
        final int periodCount = Math.max(1, data.periods.size());

        card.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                float localY = event.getY();
                mode[0] = localY <= edgeSize ? -1
                        : (localY >= view.getHeight() - edgeSize ? 1 : 0);
                pressed[0] = true;
                active[0] = false;
                moved[0] = false;
                valid[0] = true;
                downRawX[0] = event.getRawX();
                downRawY[0] = event.getRawY();
                originalDay[0] = course != null ? course.day : todo.day;
                originalStart[0] = course != null ? course.startPeriod : todo.startPeriod;
                originalEnd[0] = course != null ? course.endPeriod : todo.endPeriod;
                originalLeft[0] = params.leftMargin;
                originalTop[0] = params.topMargin;
                proposedDay[0] = originalDay[0];
                proposedStart[0] = originalStart[0];
                proposedEnd[0] = originalEnd[0];
                activation[0] = () -> {
                    if (!pressed[0]) return;
                    active[0] = true;
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    view.bringToFront();
                    view.setElevation(Ui.dp(this, 12));
                    view.animate().scaleX(1.06f).scaleY(1.06f).alpha(0.88f)
                            .setDuration(80).start();
                    performCrispHaptic(view);
                    String hint = mode[0] == 0 ? "拖到空白格后松手"
                            : (mode[0] < 0 ? "拖动调整开始节次" : "拖动调整结束节次");
                    Toast.makeText(this, hint, Toast.LENGTH_SHORT).show();
                };
                launchHandler.postDelayed(activation[0], 320);
                return true;
            }

            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                float dx = event.getRawX() - downRawX[0];
                float dy = event.getRawY() - downRawY[0];
                if (!active[0] && Math.hypot(dx, dy) > touchSlop * 2.5f) {
                    launchHandler.removeCallbacks(activation[0]);
                }
                if (!active[0]) return true;
                if (Math.hypot(dx, dy) > touchSlop) moved[0] = true;

                int day = originalDay[0];
                int start = originalStart[0];
                int end = originalEnd[0];
                if (mode[0] == 0) {
                    int duration = originalEnd[0] - originalStart[0];
                    int floatingLeft = originalLeft[0] + Math.round(dx);
                    int floatingTop = originalTop[0] + Math.round(dy);
                    day = Math.max(1, Math.min(7,
                            Math.round((floatingLeft - leftWidth - margin) / (float) columnWidth) + 1));
                    start = Math.max(1, Math.min(periodCount - duration,
                            Math.round((floatingTop - topHeight - margin) / (float) rowHeight) + 1));
                    end = start + duration;
                } else {
                    int delta = Math.round(dy / rowHeight);
                    if (mode[0] < 0) {
                        start = Math.max(1, Math.min(originalEnd[0], originalStart[0] + delta));
                    } else {
                        end = Math.max(originalStart[0], Math.min(periodCount, originalEnd[0] + delta));
                    }
                }

                proposedDay[0] = day;
                proposedStart[0] = start;
                proposedEnd[0] = end;
                params.leftMargin = leftWidth + (day - 1) * columnWidth + margin;
                params.topMargin = topHeight + (start - 1) * rowHeight + margin;
                params.height = (end - start + 1) * rowHeight - margin * 2;
                card.setLayoutParams(params);
                valid[0] = !hasBlockConflict(course, todo, day, start, end);
                card.setBackground(Ui.rounded(valid[0] ? 0xFF27AE60 : 0xFFE24A4A, 7, this));
                return true;
            }

            if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                pressed[0] = false;
                if (activation[0] != null) launchHandler.removeCallbacks(activation[0]);
                view.getParent().requestDisallowInterceptTouchEvent(false);
                boolean wasActive = active[0];
                active[0] = false;
                view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(100).start();
                view.setElevation(originalElevation);
                card.setBackground(Ui.rounded(originalColor, 7, this));

                boolean changed = proposedDay[0] != originalDay[0]
                        || proposedStart[0] != originalStart[0]
                        || proposedEnd[0] != originalEnd[0];
                boolean commit = event.getAction() == MotionEvent.ACTION_UP
                        && wasActive && moved[0] && changed && valid[0];
                if (commit) {
                    if (course != null) {
                        course.day = proposedDay[0];
                        course.startPeriod = proposedStart[0];
                        course.endPeriod = proposedEnd[0];
                    } else {
                        todo.day = proposedDay[0];
                        todo.startPeriod = proposedStart[0];
                        todo.endPeriod = proposedEnd[0];
                    }
                    store.save(data);
                    performCrispHaptic(view);
                    Toast.makeText(this, "已吸附到 " + dayName(proposedDay[0])
                            + " 第" + proposedStart[0] + "-" + proposedEnd[0] + "节",
                            Toast.LENGTH_SHORT).show();
                    showPage(0);
                } else {
                    params.leftMargin = originalLeft[0];
                    params.topMargin = originalTop[0];
                    params.height = (originalEnd[0] - originalStart[0] + 1) * rowHeight - margin * 2;
                    card.setLayoutParams(params);
                    if (wasActive && moved[0] && !valid[0]) {
                        performCrispHaptic(view);
                        Toast.makeText(this, "目标范围与其他课程或待办冲突，已返回原位",
                                Toast.LENGTH_LONG).show();
                    } else if (event.getAction() == MotionEvent.ACTION_UP && (!wasActive || !moved[0])) {
                        view.performClick();
                    }
                }
                return true;
            }
            return true;
        });
    }

    private boolean hasBlockConflict(Course movingCourse, TodoItem movingTodo,
                                     int day, int start, int end) {
        if (movingCourse != null) {
            for (Course other : data.courses) {
                if (other == movingCourse || other.day != day) continue;
                if (rangesOverlap(start, end, other.startPeriod, other.endPeriod)
                        && courseWeeksOverlap(movingCourse, other)) return true;
            }
            for (TodoItem other : data.todos) {
                if (other.completed || other.day != day) continue;
                if (movingCourse.isActiveInWeek(other.week)
                        && rangesOverlap(start, end, other.startPeriod, other.endPeriod)) return true;
            }
            return false;
        }

        for (Course other : data.courses) {
            if (other.day != day || !other.isActiveInWeek(movingTodo.week)) continue;
            if (rangesOverlap(start, end, other.startPeriod, other.endPeriod)) return true;
        }
        for (TodoItem other : data.todos) {
            if (other == movingTodo || other.completed || other.week != movingTodo.week || other.day != day) continue;
            if (rangesOverlap(start, end, other.startPeriod, other.endPeriod)) return true;
        }
        return false;
    }

    private boolean rangesOverlap(int firstStart, int firstEnd, int secondStart, int secondEnd) {
        return firstStart <= secondEnd && secondStart <= firstEnd;
    }

    private boolean courseWeeksOverlap(Course first, Course second) {
        if (first.weeks.isEmpty() || second.weeks.isEmpty()) return true;
        for (Integer week : first.weeks) if (second.weeks.contains(week)) return true;
        return false;
    }

    private void showWeekShiftDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), 0);
        EditText oldStart = input(form, "原开始周", "2");
        EditText oldEnd = input(form, "原结束周", "13");
        EditText newStart = input(form, "调整后的开始周", "3");
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("周次整体调整")
                .setMessage("范围内的课程和待办将保持原长度整体平移。")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("应用", null)
                .create();
        dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                int from = Integer.parseInt(oldStart.getText().toString().trim());
                int to = Integer.parseInt(oldEnd.getText().toString().trim());
                int target = Integer.parseInt(newStart.getText().toString().trim());
                int delta = target - from;
                int targetEnd = to + delta;
                if (from < 1 || to < from || to > 30 || target < 1 || targetEnd > 30) {
                    throw new IllegalArgumentException();
                }
                int changedCourses = 0;
                for (Course course : data.courses) {
                    if (course.weeks.isEmpty()) continue;
                    ArrayList<Integer> shifted = new ArrayList<>();
                    boolean changed = false;
                    for (Integer courseWeek : course.weeks) {
                        int value = courseWeek;
                        if (value >= from && value <= to) {
                            value += delta;
                            changed = true;
                        }
                        if (value >= 1 && value <= 30 && !shifted.contains(value)) shifted.add(value);
                    }
                    if (changed) {
                        course.weeks.clear();
                        course.weeks.addAll(shifted);
                        course.weeks.sort(Integer::compareTo);
                        changedCourses++;
                    }
                }
                int changedTodos = 0;
                for (TodoItem todo : data.todos) {
                    if (todo.week >= from && todo.week <= to) {
                        todo.week += delta;
                        changedTodos++;
                    }
                }
                if (selectedWeek >= from && selectedWeek <= to) selectedWeek += delta;
                selectedWeek = Math.max(1, Math.min(20, selectedWeek));
                store.saveWeek(selectedWeek);
                dialog.dismiss();
                refreshAll();
                Toast.makeText(this, "已调整为 " + target + "-" + targetEnd + " 周："
                        + changedCourses + " 门课程，" + changedTodos + " 项待办",
                        Toast.LENGTH_LONG).show();
            } catch (Exception error) {
                newStart.setError("请输入有效范围，且调整后不得超过第30周");
            }
        }));
        dialog.show();
    }

    private String[] periodLabels() {
        int count = Math.max(1, data.periods.size());
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) {
            labels[i] = "第" + (i + 1) + "节  " + data.periods.get(i).start + "-" + data.periods.get(i).end;
        }
        return labels;
    }

    private EditText input(LinearLayout form, String label, String value) {
        TextView title = Ui.text(this, label, 12, MUTED, true);
        form.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 29)));
        EditText input = new EditText(this);
        input.setText(value);
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        input.setBackground(Ui.roundedStroke(0xFFF8FAFD, 0xFFD9E0E9, 9, this));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        params.setMargins(0, 0, 0, Ui.dp(this, 7));
        form.addView(input, params);
        return input;
    }

    private Spinner spinner(LinearLayout form, String label, String[] values, int selection) {
        TextView title = Ui.text(this, label, 12, MUTED, true);
        form.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 28)));
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setSelection(Math.max(0, Math.min(values.length - 1, selection)));
        spinner.setBackground(Ui.roundedStroke(0xFFF8FAFD, 0xFFD9E0E9, 9, this));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        params.setMargins(0, 0, 0, Ui.dp(this, 7));
        form.addView(spinner, params);
        return spinner;
    }

    private void showImportMenu() {
        new AlertDialog.Builder(this)
                .setTitle("选择导入方式")
                .setItems(new String[]{
                        "Excel 课表（.xls / .xlsx）",
                        "课表截图或照片（识别为课程）",
                        "普通截图或照片（生成待整理）"
                }, (dialog, which) -> {
                    if (which == 0) pickExcel();
                    else if (which == 1) pickImage(true);
                    else pickImage(false);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showImageImportMenu() {
        new AlertDialog.Builder(this)
                .setTitle("图片导入")
                .setItems(new String[]{"识别课表图片", "生成待整理待办"}, (dialog, which) -> pickImage(which == 0))
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickImage(boolean timetable) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, !timetable);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, timetable ? REQUEST_IMAGE_TIMETABLE : REQUEST_IMAGE_TODO);
    }

    private void pickExcel() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/octet-stream"
        });
        startActivityForResult(intent, REQUEST_EXCEL);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (resultCode != RESULT_OK || intent == null) return;
        ArrayList<Uri> uris = collectImageUris(intent);
        if (requestCode == REQUEST_EXCEL) {
            Uri uri = intent.getData();
            if (uri == null) return;
            persistReadPermission(uri);
            importExcel(uri);
        } else if (requestCode == REQUEST_IMAGE_TIMETABLE && !uris.isEmpty()) {
            persistReadPermission(uris.get(0));
            processTimetableImage(uris.get(0));
        } else if (requestCode == REQUEST_IMAGE_TODO && !uris.isEmpty()) {
            for (Uri uri : uris) {
                persistReadPermission(uri);
                processInboxImage(uri, "主动导入");
            }
        }
    }

    private ArrayList<Uri> collectImageUris(Intent intent) {
        ArrayList<Uri> uris = new ArrayList<>();
        if (intent.getData() != null) uris.add(intent.getData());
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null && !uris.contains(uri)) uris.add(uri);
            }
        }
        return uris;
    }

    private void persistReadPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
    }

    private void processInboxImage(Uri uri, String source) {
        ImageImportEngine.recognize(this, uri, new ImageImportEngine.Callback() {
            @Override public void onSuccess(ImageImportEngine.Result result) {
                if (result.fullText.trim().isEmpty()) {
                    Toast.makeText(MainActivity.this, "这张图片没有识别到文字，未生成待整理", Toast.LENGTH_SHORT).show();
                    rememberProcessedUri(uri);
                    return;
                }
                InboxItem item = ImageImportEngine.toInboxItem(result, uri, source, selectedWeek);
                data.inboxItems.add(item);
                rememberProcessedUri(uri);
                store.save(data);
                if (currentPage == 1) showPage(1);
                Toast.makeText(MainActivity.this, "已加入“待整理”： " + item.title, Toast.LENGTH_LONG).show();
            }

            @Override public void onError(Exception error) {
                Toast.makeText(MainActivity.this, "图片识别失败，请换一张更清晰的图片", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void processTimetableImage(Uri uri) {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("正在本机识别课表图片…");
        progress.setCancelable(false);
        progress.show();
        ImageImportEngine.recognize(this, uri, new ImageImportEngine.Callback() {
            @Override public void onSuccess(ImageImportEngine.Result result) {
                progress.dismiss();
                ImageImportEngine.TimetableResult parsed = ImageImportEngine.toTimetable(result);
                if (parsed.courses.isEmpty()) {
                    if (!result.fullText.isEmpty()) {
                        data.inboxItems.add(ImageImportEngine.toInboxItem(result, uri, "课表图片待核对", selectedWeek));
                        refreshAll();
                    }
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("未能可靠生成课表")
                            .setMessage(parsed.warning + "\n\n识别文字已放入“待整理”，你可以稍后核对。")
                            .setPositiveButton("知道了", null)
                            .show();
                    return;
                }
                ScheduleData imported = ScheduleData.empty();
                imported.className = "图片识别课表";
                imported.sourceFile = displayName(uri);
                imported.courses.addAll(parsed.courses);
                imported.warnings.add(parsed.warning);
                showImportPreview(imported);
            }

            @Override public void onError(Exception error) {
                progress.dismiss();
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("图片识别失败")
                        .setMessage("请换一张更清晰、包含完整星期栏和节次的课表图片。")
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });
    }

    private void handleIncomingShare(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String type = intent.getType();
        if (type == null || !type.startsWith("image/")) return;
        ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) uris.add(uri);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> values = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (values != null) uris.addAll(values);
        }
        if (uris.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("收到 " + uris.size() + " 张图片")
                .setItems(new String[]{"生成待整理待办", "识别为课表（仅第一张）"}, (dialog, which) -> {
                    if (which == 1) processTimetableImage(uris.get(0));
                    else for (Uri uri : uris) processInboxImage(uri, "分享至课表流");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingShare(intent);
    }

    private void importExcel(Uri uri) {
        String fileName = displayName(uri);
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("正在识别 Excel 课表…");
        progress.setCancelable(false);
        progress.show();
        executor.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalArgumentException("无法读取所选文件");
                List<ScheduleData> schedules = ExcelScheduleImporter.parse(input, fileName);
                runOnUiThread(() -> {
                    progress.dismiss();
                    chooseSchedule(schedules);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("导入失败")
                            .setMessage(error.getMessage() == null ? "无法解析该 Excel 文件" : error.getMessage())
                            .setPositiveButton("知道了", null)
                            .show();
                });
            }
        });
    }

    private void chooseSchedule(List<ScheduleData> schedules) {
        if (schedules.size() == 1) {
            if (data.courses.isEmpty()) replaceWithImported(schedules.get(0));
            else showImportPreview(schedules.get(0));
            return;
        }
        String[] labels = new String[schedules.size()];
        for (int i = 0; i < schedules.size(); i++) {
            ScheduleData item = schedules.get(i);
            labels[i] = item.className + "  ·  " + item.courses.size() + "门";
        }
        new AlertDialog.Builder(this)
                .setTitle("选择你的班级")
                .setItems(labels, (dialog, which) -> {
                    ScheduleData selected = schedules.get(which);
                    if (data.courses.isEmpty()) replaceWithImported(selected);
                    else showImportPreview(selected);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showImportPreview(ScheduleData imported) {
        int previewWeek = findBestWeek(imported);
        int conflicts = imported.conflictCount(previewWeek);
        String message = "识别到 " + imported.courses.size() + " 条课程安排\n"
                + "第" + previewWeek + "周可显示 " + activeCourseCount(imported, previewWeek) + " 条，时间重叠 " + conflicts + " 组\n\n"
                + (conflicts > 0 ? "重叠项通常来自英语、体育等分组选课，导入后可在“设置 > 课程管理”中删除不属于你的候选项。" : "课程时间与周次已准备就绪。")
                + (imported.warnings.isEmpty() ? "" : "\n\n提示：" + imported.warnings.get(0));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("导入预览 · " + imported.className)
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setNeutralButton("合并导入", (d, which) -> mergeImported(imported))
                .setPositiveButton("替换当前课表", (d, which) -> replaceWithImported(imported))
                .create();
        dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(BRAND));
        dialog.show();
    }

    private void replaceWithImported(ScheduleData imported) {
        imported.semesterStartMillis = data.semesterStartMillis;
        imported.periods.clear();
        data.periods.forEach(period -> imported.periods.add(period.copy()));
        imported.todos.addAll(data.todos);
        imported.inboxItems.addAll(data.inboxItems);
        data = imported;
        selectedWeek = findBestWeek(imported);
        store.saveWeek(selectedWeek);
        batchSelectionMode = false;
        selectedCourseIds.clear();
        currentPage = 0;
        refreshAll();
        Toast.makeText(this, "已导入 " + imported.courses.size() + " 条课程，并切换到第" + selectedWeek + "周", Toast.LENGTH_LONG).show();
    }

    private void mergeImported(ScheduleData imported) {
        boolean wasEmpty = data.courses.isEmpty();
        data.courses.addAll(imported.courses);
        data.className = wasEmpty ? imported.className : data.className + " + " + imported.className;
        data.sourceFile = imported.sourceFile;
        data.warnings.addAll(imported.warnings);
        selectedWeek = findBestWeek(data);
        store.saveWeek(selectedWeek);
        currentPage = 0;
        refreshAll();
        Toast.makeText(this, "已合并并切换到第" + selectedWeek + "周", Toast.LENGTH_SHORT).show();
    }

    private void maybeShowDiscoveryIntro() {
        if (featurePreferences.getBoolean(KEY_DISCOVERY_INTRO, false)) {
            startDiscoveryIfAllowed();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("发现新图片 · 默认开启")
                .setMessage("为了把容易忘记的截图和照片变成“待整理”，课表流需要读取你授权范围内的新图片。\n\n暂时没钱开服务器，不会上传任何图片或文字；识别和整理全部在你的手机本地完成。不会回扫历史相册，你也可以随时在设置中关闭。")
                .setNegativeButton("暂不授权", (dialog, which) -> {
                    featurePreferences.edit().putBoolean(KEY_DISCOVERY_INTRO, true).apply();
                })
                .setPositiveButton("允许并开启", (dialog, which) -> {
                    featurePreferences.edit()
                            .putBoolean(KEY_DISCOVERY_INTRO, true)
                            .putBoolean(KEY_DISCOVERY, true)
                            .putLong(KEY_MEDIA_WATERMARK, System.currentTimeMillis() / 1000L)
                            .apply();
                    requestDiscoveryPermission();
                })
                .show();
    }

    private String discoveryStatusText() {
        if (!featurePreferences.getBoolean(KEY_DISCOVERY, true)) return "已关闭；主动导入仍可使用";
        if (!hasImagePermission()) return "默认开启 · 待系统授权（不会扫描历史图片）";
        return "已开启 · 只整理启用后新增且系统允许访问的图片";
    }

    private void showDiscoverySettings() {
        boolean enabled = featurePreferences.getBoolean(KEY_DISCOVERY, true);
        new AlertDialog.Builder(this)
                .setTitle("发现新图片")
                .setMessage("图片与识别文字仅保存在本机，不会上传。Android 只会让应用看到系统授权范围内的图片。")
                .setItems(new String[]{enabled ? "重新授权 / 检查权限" : "开启并授权", "关闭自动发现"}, (dialog, which) -> {
                    if (which == 0) {
                        featurePreferences.edit()
                                .putBoolean(KEY_DISCOVERY, true)
                                .putLong(KEY_MEDIA_WATERMARK, System.currentTimeMillis() / 1000L)
                                .apply();
                        requestDiscoveryPermission();
                    } else {
                        featurePreferences.edit().putBoolean(KEY_DISCOVERY, false).apply();
                        stopImageObserver();
                        showPage(2);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean hasImagePermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            boolean full = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
            boolean partial = Build.VERSION.SDK_INT >= 34
                    && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
            return full || partial;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestDiscoveryPermission() {
        if (hasImagePermission()) {
            startDiscoveryIfAllowed();
            showPage(currentPage);
            return;
        }
        if (Build.VERSION.SDK_INT >= 34) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            }, REQUEST_MEDIA_PERMISSION);
        } else if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQUEST_MEDIA_PERMISSION);
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_MEDIA_PERMISSION);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_MEDIA_PERMISSION) return;
        if (hasImagePermission()) {
            startDiscoveryIfAllowed();
            Toast.makeText(this, "发现新图片已开启，只处理现在起新增的图片", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "未获得图片权限；主动导入仍然可以使用", Toast.LENGTH_LONG).show();
        }
        if (currentPage == 2) showPage(2);
    }

    private void startDiscoveryIfAllowed() {
        if (!featurePreferences.getBoolean(KEY_DISCOVERY, true) || !hasImagePermission()) return;
        if (featurePreferences.getLong(KEY_MEDIA_WATERMARK, 0L) == 0L) {
            featurePreferences.edit().putLong(KEY_MEDIA_WATERMARK, System.currentTimeMillis() / 1000L).apply();
        }
        if (imageObserver == null) {
            imageObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange, Uri uri) {
                    scanForNewImages();
                }
            };
            getContentResolver().registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, imageObserver);
        }
        scanForNewImages();
    }

    private void stopImageObserver() {
        if (imageObserver == null) return;
        try { getContentResolver().unregisterContentObserver(imageObserver); } catch (Exception ignored) {}
        imageObserver = null;
    }

    private void scanForNewImages() {
        if (scanningNewImages || !featurePreferences.getBoolean(KEY_DISCOVERY, true) || !hasImagePermission()) return;
        scanningNewImages = true;
        long watermark = featurePreferences.getLong(KEY_MEDIA_WATERMARK, System.currentTimeMillis() / 1000L);
        executor.execute(() -> {
            ArrayList<Uri> found = new ArrayList<>();
            long newest = watermark;
            String[] projection = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED};
            String selection = MediaStore.Images.Media.DATE_ADDED + " > ?";
            String[] arguments = {String.valueOf(watermark)};
            try (Cursor cursor = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, arguments,
                    MediaStore.Images.Media.DATE_ADDED + " ASC")) {
                if (cursor != null) {
                    int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                    int dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
                    while (cursor.moveToNext() && found.size() < 20) {
                        long id = cursor.getLong(idIndex);
                        long added = cursor.getLong(dateIndex);
                        newest = Math.max(newest, added);
                        Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                        if (!wasProcessed(uri)) found.add(uri);
                    }
                }
            } catch (Exception ignored) {
            }
            long finalNewest = newest;
            runOnUiThread(() -> {
                scanningNewImages = false;
                if (finalNewest > watermark) {
                    featurePreferences.edit().putLong(KEY_MEDIA_WATERMARK, finalNewest).apply();
                }
                for (Uri uri : found) processInboxImage(uri, "自动发现新图片");
            });
        });
    }

    private boolean wasProcessed(Uri uri) {
        return featurePreferences.getStringSet(KEY_PROCESSED_URIS, new HashSet<>()).contains(uri.toString());
    }

    private void rememberProcessedUri(Uri uri) {
        HashSet<String> values = new HashSet<>(featurePreferences.getStringSet(KEY_PROCESSED_URIS, new HashSet<>()));
        values.add(uri.toString());
        if (values.size() > 200) {
            ArrayList<String> limited = new ArrayList<>(values);
            values.clear();
            values.addAll(limited.subList(Math.max(0, limited.size() - 150), limited.size()));
        }
        featurePreferences.edit().putStringSet(KEY_PROCESSED_URIS, values).apply();
    }

    @Override protected void onResume() {
        super.onResume();
        startDiscoveryIfAllowed();
        if (updateManager != null) updateManager.onResume();
    }

    private void pickSemesterStart() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(data.semesterStartMillis);
        new DatePickerDialog(this, (view, year, month, day) -> {
            Calendar selected = Calendar.getInstance();
            selected.set(year, month, day, 0, 0, 0);
            selected.set(Calendar.MILLISECOND, 0);
            int dayOfWeek = selected.get(Calendar.DAY_OF_WEEK);
            int backToMonday = dayOfWeek == Calendar.SUNDAY ? 6 : dayOfWeek - Calendar.MONDAY;
            selected.add(Calendar.DAY_OF_YEAR, -backToMonday);
            data.semesterStartMillis = selected.getTimeInMillis();
            selectedWeek = calculateCurrentWeek();
            store.saveWeek(selectedWeek);
            refreshAll();
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("清空全部数据？")
                .setMessage("所有课程、待办和导入记录都会删除。此操作无法撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    data = ScheduleData.empty();
                    selectedWeek = calculateCurrentWeek();
                    batchSelectionMode = false;
                    selectedCourseIds.clear();
                    currentPage = 0;
                    refreshAll();
                })
                .show();
    }

    private int activeCourseCount(ScheduleData schedule, int week) {
        int count = 0;
        for (Course course : schedule.courses) if (course.isActiveInWeek(week)) count++;
        return count;
    }

    private int activeTodoCount(ScheduleData schedule, int week) {
        int count = 0;
        for (TodoItem todo : schedule.todos) if (!todo.completed && todo.week == week) count++;
        return count;
    }

    private int findBestWeek(ScheduleData schedule) {
        int bestWeek = 1;
        int bestCount = -1;
        for (int week = 1; week <= 20; week++) {
            int count = activeCourseCount(schedule, week) + activeTodoCount(schedule, week);
            if (count > bestCount) {
                bestCount = count;
                bestWeek = week;
            }
        }
        return bestWeek;
    }

    private int calculateCurrentWeek() {
        long difference = System.currentTimeMillis() - data.semesterStartMillis;
        int week = (int) Math.floor(difference / (7d * 24d * 60d * 60d * 1000d)) + 1;
        return Math.max(1, Math.min(20, week));
    }

    private String displayName(Uri uri) {
        String result = "导入课表.xls";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) result = cursor.getString(index);
            }
        } catch (Exception ignored) {
        }
        return result == null ? "导入课表.xls" : result;
    }

    private String formatDate(long millis) {
        return new SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(millis);
    }

    private String dayName(int day) {
        String[] days = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return days[Math.max(0, Math.min(6, day - 1))];
    }

    private String joinNonEmpty(String first, String second) {
        if (first == null || first.isEmpty()) return second == null ? "" : second;
        if (second == null || second.isEmpty()) return first;
        return first + "  ·  " + second;
    }

    @Override public void onBackPressed() {
        if (currentPage != 0) showPage(0);
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        launchHandler.removeCallbacksAndMessages(null);
        if (launchDialog != null && launchDialog.isShowing()) launchDialog.dismiss();
        launchDialog = null;
        stopImageObserver();
        if (updateManager != null) updateManager.destroy();
        executor.shutdownNow();
        super.onDestroy();
    }
}
























