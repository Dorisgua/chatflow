import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** One text-only activity observation. It never contains image bytes or camera credentials. */
final class ActivityRecord {
    static final List<String> CATEGORIES = List.of(
            "电脑工作", "阅读学习", "面对面交流", "用餐饮水", "行走移动",
            "整理物品", "休息", "离开画面", "其他", "无法判断");

    final String id;
    final OffsetDateTime observedAt;
    final String recordType;
    final String category;
    final String confidence;
    final String scene;
    final String directObservation;
    final String likelyActivity;
    final String evidence;
    final String uncertainty;
    final String rawModelOutput;
    final String displayOutput;
    final long inferenceMs;
    final int frameCount;

    private ActivityRecord(String id, OffsetDateTime observedAt, String recordType,
                           String category, String confidence,
                           String scene, String directObservation, String likelyActivity,
                           String evidence, String uncertainty, String rawModelOutput,
                           String displayOutput, long inferenceMs, int frameCount) {
        this.id = id;
        this.observedAt = observedAt;
        this.recordType = recordType;
        this.category = category;
        this.confidence = confidence;
        this.scene = scene;
        this.directObservation = directObservation;
        this.likelyActivity = likelyActivity;
        this.evidence = evidence;
        this.uncertainty = uncertainty;
        this.rawModelOutput = rawModelOutput;
        this.displayOutput = displayOutput;
        this.inferenceMs = inferenceMs;
        this.frameCount = frameCount;
    }

    static ActivityRecord fromRaw(String raw, OffsetDateTime observedAt, long inferenceMs, int frameCount) {
        String cleanRaw = raw == null ? "" : raw.trim();
        String category = normalizeCategory(field(cleanRaw, "活动类别"));
        String confidence = normalizeConfidence(field(cleanRaw, "可信度"));
        String scene = fallback(field(cleanRaw, "场景"), "未明确描述");
        String observation = fallback(field(cleanRaw, "直接观察"), firstSentence(cleanRaw));
        String likely = fallback(field(cleanRaw, "活动推测"), category);
        String evidence = fallback(field(cleanRaw, "判断依据"), "模型未单独说明");
        String uncertainty = fallback(field(cleanRaw, "不确定性"), "模型未单独说明");
        String display = "活动：" + category + "\n"
                + "可信度：" + confidence + "\n"
                + "场景：" + scene + "\n\n"
                + "直接观察：" + observation + "\n"
                + "活动推测：" + likely + "\n"
                + "判断依据：" + evidence + "\n"
                + "不确定性：" + uncertainty;
        return new ActivityRecord(
                observedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT))
                        + "-" + UUID.randomUUID().toString().substring(0, 8),
                observedAt, "activity", category, confidence, scene, observation, likely, evidence,
                uncertainty, cleanRaw, display, inferenceMs, frameCount);
    }

    static ActivityRecord dataGap(OffsetDateTime detectedAt) {
        String message = "超过10秒未收到新画面，程序已清空旧画面并自动暂停推理";
        return new ActivityRecord(
                detectedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT))
                        + "-gap-" + UUID.randomUUID().toString().substring(0, 8),
                detectedAt, "data_gap", "数据缺失", "不适用", "视频流中断",
                message, "未进行活动推测", "没有新鲜画面", "中断期间的活动未知",
                "", "数据缺失：" + message, 0, 0);
    }

    boolean isDataGap() { return "data_gap".equals(recordType); }

    String timelineLine() {
        String duration = isDataGap() ? "—" : String.format(Locale.ROOT, "%.1f秒", inferenceMs / 1000.0);
        return observedAt.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                + "｜" + category + "｜" + confidence + "｜" + duration;
    }

    String toJson() {
        return "{" +
                "\"record_id\":" + quote(id) + "," +
                "\"observed_at\":" + quote(observedAt.toString()) + "," +
                "\"record_type\":" + quote(recordType) + "," +
                "\"activity\":{" +
                    "\"category\":" + quote(category) + "," +
                    "\"confidence\":" + quote(confidence) + "}," +
                "\"direct_observation\":{" +
                    "\"scene\":" + quote(scene) + "," +
                    "\"description\":" + quote(directObservation) + "}," +
                "\"inference\":{" +
                    "\"likely_activity\":" + quote(likelyActivity) + "," +
                    "\"evidence\":" + quote(evidence) + "," +
                    "\"uncertainty\":" + quote(uncertainty) + "}," +
                "\"model_output\":{" +
                    "\"raw_text\":" + quote(rawModelOutput) + "," +
                    "\"display_text\":" + quote(displayOutput) + "}," +
                "\"technical\":{" +
                    "\"model\":" + quote(isDataGap() ? "not_called" : "MiniCPM-V 4.6 Q4_K_M") + "," +
                    "\"prompt_version\":" + quote(isDataGap() ? "not_called" : "activity-v1") + "," +
                    "\"frames_analyzed\":" + frameCount + "," +
                    "\"input_max_side\":448," +
                    "\"temperature\":0.1," +
                    "\"max_tokens\":220," +
                    "\"inference_ms\":" + inferenceMs + "," +
                    "\"image_saved\":false}," +
                "\"review\":{" +
                    "\"status\":\"未确认\"," +
                    "\"corrected_activity\":null," +
                    "\"user_note\":null}" +
                "}";
    }

    private static String field(String raw, String label) {
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(label + "：")) return trimmed.substring(label.length() + 1).trim();
            if (trimmed.startsWith(label + ":")) return trimmed.substring(label.length() + 1).trim();
        }
        return "";
    }

    private static String normalizeCategory(String value) {
        for (String category : CATEGORIES) if (value.contains(category)) return category;
        return "无法判断";
    }

    private static String normalizeConfidence(String value) {
        if (value.contains("高")) return "高";
        if (value.contains("低")) return "低";
        return "中";
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String firstSentence(String raw) {
        String one = raw.replaceAll("\\s+", " ").trim();
        for (int i = 0; i < one.length(); i++) {
            if ("。！？!?".indexOf(one.charAt(i)) >= 0) return one.substring(0, i + 1);
        }
        return one.length() > 120 ? one.substring(0, 119) + "…" : one;
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
