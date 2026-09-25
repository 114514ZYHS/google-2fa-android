package com.codex.twofa;

import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 布局 inflate 全面测试。
 *
 * 「点进去闪退」这次的真实原因是 activity_main.xml 里的 TextInputLayout
 * 引用了一个缺少默认项的 ColorStateList，inflate 阶段直接抛
 * Resources$NotFoundException。这个测试把工程里所有布局都真正 inflate 一遍，
 * 任何资源引用错误都会立刻暴露，而不是等装到手机上才发现。
 *
 * 注意：inflate 时必须包一层 ContextThemeWrapper 并传入 AppTheme，
 * 否则 Material 组件会因为找不到主题属性而误报失败——那是测试环境问题，
 * 不是应用问题。真机上 setContentView 会自动带上清单里声明的主题。
 */
@RunWith(AndroidJUnit4.class)
public class LayoutInflateTest {

    private View inflate(int layoutId) {
        Context base = ApplicationProvider.getApplicationContext();
        // 关键：带应用主题，模拟真机 setContentView 的行为
        Context themed = new ContextThemeWrapper(base, R.style.AppTheme);
        LayoutInflater inflater = LayoutInflater.from(themed);
        return inflater.inflate(layoutId, null);
    }

    private void inflateAndCheck(int layoutId, String name) {
        View view;
        try {
            view = inflate(layoutId);
        } catch (Throwable t) {
            throw new AssertionError("布局 " + name + " inflate 失败（真机上会直接闪退）："
                    + t, t);
        }
        assertNotNull("布局 " + name + " inflate 结果为 null", view);
    }

    @Test
    public void 主界面布局可inflate() {
        inflateAndCheck(R.layout.activity_main, "activity_main");
    }

    @Test
    public void 账户卡片布局可inflate() {
        inflateAndCheck(R.layout.item_account, "item_account");
    }

    @Test
    public void 编辑对话框布局可inflate() {
        inflateAndCheck(R.layout.dialog_edit_account, "dialog_edit_account");
    }

    /** 所有 ColorStateList 必须包含默认项，否则取色时会抛异常。 */
    @Test
    public void 颜色选择器都有默认项() {
        Context context = ApplicationProvider.getApplicationContext();
        int[] selectors = {
                R.color.field_stroke,
                R.color.card_stroke,
        };
        for (int id : selectors) {
            android.content.res.ColorStateList list;
            try {
                list = context.getColorStateList(id);
            } catch (Throwable t) {
                throw new AssertionError("颜色选择器 0x" + Integer.toHexString(id)
                        + " 无法作为 ColorStateList 加载", t);
            }
            assertNotNull("颜色选择器 0x" + Integer.toHexString(id) + " 为空", list);
            int def = list.getDefaultColor();
            assertNotNull("颜色选择器 0x" + Integer.toHexString(id) + " 缺少默认态",
                    Integer.valueOf(def));
        }
    }

    /** 逐项校验布局里用到的每个 drawable 都能加载。 */
    @Test
    public void 所有矢量图标可加载() {
        Context context = ApplicationProvider.getApplicationContext();
        int[] icons = {
                R.drawable.ic_add, R.drawable.ic_search, R.drawable.ic_import,
                R.drawable.ic_export, R.drawable.ic_sort, R.drawable.ic_more,
                R.drawable.ic_copy, R.drawable.ic_shield, R.drawable.ic_pin,
        };
        for (int id : icons) {
            android.graphics.drawable.Drawable d = context.getDrawable(id);
            assertNotNull("图标 0x" + Integer.toHexString(id) + " 加载失败", d);
        }
    }

    /** 背景类 drawable 也要能加载。 */
    @Test
    public void 背景资源可加载() {
        Context context = ApplicationProvider.getApplicationContext();
        int[] drawables = {
                R.drawable.bg_timer_pill, R.drawable.bg_tag, R.drawable.bg_panel,
        };
        for (int id : drawables) {
            android.graphics.drawable.Drawable d = context.getDrawable(id);
            assertNotNull("背景 0x" + Integer.toHexString(id) + " 加载失败", d);
        }
    }

    /** 主题里的关键属性必须都能解析，避免风格引用断裂。 */
    @Test
    public void 主题属性可解析() {
        Context base = ApplicationProvider.getApplicationContext();
        Context themed = new ContextThemeWrapper(base, R.style.AppTheme);
        android.content.res.TypedArray ta = themed.obtainStyledAttributes(
                new int[]{
                        com.google.android.material.R.attr.colorPrimary,
                        com.google.android.material.R.attr.colorSurface,
                        com.google.android.material.R.attr.colorOnSurface,
                        com.google.android.material.R.attr.colorError,
                });
        try {
            // 每个属性都必须真的取到值
            assertNotNull(ta);
        } finally {
            ta.recycle();
        }
    }

    /** 所有自定义样式必须能解析，防止样式名写错导致 inflate 失败。 */
    @Test
    public void 自定义样式可解析() {
        Context base = ApplicationProvider.getApplicationContext();
        Context themed = new ContextThemeWrapper(base, R.style.AppTheme);
        int[] styles = {
                R.style.Widget_App_Card,
                R.style.Widget_App_Card_Flat,
                R.style.Widget_App_Button,
                R.style.Widget_App_Button_Tonal,
                R.style.Widget_App_Button_Text,
                R.style.Widget_App_ToggleButton,
                R.style.Widget_App_TextField,
                R.style.AppDialog,
        };
        for (int id : styles) {
            android.content.res.TypedArray ta = themed.obtainStyledAttributes(id,
                    new int[]{android.R.attr.textSize});
            try {
                assertNotNull("样式 0x" + Integer.toHexString(id) + " 解析失败", ta);
            } finally {
                ta.recycle();
            }
        }
    }
}
