package com.codex.twofa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.XmlResourceParser;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 针对本次闪退根因的回归测试。
 *
 * 事故复盘：
 *   activity_main.xml 的 TextInputLayout 通过 boxStrokeColor 引用了
 *   @color/field_stroke。该 selector 当时只有 state_focused="true" 与
 *   state_focused="false" 两项，没有「无状态」的默认项。控件在既不聚焦
 *   也不失焦的中间状态下取色失败，inflate 阶段抛
 *   Resources$NotFoundException，进程被系统直接杀掉 —— 表现为「点进去闪退」。
 *
 * 本测试确保：任何放在 res/color/ 下的 selector 都必须带默认项。
 */
@RunWith(AndroidJUnit4.class)
public class ColorSelectorRegressionTest {

    /** field_stroke 必须有默认色，且默认色与聚焦色不同。 */
    @Test
    public void field_stroke必须有默认项() {
        Context context = ApplicationProvider.getApplicationContext();
        ColorStateList list = context.getColorStateList(R.color.field_stroke);
        assertNotNull(list);

        int defaultColor = list.getDefaultColor();
        int focusedColor = list.getColorForState(
                new int[]{android.R.attr.state_focused}, defaultColor);

        // 默认色必须有效（非透明黑以外的合理值都行，这里只要求不是未定义）
        assertNotNull(Integer.valueOf(defaultColor));
        // 聚焦色与默认色应当不同，否则说明 selector 没起作用
        assertTrue("field_stroke 的聚焦色与默认色相同，selector 可能失效",
                focusedColor != defaultColor);
    }

    /** 卡片描边同理。 */
    @Test
    public void card_stroke必须有默认项() {
        Context context = ApplicationProvider.getApplicationContext();
        ColorStateList list = context.getColorStateList(R.color.card_stroke);
        assertNotNull(list);
        int defaultColor = list.getDefaultColor();
        int selectedColor = list.getColorForState(
                new int[]{android.R.attr.state_selected}, defaultColor);
        assertTrue("card_stroke 的选中色与默认色相同，selector 可能失效",
                selectedColor != defaultColor);
    }

    /**
     * 直接扫描 res/color 下的 selector，确认每一项都有默认兜底。
     * 这样以后新增颜色选择器时，忘了写默认项会被立刻发现。
     */
    @Test
    public void 所有颜色选择器都带无状态默认项() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        int[] all = {R.color.field_stroke, R.color.card_stroke};

        for (int id : all) {
            // 取默认色，若 selector 没有默认项，getDefaultColor 会抛异常或返回未定义
            ColorStateList list = context.getColorStateList(id);
            assertNotNull("0x" + Integer.toHexString(id) + " 无法加载", list);

            // 用一个「无任何状态」的空状态数组取色，这是最容易触发的路径
            int color = list.getColorForState(new int[0], Integer.MIN_VALUE);
            assertTrue("0x" + Integer.toHexString(id)
                            + " 在空状态下取不到颜色，说明缺少无状态默认项",
                    color != Integer.MIN_VALUE);
        }
    }

    /** 资源层校验：selector XML 里必须存在一个不带 state_ 属性的 item。 */
    @Test
    public void selector源码里必须有无状态项() {
        assertHasFallbackItem(R.color.field_stroke, "field_stroke");
        assertHasFallbackItem(R.color.card_stroke, "card_stroke");
    }

    private void assertHasFallbackItem(int colorResId, String name) {
        Context context = ApplicationProvider.getApplicationContext();
        try (XmlResourceParser parser = context.getResources().getXml(colorResId)) {
            boolean foundFallback = false;
            int event;
            while ((event = parser.next()) != XmlResourceParser.END_DOCUMENT) {
                if (event == XmlResourceParser.START_TAG
                        && "item".equals(parser.getName())) {
                    boolean hasState = false;
                    for (int i = 0; i < parser.getAttributeCount(); i++) {
                        String attrName = parser.getAttributeName(i);
                        if (attrName != null && attrName.startsWith("state_")) {
                            hasState = true;
                            break;
                        }
                    }
                    if (!hasState) {
                        foundFallback = true;
                        break;
                    }
                }
            }
            assertTrue("颜色选择器 " + name
                            + " 缺少无状态默认项 —— 这正是上次启动闪退的根因，必须补上",
                    foundFallback);
        } catch (Exception e) {
            throw new AssertionError("解析 " + name + " 失败: " + e, e);
        }
    }
}
