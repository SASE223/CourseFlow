package com.courseflow.app.importer;

import com.courseflow.app.model.Course;
import com.courseflow.app.model.ScheduleData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import jxl.Cell;
import jxl.Sheet;
import jxl.Workbook;
import jxl.WorkbookSettings;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class ExcelScheduleImporter {
    private static final Pattern MARKER = Pattern.compile(
            "^\\s*(\\d{5,9})\\s*[（(]([^）)]*?)周[）)]\\s*[（(](\\d{1,2})\\s*[-—~至]\\s*(\\d{1,2})节[）)]\\s*$");
    private static final Pattern PERIOD_RANGE = Pattern.compile("(\\d{1,2})\\s*[-—~至]\\s*(\\d{1,2})");

    private ExcelScheduleImporter() {}

    public static List<ScheduleData> parse(InputStream input, String fileName) throws Exception {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        List<Grid> grids;
        if (lower.endsWith(".xlsx")) {
            grids = readXlsx(input);
        } else if (lower.endsWith(".xls")) {
            grids = readXls(input);
        } else {
            byte[] bytes = readAll(input);
            try {
                grids = readXlsx(new ByteArrayInputStream(bytes));
            } catch (Exception ignored) {
                grids = readXls(new ByteArrayInputStream(bytes));
            }
        }
        ArrayList<ScheduleData> schedules = new ArrayList<>();
        for (Grid grid : grids) {
            List<ScheduleData> parsed = parseGridLayout(grid, fileName);
            if (parsed.isEmpty()) parsed = parseTableLayout(grid, fileName);
            schedules.addAll(parsed);
        }
        schedules.removeIf(item -> item.courses.isEmpty());
        if (schedules.isEmpty()) {
            throw new IllegalArgumentException("没有识别到课程。请确认文件包含班级、课程、星期、节次和周次信息。");
        }
        return schedules;
    }

    private static List<Grid> readXls(InputStream input) throws Exception {
        WorkbookSettings settings = new WorkbookSettings();
        settings.setEncoding("GBK");
        Workbook workbook = Workbook.getWorkbook(input, settings);
        ArrayList<Grid> grids = new ArrayList<>();
        try {
            for (Sheet sheet : workbook.getSheets()) {
                if (sheet.getRows() == 0 || sheet.getColumns() == 0) continue;
                grids.add(new Grid() {
                    @Override public String name() { return sheet.getName(); }
                    @Override public int rows() { return sheet.getRows(); }
                    @Override public int columns() { return sheet.getColumns(); }
                    @Override public String cell(int row, int column) {
                        if (row < 0 || column < 0 || row >= rows() || column >= columns()) return "";
                        Cell cell = sheet.getCell(column, row);
                        return cell == null ? "" : clean(cell.getContents());
                    }
                });
            }
            ArrayList<Grid> snapshots = new ArrayList<>();
            for (Grid grid : grids) snapshots.add(GridSnapshot.of(grid));
            return snapshots;
        } finally {
            workbook.close();
        }
    }

    private static List<ScheduleData> parseGridLayout(Grid grid, String fileName) {
        int classHeaderRow = -1;
        for (int row = 0; row < Math.min(10, grid.rows()); row++) {
            String first = grid.cell(row, 0);
            if (first.contains("班级") || first.contains("班别") || first.contains("星期")) {
                classHeaderRow = row;
                break;
            }
        }
        if (classHeaderRow < 0 || grid.columns() < 11) return Collections.emptyList();
        int dataStart = classHeaderRow + 1;
        if (dataStart < grid.rows() && grid.cell(dataStart, 0).contains("节")) dataStart++;
        ArrayList<ScheduleData> schedules = new ArrayList<>();
        for (int row = dataStart; row < grid.rows(); row++) {
            String className = grid.cell(row, 0);
            if (className.isEmpty()) continue;
            ScheduleData data = new ScheduleData();
            data.courses.clear();
            data.className = className;
            data.sourceFile = fileName == null ? "" : fileName;
            int unparsed = 0;
            int usableColumns = Math.min(grid.columns(), 36);
            for (int column = 1; column < usableColumns; column++) {
                String raw = grid.cell(row, column);
                if (raw.isEmpty()) continue;
                int fallbackDay = ((column - 1) / 5) + 1;
                int pairIndex = (column - 1) % 5;
                int fallbackStart = pairIndex * 2 + 1;
                List<Course> courses = parseCourseCell(raw, fallbackDay, fallbackStart);
                if (courses.isEmpty()) unparsed++;
                data.courses.addAll(courses);
            }
            if (!data.courses.isEmpty()) {
                if (unparsed > 0) data.warnings.add(unparsed + " 个单元格未能完整识别，可在课程管理中补充");
                int conflicts = data.conflictCount(2);
                if (conflicts > 0) data.warnings.add("第2周检测到 " + conflicts + " 组时间重叠，可能是分组候选课程");
                schedules.add(data);
            }
        }
        return schedules;
    }

    private static List<ScheduleData> parseTableLayout(Grid grid, String fileName) {
        for (int headerRow = 0; headerRow < Math.min(15, grid.rows()); headerRow++) {
            Map<String, Integer> columns = mapHeaders(grid, headerRow);
            if (!columns.containsKey("name") || !columns.containsKey("day") || !columns.containsKey("period")) continue;
            ScheduleData data = new ScheduleData();
            data.courses.clear();
            data.className = grid.name().isEmpty() ? "导入课表" : grid.name();
            data.sourceFile = fileName == null ? "" : fileName;
            for (int row = headerRow + 1; row < grid.rows(); row++) {
                String name = value(grid, row, columns.get("name"));
                if (name.isEmpty()) continue;
                Course course = new Course();
                course.name = name;
                course.teacher = value(grid, row, columns.get("teacher"));
                course.room = value(grid, row, columns.get("room"));
                course.day = parseDay(value(grid, row, columns.get("day")));
                Matcher range = PERIOD_RANGE.matcher(value(grid, row, columns.get("period")));
                if (range.find()) {
                    course.startPeriod = clamp(parseInt(range.group(1), 1), 1, 12);
                    course.endPeriod = clamp(parseInt(range.group(2), course.startPeriod), course.startPeriod, 12);
                } else {
                    int period = clamp(parseInt(value(grid, row, columns.get("period")), 1), 1, 12);
                    course.startPeriod = period;
                    course.endPeriod = period;
                }
                course.weeks.addAll(Course.parseWeeks(value(grid, row, columns.get("weeks"))));
                course.color = Course.colorFor(course.name);
                data.courses.add(course);
            }
            if (!data.courses.isEmpty()) return Collections.singletonList(data);
        }
        return Collections.emptyList();
    }

    private static Map<String, Integer> mapHeaders(Grid grid, int row) {
        HashMap<String, Integer> columns = new HashMap<>();
        for (int col = 0; col < Math.min(80, grid.columns()); col++) {
            String header = grid.cell(row, col).replace(" ", "").toLowerCase(Locale.ROOT);
            if (header.contains("课程") || header.equals("名称") || header.equals("name")) columns.put("name", col);
            else if (header.contains("星期") || header.contains("周几") || header.equals("day")) columns.put("day", col);
            else if (header.contains("节次") || header.contains("节数") || header.equals("period")) columns.put("period", col);
            else if (header.contains("周次") || header.equals("weeks")) columns.put("weeks", col);
            else if (header.contains("教师") || header.contains("老师") || header.equals("teacher")) columns.put("teacher", col);
            else if (header.contains("教室") || header.contains("地点") || header.equals("room")) columns.put("room", col);
        }
        return columns;
    }

    private static List<Course> parseCourseCell(String raw, int fallbackDay, int fallbackStart) {
        String[] sourceLines = raw.replace("\r", "").split("\n");
        ArrayList<String> lines = new ArrayList<>();
        for (String line : sourceLines) {
            String cleaned = clean(line);
            if (!cleaned.isEmpty()) lines.add(cleaned);
        }
        ArrayList<Integer> markers = new ArrayList<>();
        ArrayList<MatcherData> markerData = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = MARKER.matcher(lines.get(i));
            if (matcher.matches()) {
                markers.add(i);
                markerData.add(new MatcherData(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4)));
            }
        }
        ArrayList<Course> courses = new ArrayList<>();
        int recordStart = 0;
        for (int index = 0; index < markers.size(); index++) {
            int markerLine = markers.get(index);
            MatcherData marker = markerData.get(index);
            if (markerLine - recordStart < 2) {
                recordStart = Math.min(lines.size(), markerLine + 2);
                continue;
            }
            String teacher = lines.get(markerLine - 1);
            StringBuilder name = new StringBuilder();
            for (int line = recordStart; line < markerLine - 1; line++) {
                if (name.length() > 0) name.append(' ');
                name.append(lines.get(line));
            }
            String room = markerLine + 1 < lines.size() ? lines.get(markerLine + 1) : "";
            Course course = new Course();
            course.name = name.length() == 0 ? "未命名课程" : name.toString();
            course.teacher = teacher;
            course.room = room;
            int encodedDay = marker.code.isEmpty() ? fallbackDay : Character.digit(marker.code.charAt(0), 10);
            course.day = encodedDay >= 1 && encodedDay <= 7 ? encodedDay : fallbackDay;
            course.startPeriod = clamp(parseInt(marker.start, fallbackStart), 1, 12);
            course.endPeriod = clamp(parseInt(marker.end, course.startPeriod + 1), course.startPeriod, 12);
            course.weeks.addAll(Course.parseWeeks(marker.weeks));
            course.color = Course.colorFor(course.name);
            courses.add(course);
            recordStart = Math.min(lines.size(), markerLine + 2);
        }
        return courses;
    }

    private static int parseDay(String value) {
        if (value == null) return 1;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        for (int i = 0; i < names.length; i++) if (normalized.contains(names[i])) return i + 1;
        return clamp(parseInt(normalized, 1), 1, 7);
    }

    private static String value(Grid grid, int row, Integer column) {
        return column == null ? "" : grid.cell(row, column);
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        Matcher matcher = Pattern.compile("\\d+").matcher(value);
        if (!matcher.find()) return fallback;
        try { return Integer.parseInt(matcher.group()); } catch (Exception ignored) { return fallback; }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\u00A0', ' ').replaceAll("[\\t ]+", " ").trim();
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static List<Grid> readXlsx(InputStream input) throws Exception {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.equals("xl/sharedStrings.xml") || name.matches("xl/worksheets/sheet\\d+\\.xml")) {
                    files.put(name, readAll(zip));
                }
            }
        }
        if (files.isEmpty()) throw new IllegalArgumentException("不是有效的 .xlsx 文件");
        ArrayList<String> shared = parseSharedStrings(files.get("xl/sharedStrings.xml"));
        ArrayList<Grid> grids = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            if (!entry.getKey().startsWith("xl/worksheets/sheet")) continue;
            GridSnapshot snapshot = parseSheetXml(entry.getValue(), shared, entry.getKey());
            if (snapshot.rows() > 0) grids.add(snapshot);
        }
        return grids;
    }

    private static ArrayList<String> parseSharedStrings(byte[] xml) throws Exception {
        ArrayList<String> values = new ArrayList<>();
        if (xml == null) return values;
        Document document = parseXml(xml);
        NodeList items = document.getElementsByTagName("si");
        for (int i = 0; i < items.getLength(); i++) values.add(textOf(items.item(i)));
        return values;
    }

    private static GridSnapshot parseSheetXml(byte[] xml, List<String> shared, String name) throws Exception {
        Document document = parseXml(xml);
        NodeList cells = document.getElementsByTagName("c");
        HashMap<Integer, HashMap<Integer, String>> rows = new HashMap<>();
        int maxRow = -1;
        int maxColumn = -1;
        for (int i = 0; i < cells.getLength(); i++) {
            Element cell = (Element) cells.item(i);
            String reference = cell.getAttribute("r");
            int column = columnFromReference(reference);
            int row = rowFromReference(reference);
            if (row < 0 || column < 0) continue;
            String type = cell.getAttribute("t");
            String value;
            if ("inlineStr".equals(type)) {
                value = textOf(cell);
            } else {
                NodeList rawValues = cell.getElementsByTagName("v");
                value = rawValues.getLength() == 0 ? "" : rawValues.item(0).getTextContent();
                if ("s".equals(type)) {
                    int sharedIndex = parseInt(value, -1);
                    value = sharedIndex >= 0 && sharedIndex < shared.size() ? shared.get(sharedIndex) : "";
                }
            }
            rows.computeIfAbsent(row, unused -> new HashMap<>()).put(column, clean(value));
            maxRow = Math.max(maxRow, row);
            maxColumn = Math.max(maxColumn, column);
        }
        String[][] values = new String[maxRow + 1][maxColumn + 1];
        for (int row = 0; row <= maxRow; row++) {
            for (int column = 0; column <= maxColumn; column++) values[row][column] = "";
            Map<Integer, String> rowValues = rows.get(row);
            if (rowValues != null) for (Map.Entry<Integer, String> value : rowValues.entrySet()) values[row][value.getKey()] = value.getValue();
        }
        return new GridSnapshot(name.replace("xl/worksheets/", "").replace(".xml", ""), values);
    }

    private static Document parseXml(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static String textOf(Node node) {
        StringBuilder value = new StringBuilder();
        collectText(node, value);
        return value.toString();
    }

    private static void collectText(Node node, StringBuilder value) {
        if (node.getNodeType() == Node.TEXT_NODE) {
            value.append(node.getNodeValue());
            return;
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) collectText(children.item(i), value);
    }

    private static int columnFromReference(String reference) {
        int column = 0;
        int letters = 0;
        for (int i = 0; i < reference.length(); i++) {
            char ch = reference.charAt(i);
            if (!Character.isLetter(ch)) break;
            column = column * 26 + (Character.toUpperCase(ch) - 'A' + 1);
            letters++;
        }
        return letters == 0 ? -1 : column - 1;
    }

    private static int rowFromReference(String reference) {
        String digits = reference.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? -1 : parseInt(digits, 0) - 1;
    }

    private interface Grid {
        String name();
        int rows();
        int columns();
        String cell(int row, int column);
    }

    private static final class GridSnapshot implements Grid {
        private final String name;
        private final String[][] values;

        GridSnapshot(String name, String[][] values) {
            this.name = name;
            this.values = values;
        }

        static GridSnapshot of(Grid source) {
            String[][] values = new String[source.rows()][source.columns()];
            for (int row = 0; row < source.rows(); row++) {
                for (int column = 0; column < source.columns(); column++) values[row][column] = source.cell(row, column);
            }
            return new GridSnapshot(source.name(), values);
        }

        @Override public String name() { return name; }
        @Override public int rows() { return values.length; }
        @Override public int columns() { return values.length == 0 ? 0 : values[0].length; }
        @Override public String cell(int row, int column) {
            if (row < 0 || column < 0 || row >= rows() || column >= columns()) return "";
            String value = values[row][column];
            return value == null ? "" : value;
        }
    }

    private static final class MatcherData {
        final String code;
        final String weeks;
        final String start;
        final String end;

        MatcherData(String code, String weeks, String start, String end) {
            this.code = code;
            this.weeks = weeks;
            this.start = start;
            this.end = end;
        }
    }
}


