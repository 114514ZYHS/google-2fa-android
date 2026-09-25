package com.codex.twofa;

import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;

/**
 * 诊断用：把 showFatal() 捕获到的真实异常打印出来。
 * 如果 onCreate 一切正常，这个测试也会通过；一旦失败，就会显示根因。
 */
@RunWith(AndroidJUnit4.class)
public class DiagnoseTest {

    private void dumpFatal(MainActivity activity) {
        View root = activity.findViewById(android.R.id.content);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof android.widget.ScrollView) {
                    android.widget.ScrollView sv = (android.widget.ScrollView) child;
                    if (sv.getChildCount() > 0 && sv.getChildAt(0) instanceof TextView) {
                        String text = ((TextView) sv.getChildAt(0)).getText().toString();
                        if (text.startsWith("启动失败")) {
                            throw new AssertionError("应用初始化抛出了异常：\n\n" + text);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void 打印初始化真实异常() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class);
        controller.create();
        MainActivity activity = controller.get();
        assertNotNull(activity);
        dumpFatal(activity);
    }

    @Test
    public void 打印完整启动后的真实异常() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class);
        MainActivity activity = controller.setup().get();
        assertNotNull(activity);
        dumpFatal(activity);

        // 顺带确认几个关键控件是否真的绑上了
        int[] ids = {R.id.accountList, R.id.searchInput, R.id.btnAdd, R.id.quickCode};
        StringBuilder missing = new StringBuilder();
        for (int id : ids) {
            if (activity.findViewById(id) == null) {
                missing.append("0x").append(Integer.toHexString(id)).append(" ");
            }
        }
        if (missing.length() > 0) {
            throw new AssertionError("以下控件未找到: " + missing);
        }
    }
}
