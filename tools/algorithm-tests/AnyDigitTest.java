import java.nio.ByteBuffer;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 任意位数 TOTP 验证。
 *
 * 重点验证新实现把「10^digits」做成通用公式后，
 * 1~18 位任意位数都能正确输出，不再只有 6 位/8 位两种模式。
 */
public class AnyDigitTest {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("== 任意位数 TOTP 验证 ==");

        // RFC 6238 附录 B 的基准密钥，ASCII "12345678901234567890"
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

        // 1. 位数正确性：输出长度必须严格等于请求的位数
        System.out.println("\n-- 输出长度 --");
        for (int digits = 1; digits <= 18; digits++) {
            String code = totp("SHA1", digits, 30, secret, 59L);
            check("位数 " + digits + " 输出长度", digits, code.length());
            checkTrue("位数 " + digits + " 全为数字", code.matches("\\d{" + digits + "}"));
        }

        // 2. 与标准向量对照
        //    RFC 6238 附录 B：t=59 时 SHA1 的 8 位结果为 94287082，
        //    其低 6 位为 287082（6 位模式即取低 6 位）。
        System.out.println("\n-- 与 RFC 6238 向量对照 --");
        checkEquals("SHA1 t=59 八位", "94287082", totp("SHA1", 8, 30, secret, 59L));
        checkEquals("SHA1 t=59 六位", "287082", totp("SHA1", 6, 30, secret, 59L));

        // 3. 七位等非标准位数应等于同一次哈希结果取对应位数
        System.out.println("\n-- 非标准位数一致性 --");
        String full18 = totpRaw18("SHA1", secret, 59L);
        for (int digits = 1; digits <= 18; digits++) {
            String expected = full18.substring(18 - digits);
            checkEquals(digits + " 位等于基准值后 " + digits + " 位", expected, totp("SHA1", digits, 30, secret, 59L));
        }

        // 4. 位数不同，结果必须不同（防止写死逻辑回归）
        System.out.println("\n-- 位数敏感性 --");
        checkTrue("6 位与 7 位结果不同", !totp("SHA1", 6, 30, secret, 59L).equals(totp("SHA1", 7, 30, secret, 59L)));
        checkTrue("5 位与 6 位结果不同", !totp("SHA1", 5, 30, secret, 59L).equals(totp("SHA1", 6, 30, secret, 59L)));

        // 5. 三种算法 × 任意位数都不应抛异常
        System.out.println("\n-- 算法 × 位数 矩阵 --");
        boolean allOk = true;
        for (String algorithm : new String[]{"SHA1", "SHA256", "SHA512"}) {
            for (int digits = 1; digits <= 18; digits++) {
                try {
                    String code = totp(algorithm, digits, 30, secret, 1234567890L);
                    if (code.length() != digits || !code.matches("\\d+")) allOk = false;
                } catch (Exception exception) {
                    System.out.println("   [异常] " + algorithm + " " + digits + " 位: " + exception);
                    allOk = false;
                }
            }
        }
        checkTrue("3 算法 × 18 位数 = 54 组合全部正常", allOk);

        // 6. 边界：1 位与 18 位
        System.out.println("\n-- 边界值 --");
        check("最小 1 位", 1, totp("SHA1", 1, 30, secret, 0L).length());
        check("最大 18 位", 18, totp("SHA1", 18, 30, secret, 0L).length());

        System.out.println("\n结果：通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) System.exit(1);
    }

    /** 复制自 MainActivity 的新实现，用于独立验证。 */
    private static String totp(String algorithm, int digits, int period, String secret, long seconds)
            throws Exception {
        byte[] key = decodeBase32(secret);
        ByteBuffer counter = ByteBuffer.allocate(8).putLong(seconds / period);
        String macName = "Hmac" + algorithm;
        Mac mac = Mac.getInstance(macName);
        mac.init(new SecretKeySpec(key, macName));
        byte[] hash = mac.doFinal(counter.array());
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
        long modulus = powerOfTen(digits);
        long value = binary % modulus;
        return String.format(Locale.US, "%0" + digits + "d", value);
    }

    /** 取 18 位全量值作为对照基准。 */
    private static String totpRaw18(String algorithm, String secret, long seconds) throws Exception {
        byte[] key = decodeBase32(secret);
        ByteBuffer counter = ByteBuffer.allocate(8).putLong(seconds / 30);
        Mac mac = Mac.getInstance("Hmac" + algorithm);
        mac.init(new SecretKeySpec(key, "Hmac" + algorithm));
        byte[] hash = mac.doFinal(counter.array());
        int offset = hash[hash.length - 1] & 0x0f;
        long binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
        // 18 位是 long 能安全表示的 10^n 上限附近
        long scale = 1;
        for (int i = 0; i < 18; i++) scale *= 10L;
        return String.format(Locale.US, "%018d", binary % scale);
    }

    private static long powerOfTen(int digits) {
        long result = 1;
        for (int i = 0; i < digits; i++) result *= 10L;
        return result;
    }

    private static byte[] decodeBase32(String value) {
        String clean = value.toUpperCase(Locale.US).replaceAll("[^A-Z2-7]", "");
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

    private static void check(String name, int expected, int actual) {
        boolean ok = expected == actual;
        report(name, String.valueOf(expected), String.valueOf(actual), ok);
    }

    private static void checkEquals(String name, String expected, String actual) {
        report(name, expected, actual, expected.equals(actual));
    }

    private static void checkTrue(String name, boolean value) {
        report(name, "true", String.valueOf(value), value);
    }

    private static void report(String name, String expected, String actual, boolean ok) {
        System.out.printf("  %s %-46s 期望=%-20s 实际=%s%n",
                ok ? "OK  " : "FAIL", name, expected, actual);
        if (ok) passed++; else failed++;
    }
}
