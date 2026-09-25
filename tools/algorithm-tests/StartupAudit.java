import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * 启动路径静态审计。
 *
 * 闪退绝大多数来自「代码引用了 XML 里不存在的控件」或「资源 id 不匹配」。
 * 这里直接解包 APK，交叉比对：
 *   1. 代码里 R.id.xxx 引用的每个 id，是否在资源表里真实存在
 *   2. 每个布局文件引用的 @color / @drawable / @style，是否都能解析
 * 任何一项对不上，启动时就会抛异常。
 */
public class StartupAudit {

    static int problems = 0;

    public static void main(String[] args) throws Exception {
        String apk = args[0];
        System.out.println("== 启动路径审计 ==");
        System.out.println("APK: " + apk);
        System.out.println();

        // 用 aapt2 导出资源表
        String aapt2 = "/opt/android-sdk/build-tools/35.0.0/aapt2";
        String resources = run(aapt2, "dump", "resources", apk);
        String badging = run(aapt2, "dump", "badging", apk);

        Set<String> declaredIds = new HashSet<>();
        Set<String> declaredColors = new HashSet<>();
        Set<String> declaredDrawables = new HashSet<>();
        Set<String> declaredStyles = new HashSet<>();
        Set<String> declaredLayouts = new HashSet<>();
        Set<String> declaredStrings = new HashSet<>();
        Set<String> declaredDimens = new HashSet<>();
        Set<String> declaredMipmaps = new HashSet<>();

        BufferedReader reader = new BufferedReader(new StringReader(resources));
        String line;
        // aapt2 输出形如：  resource 0x7f0800c4 id/accountList
        // 注意不带包名前缀，直接是 type/name
        java.util.regex.Pattern row = java.util.regex.Pattern
                .compile("^\\s*resource\\s+0x[0-9a-f]+\\s+([a-z0-9_]+)/(\\S+)\\s*$");
        while ((line = reader.readLine()) != null) {
            java.util.regex.Matcher m = row.matcher(line);
            if (!m.find()) continue;
            String type = m.group(1);
            String name = m.group(2);
            switch (type) {
                case "id": declaredIds.add(name); break;
                case "color": declaredColors.add(name); break;
                case "drawable": declaredDrawables.add(name); break;
                case "style": declaredStyles.add(name); break;
                case "layout": declaredLayouts.add(name); break;
                case "string": declaredStrings.add(name); break;
                case "dimen": declaredDimens.add(name); break;
                case "mipmap": declaredMipmaps.add(name); break;
                default: break;
            }
        }

        System.out.println("资源表统计：");
        System.out.println("  id     : " + declaredIds.size());
        System.out.println("  color  : " + declaredColors.size());
        System.out.println("  drawable: " + declaredDrawables.size());
        System.out.println("  style  : " + declaredStyles.size());
        System.out.println("  layout : " + declaredLayouts.size());
        System.out.println("  string : " + declaredStrings.size());
        System.out.println("  dimen  : " + declaredDimens.size());
        System.out.println("  mipmap : " + declaredMipmaps.size());
        System.out.println();

        // 1) 图标必须存在，否则桌面显示默认机器人
        System.out.println("-- 图标检查 --");
        check("存在 ic_launcher", declaredMipmaps.contains("ic_launcher"));
        check("存在 ic_launcher_round", declaredMipmaps.contains("ic_launcher_round"));
        check("清单声明了图标", badging.contains("application:") && badging.contains("icon="));
        System.out.println();

        // 2) 三个布局必须都在
        System.out.println("-- 布局检查 --");
        for (String l : new String[]{"activity_main", "item_account", "dialog_edit_account"}) {
            check("布局 " + l, declaredLayouts.contains(l));
        }
        System.out.println();

        // 3) 代码引用的 id 必须都在资源表里
        System.out.println("-- 控件 id 引用检查 --");
        String code = readFile("/workspace/google-2fa-android/app/src/main/java/com/codex/twofa/MainActivity.java");
        Set<String> codeIds = new TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("R\\.id\\.(\\w+)").matcher(code);
        while (m.find()) codeIds.add(m.group(1));
        for (String id : codeIds) {
            if (!declaredIds.contains(id)) {
                System.out.println("  FAIL 代码引用 R.id." + id + " 但资源表中不存在");
                problems++;
            }
        }
        System.out.println("  共检查 " + codeIds.size() + " 个 id 引用");
        System.out.println();

        // 4) 布局里引用的 color / drawable / dimen 必须存在
        System.out.println("-- 布局资源引用检查 --");
        String[] layouts = {"activity_main", "item_account", "dialog_edit_account"};
        for (String l : layouts) {
            String xml = readLayout(l);
            checkRefs(xml, "@color/", declaredColors, l + " 颜色");
            checkRefs(xml, "@drawable/", declaredDrawables, l + " 图形");
            checkRefs(xml, "@dimen/", declaredDimens, l + " 尺寸");
            checkRefs(xml, "@style/", declaredStyles, l + " 样式");
            checkRefs(xml, "@string/", declaredStrings, l + " 文案");
        }
        System.out.println();

        // 5) 主题必须能解析
        System.out.println("-- 主题检查 --");
        check("AppTheme 已定义", declaredStyles.contains("AppTheme"));
        check("无高版本属性泄漏到基础主题", !readFile(
                "/workspace/google-2fa-android/app/src/main/res/values/styles.xml")
                .contains("windowLightNavigationBar"));
        System.out.println();

        System.out.println(problems == 0
                ? "审计通过：未发现会导致启动崩溃的问题"
                : "审计发现 " + problems + " 个问题，需修复");
        if (problems > 0) System.exit(1);
    }

    static void checkRefs(String xml, String prefix, Set<String> declared, String label) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(java.util.regex.Pattern.quote(prefix) + "([\\w.]+)").matcher(xml);
        Set<String> seen = new TreeSet<>();
        while (m.find()) seen.add(m.group(1));
        for (String name : seen) {
            if (!declared.contains(name)) {
                System.out.println("  FAIL " + label + " 引用 " + prefix + name + " 未定义");
                problems++;
            }
        }
    }

    static String readFile(String path) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(path), "UTF-8"))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    static String readLayout(String name) throws Exception {
        return readFile("/workspace/google-2fa-android/app/src/main/res/layout/" + name + ".xml");
    }

    static String run(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        p.waitFor();
        return sb.toString();
    }

    static void check(String name, boolean ok) {
        System.out.printf("  %s %s%n", ok ? "OK  " : "FAIL", name);
        if (!ok) problems++;
    }
}
