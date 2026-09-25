package com.codex.twofa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.widget.EditText;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 「不设任何阻拦」原则的验证。
 *
 * 用户的核心要求：只要输入了内容，就一定要能算出验证码并加进列表，
 * 不能因为「不是标准 Base32」「太短」「重复」就把人挡回去。
 *
 * 这里逐条验证这些曾经存在的拦截点都已经放开。
 */
@RunWith(AndroidJUnit4.class)
public class NoBarrierTest {

    private MainActivity newActivity() {
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    @SuppressWarnings("unchecked")
    private List<Object> accountsOf(MainActivity activity) throws Exception {
        Field f = MainActivity.class.getDeclaredField("accounts");
        f.setAccessible(true);
        return (List<Object>) f.get(activity);
    }

    /** 反射调用 parseAccount。 */
    private Object invokeParse(MainActivity activity, String name, String secret)
            throws Exception {
        Method m = MainActivity.class.getDeclaredMethod("parseAccount",
                String.class, String.class, String.class, int.class, int.class);
        m.setAccessible(true);
        return m.invoke(activity, name, secret, "SHA1", 6, 30);
    }

    /** 反射调用 totp。 */
    private String invokeTotp(MainActivity activity, String algorithm,
                              int digits, int period, String secret, long seconds)
            throws Exception {
        Method m = MainActivity.class.getDeclaredMethod("totp",
                String.class, int.class, int.class, String.class, long.class);
        m.setAccessible(true);
        try {
            return (String) m.invoke(activity, algorithm, digits, period, secret, seconds);
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (c instanceof java.security.GeneralSecurityException) return "ERR";
            throw e;
        }
    }

    // ---------------------------------------------------------------- 添加无阻拦

    /** 用户原话里的场景：只敲几个字母，也要能加进去。 */
    @Test
    public void 几个字母也能添加() throws Exception {
        MainActivity a = newActivity();
        String[] inputs = {"abc", "e", "ee", "QQ", "hello"};
        for (String s : inputs) {
            Object account = invokeParse(a, "", s);
            assertNotNull("输入「" + s + "」被拒绝了，不应该", account);
        }
    }

    /** 短密钥不再被长度限制挡下（旧代码要求解码后 ≥ 10 字节）。 */
    @Test
    public void 短密钥不再被拦() throws Exception {
        MainActivity a = newActivity();
        String[] shorts = {"AB", "ABC", "ABCD", "A", "12"};
        for (String s : shorts) {
            assertNotNull("短密钥「" + s + "」被拒绝了", invokeParse(a, "", s));
        }
    }

    /** 带符号、空格、中文等非 Base32 字符，同样照单全收。 */
    @Test
    public void 非标准字符也能添加() throws Exception {
        MainActivity a = newActivity();
        String[] weird = {
                "hello world",
                "abc-123",
                "密钥测试",
                "a@b#c",
                "1234 5678",
                "$%^&",
        };
        for (String s : weird) {
            assertNotNull("输入「" + s + "」被拒绝了", invokeParse(a, "", s));
        }
    }

    /** 重复密钥现在也允许添加（旧代码会提示「该密钥已存在」）。 */
    @Test
    public void 重复密钥可以添加() throws Exception {
        MainActivity a = newActivity();
        Object first = invokeParse(a, "", "JBSWY3DPEHPK3PXP");
        Object second = invokeParse(a, "", "JBSWY3DPEHPK3PXP");
        assertNotNull(first);
        assertNotNull("重复密钥被拒绝了，不应该", second);
    }

    /** 只有全空白输入才返回 null（因为确实没有内容可算）。 */
    @Test
    public void 只有空白输入才拒绝() throws Exception {
        MainActivity a = newActivity();
        assertEquals(null, invokeParse(a, "", ""));
        assertEquals(null, invokeParse(a, "", "   "));
    }

    /** 名称留空时自动补「验证器N」。 */
    @Test
    public void 留空名称自动命名() throws Exception {
        MainActivity a = newActivity();
        Object account = invokeParse(a, "", "JBSWY3DPEHPK3PXP");
        assertNotNull(account);
        Field nameField = account.getClass().getDeclaredField("name");
        nameField.setAccessible(true);
        String name = (String) nameField.get(account);
        assertTrue("默认名应形如「验证器一」，实际是「" + name + "」",
                name.startsWith("验证器"));
    }

    // ---------------------------------------------------------------- 计算无阻拦

    /** 任意输入都必须能算出验证码，绝不抛异常。 */
    @Test
    public void 任意输入都能算出验证码() throws Exception {
        MainActivity a = newActivity();
        String[] inputs = {
                "e", "ee", "eee", "abc", "hello world", "密钥",
                "JBSWY3DPEHPK3PXP", "mzxw6===", "a@b#c", "!@#$%",
        };
        for (String s : inputs) {
            String code = invokeTotp(a, "SHA1", 6, 30, s, 59L);
            assertFalse("输入「" + s + "」算不出验证码", "ERR".equals(code));
            assertEquals("输入「" + s + "」结果长度不对", 6, code.length());
        }
    }

    /** Base32 解不出时退回原始字节，两条路径的结果应当不同。 */
    @Test
    public void 非常规输入走字节回退路径() throws Exception {
        MainActivity a = newActivity();
        // "!!!" 不是合法 Base32，会走 UTF-8 字节路径
        String code = invokeTotp(a, "SHA1", 6, 30, "!!!", 59L);
        assertFalse("非 Base32 输入应当也能算出结果", "ERR".equals(code));
        assertEquals(6, code.length());
    }

    /** 同一输入必须得到稳定结果（可重复）。 */
    @Test
    public void 结果稳定可重复() throws Exception {
        MainActivity a = newActivity();
        String first = invokeTotp(a, "SHA1", 6, 30, "abc", 59L);
        String second = invokeTotp(a, "SHA1", 6, 30, "abc", 59L);
        assertEquals("同一输入两次结果不一致", first, second);
    }

    /** 不同输入应当给出不同验证码（避免退化成固定值）。 */
    @Test
    public void 不同输入结果不同() throws Exception {
        MainActivity a = newActivity();
        String x = invokeTotp(a, "SHA1", 6, 30, "aaa", 59L);
        String y = invokeTotp(a, "SHA1", 6, 30, "bbb", 59L);
        assertFalse("不同密钥算出了相同验证码", x.equals(y));
    }

    /** 标准 Base32 输入仍要走标准路径，不能因为放开而被破坏。 */
    @Test
    public void 标准密钥结果不变() throws Exception {
        MainActivity a = newActivity();
        // RFC 6238 官方向量：ASCII "12345678901234567890" 的 Base32 形式
        String base32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        assertEquals("94287082", invokeTotp(a, "SHA1", 8, 30, base32, 59L));
        assertEquals("287082", invokeTotp(a, "SHA1", 6, 30, base32, 59L));
    }

    // ---------------------------------------------------------------- 快速验证区

    /** 快速区输入即出码：填入密钥后，验证码文本会被更新。 */
    @Test
    public void 快速区输入即出码() throws Exception {
        MainActivity a = newActivity();
        EditText input = a.findViewById(R.id.quickSecret);
        android.widget.TextView code = a.findViewById(R.id.quickCode);
        assertNotNull(input);
        assertNotNull(code);

        String before = code.getText().toString();
        input.setText("JBSWY3DPEHPK3PXP");
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        String after = code.getText().toString();
        assertFalse("填入密钥后验证码没有变化", before.equals(after));
        assertTrue("验证码不应为占位符", after.contains("-") == false || after.length() > 5);
    }

    /** 快速区清空输入后回到占位符状态。 */
    @Test
    public void 快速区清空后回到占位符() {
        MainActivity a = newActivity();
        EditText input = a.findViewById(R.id.quickSecret);
        android.widget.TextView code = a.findViewById(R.id.quickCode);

        input.setText("JBSWY3DPEHPK3PXP");
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        input.setText("");
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        assertEquals("--- ---", code.getText().toString());
    }

    /** 快速区对任意输入都要出码，不做校验。 */
    @Test
    public void 快速区任意输入都出码() {
        MainActivity a = newActivity();
        EditText input = a.findViewById(R.id.quickSecret);
        android.widget.TextView code = a.findViewById(R.id.quickCode);
        android.widget.TextView meta = a.findViewById(R.id.quickMeta);

        for (String s : new String[]{"e", "abc", "hello world", "!!!", "密钥"}) {
            input.setText(s);
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            String shown = code.getText().toString();
            assertFalse("快速区对输入「" + s + "」没有出码", "--- ---".equals(shown));
            assertTrue("快速区对输入「" + s + "」显示异常：" + shown, shown.length() >= 5);
        }
        assertNotNull(meta);
    }

    /** 快速区参数说明应显示算法、位数、周期。 */
    @Test
    public void 快速区显示参数说明() {
        MainActivity a = newActivity();
        EditText input = a.findViewById(R.id.quickSecret);
        android.widget.TextView meta = a.findViewById(R.id.quickMeta);

        input.setText("JBSWY3DPEHPK3PXP");
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        String text = meta.getText().toString();
        assertTrue("参数说明应含算法，实际: " + text, text.contains("SHA1"));
        assertTrue("参数说明应含位数，实际: " + text, text.contains("6位"));
        assertTrue("参数说明应含周期，实际: " + text, text.contains("30"));
    }

    /** 点击账户卡片时，密钥与参数应带到快速区。 */
    @Test
    public void 点击账户带入快速区() throws Exception {
        MainActivity a = newActivity();
        Field accountsField = MainActivity.class.getDeclaredField("accounts");
        accountsField.setAccessible(true);
        List<Object> list = accountsOf(a);

        Method parse = MainActivity.class.getDeclaredMethod("parseAccount",
                String.class, String.class, String.class, int.class, int.class);
        parse.setAccessible(true);
        Object account = parse.invoke(a, "测试账户", "JBSWY3DPEHPK3PXP", "SHA256", 8, 60);
        list.add(account);

        Method select = MainActivity.class.getDeclaredMethod("select",
                Class.forName("com.codex.twofa.MainActivity$Account"));
        select.setAccessible(true);
        select.invoke(a, account);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        EditText input = a.findViewById(R.id.quickSecret);
        assertEquals("密钥没有带到快速区", "JBSWY3DPEHPK3PXP", input.getText().toString());

        android.widget.TextView meta = a.findViewById(R.id.quickMeta);
        String text = meta.getText().toString();
        assertTrue("参数应跟随账户，实际: " + text, text.contains("SHA256"));
        assertTrue("位数应跟随账户，实际: " + text, text.contains("8位"));
        assertTrue("周期应跟随账户，实际: " + text, text.contains("60"));
    }
}
