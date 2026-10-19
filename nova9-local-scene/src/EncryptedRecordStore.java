import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/** Password-encrypted, text-only record persistence using only the JDK. */
final class EncryptedRecordStore {
    private static final byte[] MAGIC = "N9AR1".getBytes(StandardCharsets.US_ASCII);
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    record SaveResult(Path encryptedDetails, Path plainSummary, Path recordInfo) { }

    static SaveResult save(Path recordsRoot, LocalDate date, List<ActivityRecord> records, char[] password)
            throws IOException, GeneralSecurityException {
        if (records.isEmpty()) throw new IllegalArgumentException("没有可保存的活动记录");
        if (password == null || password.length < 8) throw new IllegalArgumentException("记录密码至少需要8个字符");

        Path dayDir = recordsRoot.resolve(date.format(DateTimeFormatter.ofPattern("yyyy-MM")))
                .resolve(date.toString()).toAbsolutePath().normalize();
        Path normalizedRoot = recordsRoot.toAbsolutePath().normalize();
        if (!dayDir.startsWith(normalizedRoot)) throw new SecurityException("记录路径超出指定目录");
        Files.createDirectories(dayDir);

        byte[] json = recordsToJson(date, records).getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = encrypt(json, password);
        Arrays.fill(json, (byte)0);

        Path details = dayDir.resolve("activity-details.json.enc");
        Path summary = dayDir.resolve("daily-summary.md");
        Path info = dayDir.resolve("record-info.json");
        try {
            writeAtomically(details, encrypted);
            writeAtomically(summary, summaryMarkdown(date, records).getBytes(StandardCharsets.UTF_8));
            writeAtomically(info, recordInfo(date, records.size()).getBytes(StandardCharsets.UTF_8));
        } finally {
            Arrays.fill(encrypted, (byte)0);
        }
        return new SaveResult(details, summary, info);
    }

    static String decryptToJson(Path file, char[] password) throws IOException, GeneralSecurityException {
        byte[] encoded = Files.readAllBytes(file);
        byte[] plain = null;
        try {
            plain = decrypt(encoded, password);
            return new String(plain, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(encoded, (byte)0);
            if (plain != null) Arrays.fill(plain, (byte)0);
        }
    }

    private static String recordsToJson(LocalDate date, List<ActivityRecord> records) {
        StringBuilder out = new StringBuilder(2048 + records.size() * 1024);
        out.append("{\n  \"schema_version\": 1,\n  \"date\": \"").append(date)
                .append("\",\n  \"images_saved\": false,\n  \"videos_saved\": false,\n  \"records\": [\n");
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) out.append(",\n");
            out.append("    ").append(records.get(i).toJson());
        }
        return out.append("\n  ]\n}\n").toString();
    }

    private static String summaryMarkdown(LocalDate date, List<ActivityRecord> records) {
        StringBuilder out = new StringBuilder("# " + date + " 活动总结\n\n");
        out.append("> 这是未加密的简要时间线；详细观察和模型原始回答保存在加密文件中。\n\n");
        DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm");
        for (int start = 0; start < records.size();) {
            int end = start;
            while (end + 1 < records.size()
                    && records.get(end + 1).category.equals(records.get(start).category)) end++;
            ActivityRecord first = records.get(start), last = records.get(end);
            String period = first.observedAt.format(time);
            if (end > start) period += "–" + last.observedAt.format(time);
            out.append("- ").append(period).append("｜").append(first.category)
                    .append("｜").append(groupConfidence(records, start, end)).append('\n');
            start = end + 1;
        }
        return out.toString();
    }

    private static String groupConfidence(List<ActivityRecord> records, int start, int end) {
        if (records.get(start).isDataGap()) return "不适用";
        boolean medium = false;
        for (int i = start; i <= end; i++) {
            if ("低".equals(records.get(i).confidence)) return "低";
            if ("中".equals(records.get(i).confidence)) medium = true;
        }
        return medium ? "中" : "高";
    }

    private static String recordInfo(LocalDate date, int count) {
        return "{\n"
                + "  \"schema_version\": 1,\n"
                + "  \"date\": \"" + date + "\",\n"
                + "  \"record_count\": " + count + ",\n"
                + "  \"encryption\": \"AES-256-GCM + PBKDF2-HMAC-SHA256\",\n"
                + "  \"model\": \"MiniCPM-V 4.6 Q4_K_M\",\n"
                + "  \"images_saved\": false,\n"
                + "  \"videos_saved\": false\n"
                + "}\n";
    }

    private static byte[] encrypt(byte[] plain, char[] password) throws GeneralSecurityException, IOException {
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(salt); RANDOM.nextBytes(nonce);
        SecretKey key = deriveKey(password, salt, ITERATIONS);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(MAGIC);
        byte[] ciphertext = cipher.doFinal(plain);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(bytes)) {
            out.write(MAGIC); out.writeInt(ITERATIONS); out.write(salt); out.write(nonce); out.write(ciphertext);
            return bytes.toByteArray();
        } finally {
            Arrays.fill(salt, (byte)0); Arrays.fill(nonce, (byte)0); Arrays.fill(ciphertext, (byte)0);
        }
    }

    private static byte[] decrypt(byte[] encoded, char[] password) throws GeneralSecurityException, IOException {
        int header = MAGIC.length + Integer.BYTES + SALT_BYTES + NONCE_BYTES;
        if (encoded.length <= header) throw new IOException("加密记录文件不完整");
        ByteBuffer in = ByteBuffer.wrap(encoded);
        byte[] magic = new byte[MAGIC.length]; in.get(magic);
        if (!Arrays.equals(magic, MAGIC)) throw new IOException("不是Nova 9活动记录文件");
        int iterations = in.getInt();
        if (iterations < 100_000 || iterations > 2_000_000) throw new IOException("加密参数不正确");
        byte[] salt = new byte[SALT_BYTES]; in.get(salt);
        byte[] nonce = new byte[NONCE_BYTES]; in.get(nonce);
        byte[] ciphertext = new byte[in.remaining()]; in.get(ciphertext);
        try {
            SecretKey key = deriveKey(password, salt, iterations);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(MAGIC);
            return cipher.doFinal(ciphertext);
        } finally {
            Arrays.fill(magic, (byte)0); Arrays.fill(salt, (byte)0);
            Arrays.fill(nonce, (byte)0); Arrays.fill(ciphertext, (byte)0);
        }
    }

    private static SecretKey deriveKey(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            try { return new SecretKeySpec(raw, "AES"); }
            finally { Arrays.fill(raw, (byte)0); }
        } finally { spec.clearPassword(); }
    }

    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, content, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
            Arrays.fill(content, (byte)0);
        }
    }
}
