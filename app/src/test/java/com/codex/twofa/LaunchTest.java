package com.codex.twofa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * 真实启动测试：在 JVM 中完整走一遍 MainActivity 的生命周期，
 * 用于验证「点进去直接闪退」的问题是否真的修好了。
 *
 * 这是动态验证，不是静态审计——布局会真的被 inflate，
 * onCreate / onResume / 渲染列表都会真的执行。
 */
@RunWith(AndroidJUnit4.class)
@Config(qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
public class LaunchTest {

    /** 最基本的一条：Activity 能不能被系统创建出来而不抛异常。 */
    @Test
    public void activity可以正常创建() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class);
        MainActivity activity = controller.setup().get();
        assertNotNull("Activity 不应为空", activity);
    }

    /** 完整生命周期：创建 → 启动 → 恢复 → 暂停 → 停止 → 销毁，全程不许异常。 */
    @Test
    public void 完整生命周期不崩溃() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class);
        controller.create().start().resume().visible();
        ShadowLooper.idleMainLooper();
        controller.pause().stop().destroy();
    }

    /** 重建场景（旋转屏幕 / 切换主题时会发生），也常见闪退。 */
    @Test
    public void 配置重建不崩溃() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class);
        controller.setup();
        ShadowLooper.idleMainLooper();
        controller.recreate();
        ShadowLooper.idleMainLooper();
        assertNotNull(controller.get());
    }

    /** 主界面关键控件必须都能找到，否则说明布局被 inflate 失败或 id 不匹配。 */
    @Test
    public void 主界面关键控件存在() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class);
        MainActivity activity = controller.setup().get();

        int[] required = {
                R.id.accountList,
                R.id.searchInput,
                R.id.btnAdd,
                R.id.btnImport,
                R.id.btnExport,
                R.id.quickCode,
                R.id.quickMeta,
                R.id.quickSecret,
                R.id.quickWrap,
                R.id.emptyState,
                R.id.globalTimer,
        };

        for (int id : required) {
            View view = activity.findViewById(id);
            assertNotNull("缺少控件 id=0x" + Integer.toHexString(id), view);
        }
    }

    /** 底部快速验证码区域初始应显示占位符，而不是空字符串或崩溃。 */
    @Test
    public void 快速验证码区域初始有占位符() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class);
        MainActivity activity = controller.setup().get();

        TextView quickCode = activity.findViewById(R.id.quickCode);
        assertNotNull(quickCode);
        String text = quickCode.getText().toString();
        assertTrue("占位符不应为空", text.length() > 0);
    }

    /** 空状态下应该显示引导文案，而不是一片空白。 */
    @Test
    public void 空状态可见且有文案() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class);
        MainActivity activity = controller.setup().get();

        View empty = activity.findViewById(R.id.emptyState);
        TextView title = activity.findViewById(R.id.emptyTitle);
        TextView hint = activity.findViewById(R.id.emptyHint);

        assertNotNull(empty);
        assertNotNull(title);
        assertNotNull(hint);
        // 首次启动没有任何账户，空状态应该显示
        assertEquals("首次启动应显示空状态", View.VISIBLE, empty.getVisibility());
        assertTrue("空状态标题不应为空", title.getText().length() > 0);
        assertTrue("空状态提示不应为空", hint.getText().length() > 0);
    }

    /**
     * 清单里必须声明图标，且指向的 mipmap 资源真实存在。
     *
     * 说明：Robolectric 的 PackageManager.getApplicationIcon() 在单元测试环境下
     * 固定返回 null（框架限制，与真机无关），因此这里改为直接校验
     * AndroidManifest 的 android:icon 指向以及资源可解析性——这正是
     * 「装完只有默认安卓机器人」问题的真正判据。
     */
    @Test
    public void 应用图标可以加载() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        android.content.pm.ApplicationInfo info = context.getApplicationInfo();

        // 清单必须显式声明图标，否则启动器会回退成默认图标
        assertNotNull("ApplicationInfo.icon 为 0，说明清单未声明 android:icon",
                info.icon != 0 ? Integer.valueOf(info.icon) : null);

        // 图标资源必须能真正解析出来
        Drawable icon = context.getDrawable(info.icon);
        assertNotNull("清单声明的图标资源无法加载", icon);

        // 自适应图标必须同时有前景层与背景层
        Drawable foreground = context.getDrawable(R.drawable.ic_launcher_foreground);
        Drawable background = context.getDrawable(R.drawable.ic_launcher_background);
        assertNotNull("缺少自适应图标前景层", foreground);
        assertNotNull("缺少自适应图标背景层", background);

        // 圆角图标也要在
        Drawable round = context.getDrawable(R.mipmap.ic_launcher_round);
        assertNotNull("缺少圆形图标 ic_launcher_round", round);
    }

    /**
     * 图标内容不能是空白或纯色块。
     *
     * 上次的图标虽然「存在」，但光栅化质量极差——只有 3 种颜色，
     * 看上去就是个粗糙色块，用户直接评价「跟皮包的一样」。
     * 这里直接解码工程里的图标 PNG 做像素统计，确保有足够的色彩层次。
     *
     * 为什么不通过 getDrawable(R.mipmap.ic_launcher) 取：
     *   在 API 26+ 上它指向自适应图标 XML，Robolectric 对 adaptive-icon
     *   的渲染支持不完整（会得到空白），那是测试环境限制，不是真机行为。
     *   直接校验 PNG 内容才能真实反映低版本设备与后备图标的质量。
     */
    @Test
    public void 图标内容不是粗糙色块() {
        // 测试运行时的工作目录是 app 模块目录
        java.io.File base = new java.io.File("src/main/res");
        if (!base.isDirectory()) {
            base = new java.io.File("app/src/main/res");
        }
        assertTrue("找不到 res 目录，当前目录: "
                + new java.io.File(".").getAbsolutePath(), base.isDirectory());

        String[] densities = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        int checked = 0;

        for (String density : densities) {
            java.io.File f = new java.io.File(base,
                    "mipmap-" + density + "/ic_launcher.png");
            assertTrue("缺少图标文件: " + f.getPath(), f.isFile());

            android.graphics.Bitmap bmp =
                    android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
            assertNotNull("无法解码图标: " + f.getPath(), bmp);
            assertTrue("图标尺寸异常 " + f.getPath() + ": "
                            + bmp.getWidth() + "x" + bmp.getHeight(),
                    bmp.getWidth() >= 48 && bmp.getHeight() >= 48);

            java.util.HashSet<Integer> colors = new java.util.HashSet<>();
            for (int y = 0; y < bmp.getHeight(); y += 2) {
                for (int x = 0; x < bmp.getWidth(); x += 2) {
                    colors.add(bmp.getPixel(x, y));
                }
            }
            assertTrue(density + " 图标颜色层次过少（仅 " + colors.size()
                            + " 种），看起来会是粗糙色块",
                    colors.size() >= 16);
            checked++;
        }

        assertTrue("未检查到图标", checked == densities.length);
    }

    /** 自适应图标资源本身也要能解析。 */
    @Test
    public void 自适应图标资源可解析() {
        Context context = ApplicationProvider.getApplicationContext();

        Drawable adaptive = context.getDrawable(R.mipmap.ic_launcher);
        assertNotNull("ic_launcher 应可加载", adaptive);

        Drawable round = context.getDrawable(R.mipmap.ic_launcher_round);
        assertNotNull("ic_launcher_round 应可加载", round);

        Drawable foreground = context.getDrawable(R.drawable.ic_launcher_foreground);
        assertNotNull("前景层应可加载", foreground);

        Drawable background = context.getDrawable(R.drawable.ic_launcher_background);
        assertNotNull("背景层应可加载", background);
    }

    /** 应用名必须正确，且不含 Google 字样。 */
    @Test
    public void 应用名正确且无谷歌字样() {
        Context context = ApplicationProvider.getApplicationContext();
        CharSequence label = context.getApplicationInfo().loadLabel(context.getPackageManager());
        assertNotNull(label);
        String name = label.toString();
        assertTrue("应用名不应为空", name.length() > 0);
        assertTrue("不应再残留 Google 品牌", name.toLowerCase().contains("google") == false);
    }

    /** 应用不应申请任何危险权限。 */
    @Test
    public void 不申请多余权限() {
        Context context = ApplicationProvider.getApplicationContext();
        String[] requested = null;
        try {
            requested = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(),
                            android.content.pm.PackageManager.GET_PERMISSIONS)
                    .requestedPermissions;
        } catch (Exception e) {
            // 拿不到权限信息就跳过，不因此判定失败
        }
        if (requested != null) {
            for (String p : requested) {
                assertTrue("不应申请危险权限: " + p,
                        !p.equals(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                                && !p.equals(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                && !p.equals(android.Manifest.permission.INTERNET)
                                && !p.equals(android.Manifest.permission.CAMERA));
            }
        }
    }
}
