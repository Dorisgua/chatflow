import javax.crypto.AEADBadTagException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Verifies record parsing, authenticated encryption, wrong-password rejection and summary minimization. */
public final class RecordStorageSelfTest {
    public static void main(String[] args) throws Exception {
        Path home = Path.of(System.getProperty("nova9.scene.home", ".")).toAbsolutePath().normalize();
        Path build = home.resolve("build").toAbsolutePath().normalize();
        Files.createDirectories(build);
        Path testRoot = Files.createTempDirectory(build, "record-self-test-").toAbsolutePath().normalize();
        if (!testRoot.startsWith(build)) throw new SecurityException("测试目录超出build目录");
        char[] password = "local-test-password-2026".toCharArray();
        char[] wrong = "wrong-test-password".toCharArray();
        try {
            String raw = """
                    活动类别：阅读学习
                    可信度：中
                    场景：室内桌面区域
                    直接观察：一人坐在桌旁，桌上有打开的书本
                    活动推测：可能正在阅读或整理材料
                    判断依据：人物面向书本并保持坐姿
                    不确定性：无法确认具体阅读内容
                    """;
            ActivityRecord record = ActivityRecord.fromRaw(
                    raw, OffsetDateTime.parse("2026-08-17T10:15:30+08:00"), 5600, 1);
            if (!"阅读学习".equals(record.category)) throw new AssertionError("活动类别解析失败");
            if (!record.timelineLine().contains("5.6秒")) throw new AssertionError("时间线没有显示推理耗时");
            ActivityRecord gap = ActivityRecord.dataGap(OffsetDateTime.parse("2026-08-17T10:17:40+08:00"));
            EncryptedRecordStore.SaveResult saved = EncryptedRecordStore.save(
                    testRoot.resolve("records"), LocalDate.of(2026, 8, 17), List.of(record, gap), password);
            String restored = EncryptedRecordStore.decryptToJson(saved.encryptedDetails(), password);
            if (!restored.contains("模型未单独说明") && !restored.contains("人物面向书本并保持坐姿"))
                throw new AssertionError("详细记录没有正确还原");
            if (!restored.contains("活动类别：阅读学习")) throw new AssertionError("原始回答没有保存");
            if (!restored.contains("\"record_type\":\"data_gap\"") || !restored.contains("\"model\":\"not_called\""))
                throw new AssertionError("视频中断没有保存为不调用模型的数据缺失记录");
            String summary = Files.readString(saved.plainSummary(), StandardCharsets.UTF_8);
            if (!summary.contains("阅读学习") || summary.contains("具体阅读内容") || summary.contains("打开的书本"))
                throw new AssertionError("明文总结包含了不应出现的详细信息");
            if (!summary.contains("数据缺失") || summary.contains("数据缺失｜高"))
                throw new AssertionError("明文总结没有正确标记数据缺失");
            boolean wrongRejected = false;
            try { EncryptedRecordStore.decryptToJson(saved.encryptedDetails(), wrong); }
            catch (AEADBadTagException expected) { wrongRejected = true; }
            if (!wrongRejected) throw new AssertionError("错误密码没有被拒绝");
            System.out.println("RECORD SELF-TEST OK: parse, AES-GCM save/read, wrong-password rejection, and minimal summary passed.");
        } finally {
            Arrays.fill(password, '\0'); Arrays.fill(wrong, '\0');
            if (Files.exists(testRoot)) {
                try (var paths = Files.walk(testRoot)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    });
                }
            }
        }
    }
}
