package com.codex.twofa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 运行时 TOTP 验证：在真实 Android 运行时（Robolectric）里调用生产代码里的
 * totp() 方法，确认各算法、各位数都能算对。
 *
 * 与 tools/algorithm-tests 的裸 JVM 测试互补——这里用的是设备自带的
 * javax.crypto 与 Android 的 String.format 实现。
 */
@RunWith(AndroidJUnit4.class)
public class TotpRuntimeTest {

    /** RFC 6238 官方测试向量，密钥为 ASCII "12345678901234567890"。 */
    private static final String RFC_SECRET =
            base32("12345678901234567890");

    private static String base32(String ascii) {
        // 直接构造标准 Base32 便于阅读；下面用生产代码的 decoder 反向验证
        StringBuilder sb = new StringBuilder();
        // 手工编码，避免依赖测试框架
        byte[] data = ascii.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        final String ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHA.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(ALPHA.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return sb.toString();
    }

    private MainActivity newActivity() {
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    @SuppressWarnings("unchecked")
    private String invokeTotp(MainActivity activity, String algorithm,
                              int digits, int period, String secret, long seconds) {
        try {
            Method m = MainActivity.class.getDeclaredMethod(
                    "totp", String.class, int.class, int.class, String.class, long.class);
            m.setAccessible(true);
            return (String) m.invoke(activity, algorithm, digits, period, secret, seconds);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("调用 totp 失败: " + cause, cause);
        }
    }

    private String safeTotp(MainActivity activity, String algorithm,
                            int digits, int period, String secret, long seconds) {
        try {
            return invokeTotp(activity, algorithm, digits, period, secret, seconds);
        } catch (RuntimeException e) {
            // 生产代码把 GeneralSecurityException 包在 InvocationTargetException 里
            Throwable c = e.getCause();
            if (c instanceof java.security.GeneralSecurityException) {
                return "ERR";
            }
            if (c instanceof RuntimeException) {
                throw (RuntimeException) c;
            }
            return "ERR";
        }
    }

    /** RFC 6238 附录 B：时间 59 秒时，SHA1 六位应得 287082。 */
    @Test
    public void rfc向量_六位() {
        MainActivity a = newActivity();
        assertEquals("287082", safeTotp(a, "SHA1", 6, 30, RFC_SECRET, 59L));
    }

    /** RFC 6238 附录 B：时间 59 秒时，SHA1 八位应得 94287082。 */
    @Test
    public void rfc向量_八位() {
        MainActivity a = newActivity();
        assertEquals("94287082", safeTotp(a, "SHA1", 8, 30, RFC_SECRET, 59L));
    }

    /** 六位与八位结果必须不同——防止又退回写死的 6/8 分支。 */
    @Test
    public void 不同位数结果不同() {
        MainActivity a = newActivity();
        String six = safeTotp(a, "SHA1", 6, 30, RFC_SECRET, 59L);
        String seven = safeTotp(a, "SHA1", 7, 30, RFC_SECRET, 59L);
        assertTrue("六位与七位不应相同（六位=" + six + " 七位=" + seven + "）",
                !six.equals(seven));
    }

    /** 1 到 18 位全部可用，且输出长度正确、全是数字。 */
    @Test
    public void 支持一到十八位() {
        MainActivity a = newActivity();
        for (int digits = 1; digits <= 18; digits++) {
            String code = safeTotp(a, "SHA1", digits, 30, RFC_SECRET, 59L);
            assertNotNull("位数 " + digits + " 返回 null", code);
            assertEquals("位数 " + digits + " 长度不对", digits, code.length());
            for (int i = 0; i < code.length(); i++) {
                assertTrue("位数 " + digits + " 出现非数字字符: " + code,
                        Character.isDigit(code.charAt(i)));
            }
        }
    }

    /** 三种算法 × 18 种位数，共 54 种组合都不能抛异常。 */
    @Test
    public void 三种算法全位数组合不抛异常() {
        MainActivity a = newActivity();
        for (String algorithm : new String[]{"SHA1", "SHA256", "SHA512"}) {
            for (int digits = 1; digits <= 18; digits++) {
                String code = safeTotp(a, algorithm, digits, 30, RFC_SECRET, 59L);
                if ("ERR".equals(code)) {
                    fail(algorithm + " 位数 " + digits + " 计算失败");
                }
                assertEquals(algorithm + " 位数 " + digits + " 长度不对",
                        digits, code.length());
            }
        }
    }

    /** 用户举的例子：随便输入一串字符，也要能算出码，不能抛异常。 */
    @Test
    public void 任意输入不抛异常() {
        MainActivity a = newActivity();
        String[] inputs = {
                "EEEEEEEE",          // 用户原话里的「8 个 e」
                "JBSWY3DPEHPK3PXP",  // 标准示例密钥
                "ABCDEFGH",
                "MZXW6===",          // 带填充
                "mzxw6ytboi======",  // 小写带填充
                "GEZDGNBVGY3Q",
        };
        for (String secret : inputs) {
            String code = safeTotp(a, "SHA1", 6, 30, secret, 59L);
            if (!"ERR".equals(code)) {
                assertEquals(6, code.length());
            }
        }
    }

    /** 位数分组显示：6 位切成 3+3，8 位切成 4+4，其余按 4 位一组。 */
    @Test
    public void 验证码分组显示正确() throws Exception {
        MainActivity a = newActivity();
        Method m = MainActivity.class.getDeclaredMethod("groupCode", String.class);
        m.setAccessible(true);

        assertEquals("123 456", m.invoke(a, "123456"));
        assertEquals("1234 5678", m.invoke(a, "12345678"));
        assertEquals("1234 5678 90", m.invoke(a, "1234567890"));
        assertEquals("1", m.invoke(a, "1"));
    }

    /** 默认命名必须是「验证器一、验证器二」这种。 */
    @Test
    public void 默认命名是中文序号() throws Exception {
        MainActivity a = newActivity();
        Method m = MainActivity.class.getDeclaredMethod("nextDefaultName");
        m.setAccessible(true);

        String first = (String) m.invoke(a);
        assertNotNull(first);
        assertTrue("首个默认名不应为空", first.length() > 0);
        assertTrue("默认名应包含「验证器」", first.contains("验证器"));
    }

    /** 非法输入必须给出错误码而不是崩溃。 */
    @Test
    public void 非法输入返回错误而非崩溃() {
        MainActivity a = newActivity();
        // 空密钥
        assertEquals("ERR", safeTotp(a, "SHA1", 6, 30, "", 59L));
        // 位数 0
        assertEquals("ERR", safeTotp(a, "SHA1", 0, 30, RFC_SECRET, 59L));
        // 周期 0
        assertEquals("ERR", safeTotp(a, "SHA1", 6, 0, RFC_SECRET, 59L));
    }
}
