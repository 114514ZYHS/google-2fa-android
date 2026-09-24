// 从 MainActivity 原样抽取的核心算法，用于独立验证
import java.nio.ByteBuffer;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class TotpTest {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    static String totp(String algorithm, int digits, int period, String secret, long seconds) throws Exception {
        byte[] key = decodeBase32(secret);
        if (key.length == 0) throw new Exception("Empty key");
        ByteBuffer counter = ByteBuffer.allocate(8).putLong(seconds / period);
        Mac mac = Mac.getInstance("Hmac" + algorithm);
        mac.init(new SecretKeySpec(key, "Hmac" + algorithm));
        byte[] hash = mac.doFinal(counter.array());
        int offset = hash[hash.length - 1] & 0x0f;
        if (offset + 4 > hash.length) throw new Exception("Invalid HMAC result");
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
        long modulus = digits == 8 ? 100_000_000L : 1_000_000L;
        return String.format(Locale.US, "%0" + digits + "d", binary % modulus);
    }

    static byte[] decodeBase32(String value) {
        String clean = normalize(value);
        int buffer = 0, bits = 0, count = 0;
        byte[] output = new byte[clean.length() * 5 / 8];
        for (char character : clean.toCharArray()) {
            int index = ALPHABET.indexOf(character);
            if (index < 0) return new byte[0];
            buffer = (buffer << 5) | index;
            bits += 5;
            if (bits >= 8) { output[count++] = (byte) ((buffer >> (bits - 8)) & 0xff); bits -= 8; }
        }
        byte[] result = new byte[count];
        System.arraycopy(output, 0, result, 0, count);
        return result;
    }

    static String normalize(String value) {
        if (value == null) return "";
        return value.toUpperCase(Locale.US).replaceAll("[^A-Z2-7]", "");
    }

    static String formatCode(String code) {
        if (code.length() == 8) return code.substring(0, 4) + " " + code.substring(4);
        if (code.length() == 6) return code.substring(0, 3) + " " + code.substring(3);
        return code;
    }

    static int pass = 0, fail = 0;
    static void check(String label, String expect, String actual) {
        boolean ok = expect.equals(actual);
        if (ok) pass++; else fail++;
        System.out.printf("%s %-58s 期望=%-10s 实际=%s%n", ok ? "  OK  " : " FAIL ", label, expect, actual);
    }

    public static void main(String[] args) throws Exception {
        // ---- RFC 6238 官方测试向量：seed = "12345678901234567890" (ASCII)
        // SHA1 20字节 => Base32 "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        String s1 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        // SHA256 32字节
        String s256 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA";
        // SHA512 64字节
        String s512 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNA";

        System.out.println("== RFC 6238 官方测试向量 (8位) ==");
        long[] times = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[] sha1   = {"94287082","07081804","14050471","89005924","69279037","65353130"};
        String[] sha256 = {"46119246","68084774","67062674","91819424","90698825","77737706"};
        String[] sha512 = {"90693936","25091201","99943326","93441116","38618901","47863826"};
        for (int i = 0; i < times.length; i++) {
            check("SHA1   t=" + times[i], sha1[i],   totp("SHA1",   8, 30, s1,   times[i]));
            check("SHA256 t=" + times[i], sha256[i], totp("SHA256", 8, 30, s256, times[i]));
            check("SHA512 t=" + times[i], sha512[i], totp("SHA512", 8, 30, s512, times[i]));
        }

        System.out.println("\n== 6 位码 / 自定义周期 ==");
        check("SHA1 6位 t=59", "287082", totp("SHA1", 6, 30, s1, 59L));
        // 60 秒周期：t=0..59 都落在 counter=0 窗口，应与 30 秒周期下 t=0 的结果一致
        check("周期60s t=0 与 周期30s t=0 一致", totp("SHA1", 6, 30, s1, 0L), totp("SHA1", 6, 60, s1, 0L));
        check("周期60s t=59 与 t=0 同窗口", totp("SHA1", 6, 60, s1, 0L), totp("SHA1", 6, 60, s1, 59L));
        check("周期60s t=60 进入下一窗口", "true", String.valueOf(!totp("SHA1",6,60,s1,0L).equals(totp("SHA1",6,60,s1,60L))));

        System.out.println("\n== Base32 解码 / normalize ==");
        byte[] key = decodeBase32("gezd gnbv-gy3t qojq GEZDGNBVGY3TQOJQ");
        check("含空格/小写/连字符的密钥长度", "20", String.valueOf(key.length));
        check("空输入返回空", "0", String.valueOf(decodeBase32("").length));
        check("非法字符返回空", "0", String.valueOf(decodeBase32("11!!99").length));
        // 补位用例：长度非 8 倍数的 Base32
        check("非整字节 Base32 正常解码(JBSWY3DP)", "5", String.valueOf(decodeBase32("JBSWY3DP").length));
        check("奇数长度 Base32 (JBSWY3D)", "4", String.valueOf(decodeBase32("JBSWY3D").length));

        System.out.println("\n== 显示格式 ==");
        check("6位分组显示", "123 456", formatCode("123456"));
        check("8位分组显示", "1234 5678", formatCode("12345678"));

        System.out.printf("%n结果：通过 %d 项，失败 %d 项%n", pass, fail);
        if (fail > 0) System.exit(1);
    }
}
