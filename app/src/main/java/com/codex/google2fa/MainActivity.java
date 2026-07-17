package com.codex.google2fa;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends android.app.Activity {
    private static final String PREFS = "accounts";
    private static final String KEY_ACCOUNTS = "items";
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private final ArrayList<Account> accounts = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout accountList;
    private TextView timerText;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refreshCodes();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
        loadAccounts();
        renderAccounts();
    }

    @Override protected void onResume() { super.onResume(); handler.post(ticker); }
    @Override protected void onPause() { handler.removeCallbacks(ticker); super.onPause(); }

    private void buildScreen() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(12), pad, pad);
        root.setBackgroundColor(color("app_surface"));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Google 2FA", 22, color("app_ink"), Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1));
        timerText = text("30", 16, color("app_muted"), Typeface.BOLD);
        timerText.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        header.addView(timerText, new LinearLayout.LayoutParams(dp(48), dp(56)));
        root.addView(header);

        Button add = new Button(this);
        add.setText("Add account");
        add.setAllCaps(false);
        add.setContentDescription("Add account");
        add.setOnClickListener(view -> showAddDialog());
        root.addView(add, new LinearLayout.LayoutParams(-1, dp(52)));

        ScrollView scroll = new ScrollView(this);
        accountList = new LinearLayout(this);
        accountList.setOrientation(LinearLayout.VERTICAL);
        accountList.setPadding(0, dp(16), 0, 0);
        scroll.addView(accountList);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void showAddDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), 0, dp(24), 0);
        EditText name = new EditText(this);
        name.setHint("Account name");
        name.setSingleLine();
        form.addView(name);
        EditText secret = new EditText(this);
        secret.setHint("Secret key");
        secret.setSingleLine();
        secret.setInputType(InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        form.addView(secret);
        new AlertDialog.Builder(this)
            .setTitle("Add account")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add", (dialog, which) -> addAccount(name.getText().toString(), secret.getText().toString()))
            .show();
    }

    private void addAccount(String name, String secret) {
        String clean = normalize(secret);
        if (name.trim().isEmpty() || clean.isEmpty() || decodeBase32(clean).length < 10) {
            Toast.makeText(this, "Enter a valid name and secret key", Toast.LENGTH_SHORT).show();
            return;
        }
        accounts.add(new Account(UUID.randomUUID().toString(), name.trim(), clean));
        saveAccounts();
        renderAccounts();
    }

    private void renderAccounts() {
        accountList.removeAllViews();
        if (accounts.isEmpty()) {
            TextView empty = text("No accounts", 16, color("app_muted"), Typeface.NORMAL);
            empty.setGravity(Gravity.CENTER);
            accountList.addView(empty, new LinearLayout.LayoutParams(-1, dp(96)));
            return;
        }
        for (Account account : accounts) accountList.addView(accountRow(account));
        refreshCodes();
    }

    private View accountRow(Account account) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(14), dp(12), dp(12));
        row.setBackground(roundRect(Color.WHITE, color("app_line")));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.setMargins(0, 0, 0, dp(12));
        row.setLayoutParams(rowParams);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(account.name, 16, color("app_ink"), Typeface.BOLD);
        top.addView(name, new LinearLayout.LayoutParams(0, dp(32), 1));
        Button remove = new Button(this);
        remove.setText("Delete");
        remove.setAllCaps(false);
        remove.setContentDescription("Delete " + account.name);
        remove.setOnClickListener(view -> confirmDelete(account));
        top.addView(remove, new LinearLayout.LayoutParams(dp(84), dp(44)));
        row.addView(top);

        TextView code = text("------", 36, color("app_ink"), Typeface.MONOSPACE);
        code.setTag(account.id);
        code.setGravity(Gravity.CENTER_VERTICAL);
        code.setContentDescription("Copy code for " + account.name);
        code.setPadding(0, dp(6), 0, dp(6));
        code.setOnClickListener(view -> copyCode(((TextView) view).getText().toString()));
        row.addView(code, new LinearLayout.LayoutParams(-1, dp(60)));

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setTag("progress-" + account.id);
        progress.setMax(30);
        row.addView(progress, new LinearLayout.LayoutParams(-1, dp(8)));
        return row;
    }

    private void refreshCodes() {
        long now = System.currentTimeMillis() / 1000L;
        int remaining = (int) (30 - (now % 30));
        timerText.setText(String.valueOf(remaining));
        for (Account account : accounts) {
            TextView code = findByTag(accountList, account.id, TextView.class);
            ProgressBar progress = findByTag(accountList, "progress-" + account.id, ProgressBar.class);
            if (code != null) {
                try { code.setText(totp(account.secret, now)); }
                catch (GeneralSecurityException exception) { code.setText("------"); }
            }
            if (progress != null) progress.setProgress(remaining);
        }
    }

    private void confirmDelete(Account account) {
        new AlertDialog.Builder(this).setMessage("Delete " + account.name + "?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (dialog, which) -> {
                accounts.remove(account); saveAccounts(); renderAccounts();
            }).show();
    }

    private void copyCode(String code) {
        if (code.equals("------")) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("2FA code", code));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    private void loadAccounts() {
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ACCOUNTS, "[]");
        try {
            JSONArray list = new JSONArray(raw);
            for (int index = 0; index < list.length(); index++) {
                JSONObject item = list.getJSONObject(index);
                accounts.add(new Account(item.getString("id"), item.getString("name"), item.getString("secret")));
            }
        } catch (Exception ignored) { }
    }

    private void saveAccounts() {
        JSONArray list = new JSONArray();
        for (Account account : accounts) {
            JSONObject item = new JSONObject();
            try { item.put("id", account.id); item.put("name", account.name); item.put("secret", account.secret); list.put(item); }
            catch (Exception ignored) { }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ACCOUNTS, list.toString()).apply();
    }

    private String totp(String secret, long seconds) throws GeneralSecurityException {
        byte[] key = decodeBase32(secret);
        ByteBuffer counter = ByteBuffer.allocate(8).putLong(seconds / 30);
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(counter.array());
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
        return String.format(Locale.US, "%06d", binary % 1_000_000);
    }

    private byte[] decodeBase32(String value) {
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

    private String normalize(String value) { return value.toUpperCase(Locale.US).replaceAll("[^A-Z2-7]", ""); }
    private TextView text(String value, int size, int tint, int face) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(tint); view.setTypeface(null, face); return view; }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private int color(String name) { return getResources().getColor(getResources().getIdentifier(name, "color", getPackageName())); }
    private GradientDrawable roundRect(int fill, int stroke) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(6)); drawable.setStroke(dp(1), stroke); return drawable; }
    @SuppressWarnings("unchecked") private <T extends View> T findByTag(View root, Object tag, Class<T> type) { if (tag.equals(root.getTag()) && type.isInstance(root)) return (T) root; if (root instanceof android.view.ViewGroup) { android.view.ViewGroup group = (android.view.ViewGroup) root; for (int index = 0; index < group.getChildCount(); index++) { T found = findByTag(group.getChildAt(index), tag, type); if (found != null) return found; } } return null; }

    private static class Account { final String id, name, secret; Account(String id, String name, String secret) { this.id = id; this.name = name; this.secret = secret; } }
}
