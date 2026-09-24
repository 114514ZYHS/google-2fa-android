package com.codex.google2fa;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends android.app.Activity {
    private static final String PREFS = "accounts";
    private static final String KEY_ACCOUNTS = "items";
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String[] ALGORITHMS = {"SHA1", "SHA256", "SHA512"};
    private static final int REQUEST_IMPORT_FILE = 4001;
    private static final int REQUEST_EXPORT_FILE = 4002;

    private final ArrayList<Account> accounts = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout accountList;
    private TextView timerText;
    private String query = "";

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

    // ---------------------------------------------------------------- 界面构建

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
        timerText = text("30s", 16, color("app_muted"), Typeface.BOLD);
        timerText.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        header.addView(timerText, new LinearLayout.LayoutParams(dp(56), dp(56)));
        root.addView(header);

        EditText searchField = new EditText(this);
        searchField.setHint("搜索账户或发行方");
        searchField.setSingleLine();
        searchField.setTextSize(15);
        searchField.setInputType(InputType.TYPE_CLASS_TEXT);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.US);
                renderAccounts();
            }
        });
        root.addView(searchField, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        actions.addView(actionButton("添加", "添加账户", view -> showAddDialog()), weight());
        actions.addView(actionButton("导入", "导入账户", view -> showImportDialog()), weight());
        actions.addView(actionButton("导出", "导出账户", view -> showExportDialog()), weight());
        root.addView(actions);

        ScrollView scroll = new ScrollView(this);
        accountList = new LinearLayout(this);
        accountList.setOrientation(LinearLayout.VERTICAL);
        accountList.setPadding(0, dp(16), 0, 0);
        scroll.addView(accountList);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private Button actionButton(String label, String description, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setContentDescription(description);
        button.setOnClickListener(listener);
        return button;
    }

    // ---------------------------------------------------------------- 新增账户

    private void showAddDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), 0, dp(24), 0);
        EditText name = new EditText(this);
        name.setHint("账户名称");
        name.setSingleLine();
        form.addView(name);
        EditText secret = new EditText(this);
        secret.setHint("密钥（Base32）或 otpauth:// 链接");
        secret.setSingleLine();
        secret.setInputType(InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        form.addView(secret);
        new AlertDialog.Builder(this)
            .setTitle("添加账户")
            .setMessage("可直接粘贴 Google Authenticator 的 otpauth:// 链接，会自动解析发行方、算法、位数与周期。")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("添加", (dialog, which) -> addAccount(name.getText().toString(), secret.getText().toString()))
            .show();
    }

    private void addAccount(String name, String secret) {
        Account account = parseAccount(name, secret);
        if (account == null) {
            Toast.makeText(this, "请输入有效的账户名与密钥", Toast.LENGTH_SHORT).show();
            return;
        }
        if (findBySecret(account.secret) != null) {
            Toast.makeText(this, "该密钥已存在", Toast.LENGTH_SHORT).show();
            return;
        }
        accounts.add(account);
        sortAccounts();
        saveAccounts();
        renderAccounts();
        Toast.makeText(this, "已添加 " + account.display(), Toast.LENGTH_SHORT).show();
    }

    /** 支持纯 Base32 密钥或完整的 otpauth:// URI，返回 null 表示输入无效。 */
    private Account parseAccount(String rawName, String rawSecret) {
        String value = rawSecret == null ? "" : rawSecret.trim();
        if (value.toLowerCase(Locale.US).startsWith("otpauth://")) {
            Account parsed = parseOtpAuth(value);
            if (parsed == null) return null;
            if (rawName != null && !rawName.trim().isEmpty()) {
                return new Account(UUID.randomUUID().toString(), rawName.trim(), parsed.issuer,
                        parsed.secret, parsed.algorithm, parsed.digits, parsed.period);
            }
            return parsed;
        }
        String clean = normalize(value);
        if (rawName == null || rawName.trim().isEmpty() || !isValidSecret(clean)) return null;
        return new Account(UUID.randomUUID().toString(), rawName.trim(), "", clean, "SHA1", 6, 30);
    }

    private boolean isValidSecret(String secret) {
        try {
            if (secret.isEmpty() || decodeBase32(secret).length < 10) return false;
            totp("SHA1", 6, 30, secret, System.currentTimeMillis() / 1000L);
            return true;
        } catch (GeneralSecurityException | RuntimeException exception) {
            return false;
        }
    }

    // ---------------------------------------------------------------- otpauth 解析

    private Account parseOtpAuth(String uri) {
        try {
            Uri parsed = Uri.parse(uri);
            if (!"otpauth".equalsIgnoreCase(parsed.getScheme())) return null;
            String type = parsed.getHost();
            if (type == null || !"totp".equalsIgnoreCase(type)) return null; // 暂不支持 hotp

            // label 形如 "Issuer:account"，也可能只写 account。无论 issuer 参数是否存在，
            // 只要 label 含冒号就先拆分，避免名称里残留 "Issuer:" 前缀。
            String label = parsed.getPath() == null ? "" : parsed.getPath().replaceFirst("^/", "");
            String name = Uri.decode(label);
            String labelIssuer = null;
            if (name.contains(":")) {
                String[] split = name.split(":", 2);
                labelIssuer = split[0].trim();
                name = split[1].trim();
            }
            String issuer = param(parsed, "issuer");
            if (issuer == null || issuer.isEmpty()) issuer = labelIssuer;
            if (issuer == null) issuer = "";
            if (name.isEmpty()) name = issuer;
            if (name.isEmpty()) name = "未命名账户";

            String secret = normalize(param(parsed, "secret"));
            if (secret.isEmpty() || !isValidSecret(secret)) return null;

            String algorithm = normalizeAlgorithm(param(parsed, "algorithm"));
            int digits = parseChoice(param(parsed, "digits"), 6, new int[]{6, 8});
            int period = parseChoice(param(parsed, "period"), 30, null);
            if (period < 5 || period > 300) period = 30;

            return new Account(UUID.randomUUID().toString(), name, issuer, secret, algorithm, digits, period);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String param(Uri uri, String key) {
        try { return uri.getQueryParameter(key); } catch (RuntimeException exception) { return null; }
    }

    private String normalizeAlgorithm(String value) {
        if (value == null) return "SHA1";
        String upper = value.trim().toUpperCase(Locale.US).replace("-", "");
        for (String algorithm : ALGORITHMS) if (algorithm.equals(upper)) return upper;
        return "SHA1";
    }

    private int parseChoice(String value, int fallback, int[] allowed) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (allowed == null) return parsed;
            for (int candidate : allowed) if (candidate == parsed) return parsed;
        } catch (RuntimeException ignored) { }
        return fallback;
    }

    // ---------------------------------------------------------------- 列表渲染

    private void renderAccounts() {
        accountList.removeAllViews();
        if (accounts.isEmpty()) {
            accountList.addView(emptyState("还没有账户\n点击上方“添加”，或粘贴 otpauth:// 链接导入"));
            return;
        }
        List<Account> visible = new ArrayList<>();
        for (Account account : accounts) if (query.isEmpty() || account.matches(query)) visible.add(account);
        if (visible.isEmpty()) {
            accountList.addView(emptyState("没有匹配“" + query + "”的账户"));
            return;
        }
        for (Account account : visible) accountList.addView(accountRow(account));
        refreshCodes();
    }

    private View emptyState(String message) {
        TextView empty = text(message, 15, color("app_muted"), Typeface.NORMAL);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(dp(4), 1f);
        empty.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(140)));
        return empty;
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

        LinearLayout nameBlock = new LinearLayout(this);
        nameBlock.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(account.display(), 16, color("app_ink"), Typeface.BOLD);
        name.setSingleLine();
        nameBlock.addView(name);
        TextView meta = text(account.meta(), 12, color("app_muted"), Typeface.NORMAL);
        meta.setSingleLine();
        nameBlock.addView(meta);
        top.addView(nameBlock, new LinearLayout.LayoutParams(0, -2, 1));

        if (account.pinned) {
            TextView pin = text("置顶", 11, color("app_blue"), Typeface.BOLD);
            pin.setPadding(dp(8), dp(2), dp(8), dp(2));
            pin.setBackground(roundRect(color("app_surface"), color("app_line")));
            LinearLayout.LayoutParams pinParams = new LinearLayout.LayoutParams(-2, -2);
            pinParams.setMargins(0, 0, dp(8), 0);
            top.addView(pin, pinParams);
        }

        Button remove = new Button(this);
        remove.setText("删除");
        remove.setAllCaps(false);
        remove.setContentDescription("删除 " + account.display());
        remove.setOnClickListener(view -> confirmDelete(account));
        top.addView(remove, new LinearLayout.LayoutParams(dp(84), dp(44)));
        row.addView(top);

        TextView code = text(maskCode(account), 34, color("app_ink"), Typeface.MONOSPACE);
        code.setTag(account.id);
        code.setGravity(Gravity.CENTER_VERTICAL);
        code.setContentDescription("复制 " + account.display() + " 的验证码");
        code.setPadding(0, dp(6), 0, dp(6));
        code.setOnClickListener(view -> copyCode(account, ((TextView) view).getText().toString()));
        code.setOnLongClickListener(view -> { showAccountMenu(account); return true; });
        row.addView(code, new LinearLayout.LayoutParams(-1, dp(58)));

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setTag("progress-" + account.id);
        progress.setMax(account.period);
        row.addView(progress, new LinearLayout.LayoutParams(-1, dp(8)));

        // 长按整行或验证码均可调出操作菜单
        row.setOnLongClickListener(view -> { showAccountMenu(account); return true; });
        return row;
    }

    private String maskCode(Account account) {
        return account.digits == 8 ? "--------" : "------";
    }

    private void showAccountMenu(Account account) {
        String[] items = {account.pinned ? "取消置顶" : "置顶", "重命名", "复制密钥", "删除"};
        new AlertDialog.Builder(this)
            .setTitle(account.display())
            .setItems(items, (dialog, which) -> {
                switch (which) {
                    case 0:
                        account.pinned = !account.pinned;
                        sortAccounts();
                        saveAccounts();
                        renderAccounts();
                        break;
                    case 1: promptRename(account); break;
                    case 2: copyText("2FA secret", account.secret, "密钥已复制"); break;
                    case 3: confirmDelete(account); break;
                    default: break;
                }
            })
            .setNegativeButton("关闭", null)
            .show();
    }

    private void promptRename(Account account) {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setText(account.name);
        input.setSelection(account.name.length());
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(24), 0, dp(24), 0);
        wrap.addView(input);
        new AlertDialog.Builder(this)
            .setTitle("重命名账户")
            .setView(wrap)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", (dialog, which) -> {
                String value = input.getText().toString().trim();
                if (value.isEmpty()) { Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show(); return; }
                account.name = value;
                saveAccounts();
                renderAccounts();
            })
            .show();
    }

    private void confirmDelete(Account account) {
        new AlertDialog.Builder(this).setMessage("删除 " + account.display() + "？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除", (dialog, which) -> {
                accounts.remove(account); saveAccounts(); renderAccounts();
            }).show();
    }

    // ---------------------------------------------------------------- 刷新与复制

    private void refreshCodes() {
        if (accounts.isEmpty()) return;
        long now = System.currentTimeMillis() / 1000L;
        int standardRemaining = (int) (30 - (now % 30));
        timerText.setText(standardRemaining + "s");
        timerText.setTextColor(standardRemaining <= 5 ? color("app_blue") : color("app_muted"));

        for (Account account : accounts) {
            TextView code = findByTag(accountList, account.id, TextView.class);
            ProgressBar progress = findByTag(accountList, "progress-" + account.id, ProgressBar.class);
            if (code == null) continue;
            int remaining = (int) (account.period - (now % account.period));
            try {
                String value = totp(account.algorithm, account.digits, account.period, account.secret, now);
                code.setText(formatCode(value));
                code.setTextColor(remaining <= 5 ? color("app_blue") : color("app_ink"));
            } catch (GeneralSecurityException | RuntimeException exception) {
                code.setText(maskCode(account));
                code.setTextColor(color("app_muted"));
            }
            if (progress != null) progress.setProgress(remaining);
        }
    }

    /** 六位码显示为「123 456」，八位码四四分组，便于核对。 */
    private String formatCode(String code) {
        if (code.length() == 8) return code.substring(0, 4) + " " + code.substring(4);
        if (code.length() == 6) return code.substring(0, 3) + " " + code.substring(3);
        return code;
    }

    private void copyCode(Account account, String code) {
        String plain = code.replace(" ", "");
        if (plain.replace("-", "").isEmpty()) return;
        int remaining = (int) (account.period - (System.currentTimeMillis() / 1000L) % account.period);
        copyText("2FA code", plain, "已复制，剩余 " + remaining + " 秒");
    }

    private void copyText(String label, String value, String toast) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- 导入 / 导出

    private void showImportDialog() {
        new AlertDialog.Builder(this)
            .setTitle("导入账户")
            .setItems(new String[]{"从剪贴板粘贴", "从文件读取"}, (dialog, which) -> {
                if (which == 0) importFromClipboard();
                else pickImportFile();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void importFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null
                || clipboard.getPrimaryClip().getItemCount() == 0) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
        CharSequence text = item == null ? null : item.coerceToText(this);
        importPayload(text == null ? "" : text.toString());
    }

    private void pickImportFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        try {
            startActivityForResult(intent, REQUEST_IMPORT_FILE);
        } catch (RuntimeException exception) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQUEST_EXPORT_FILE && data.getData() != null) {
            String payload = data.getStringExtra("payload");
            writeExport(data.getData(), payload == null ? buildExportPayload() : payload);
            return;
        }
        if (requestCode != REQUEST_IMPORT_FILE || data.getData() == null) return;
        try (InputStream stream = getContentResolver().openInputStream(data.getData())) {
            if (stream == null) throw new IllegalStateException("empty stream");
            StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) builder.append(line).append('\n');
            }
            importPayload(builder.toString());
        } catch (Exception exception) {
            Toast.makeText(this, "读取文件失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 支持 otpauth:// 链接，也支持「名称,密钥」或「名称<TAB>密钥」两种简写。 */
    private void importPayload(String payload) {
        int added = 0, skipped = 0, invalid = 0;
        for (String rawLine : payload.split("[\\r\\n]+")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Account account;
            if (line.toLowerCase(Locale.US).startsWith("otpauth://")) {
                account = parseOtpAuth(line);
            } else {
                account = parseShorthand(line);
            }
            if (account == null) { invalid++; continue; }
            if (findBySecret(account.secret) != null) { skipped++; continue; }
            accounts.add(account);
            added++;
        }
        if (added > 0) { sortAccounts(); saveAccounts(); renderAccounts(); }
        Toast.makeText(this, "导入完成：新增 " + added + "，重复 " + skipped + "，无效 " + invalid,
                Toast.LENGTH_LONG).show();
    }

    /**
     * 解析「名称,密钥」或「名称<TAB>密钥」简写行。
     * 从右端找第一个逗号或制表符作分隔，并要求密钥段完全由 Base32 字符组成，
     * 这样名称里含逗号（如「我的,账户」）也能正确解析。
     */
    private Account parseShorthand(String line) {
        for (int index = line.length() - 1; index > 0; index--) {
            char separator = line.charAt(index);
            if (separator != ',' && separator != '\t') continue;
            String name = line.substring(0, index).trim();
            String secret = normalize(line.substring(index + 1));
            if (name.isEmpty() || secret.length() < 16) continue;
            if (!isPureBase32(line.substring(index + 1))) continue;
            if (!isValidSecret(secret)) continue;
            return new Account(UUID.randomUUID().toString(), name, "", secret, "SHA1", 6, 30);
        }
        return null;
    }

    /** 密钥段除 Base32 字符外只允许空格与连字符，防止把名称误当成密钥。 */
    private boolean isPureBase32(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return false;
        return trimmed.toUpperCase(Locale.US).matches("[A-Z2-7\\s\\-]+");
    }

    private void showExportDialog() {
        if (accounts.isEmpty()) { Toast.makeText(this, "没有可导出的账户", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this)
            .setTitle("导出 " + accounts.size() + " 个账户")
            .setMessage("导出内容包含明文密钥，请妥善保管，不要分享给他人。")
            .setItems(new String[]{"复制到剪贴板", "保存为文件"}, (dialog, which) -> {
                String payload = buildExportPayload();
                if (which == 0) copyText("2FA backup", payload, "已复制 " + accounts.size() + " 个账户");
                else saveExportFile(payload);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private String buildExportPayload() {
        StringBuilder builder = new StringBuilder();
        for (Account account : accounts) builder.append(account.toOtpAuth()).append('\n');
        return builder.toString().trim();
    }

    private void saveExportFile(String payload) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "google-2fa-backup.txt");
        intent.putExtra("payload", payload);
        try {
            startActivityForResult(intent, REQUEST_EXPORT_FILE);
        } catch (RuntimeException exception) {
            Toast.makeText(this, "无法打开文件保存器", Toast.LENGTH_SHORT).show();
        }
    }

    private void writeExport(Uri target, String payload) {
        try (OutputStream stream = getContentResolver().openOutputStream(target, "wt")) {
            if (stream == null) throw new IllegalStateException("empty stream");
            stream.write(payload.getBytes(StandardCharsets.UTF_8));
            stream.flush();
            Toast.makeText(this, "备份已保存", Toast.LENGTH_SHORT).show();
        } catch (Exception exception) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- 存储

    private void loadAccounts() {
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ACCOUNTS, "[]");
        try {
            JSONArray list = new JSONArray(raw);
            for (int index = 0; index < list.length(); index++) {
                JSONObject item = list.getJSONObject(index);
                Account account = new Account(
                        item.optString("id", UUID.randomUUID().toString()),
                        item.optString("name", "未命名账户"),
                        item.optString("issuer", ""),
                        item.optString("secret", ""),
                        normalizeAlgorithm(item.optString("algorithm", "SHA1")),
                        parseChoice(item.optString("digits", "6"), 6, new int[]{6, 8}),
                        parseChoice(item.optString("period", "30"), 30, null));
                account.pinned = item.optBoolean("pinned", false);
                if (!account.secret.isEmpty()) accounts.add(account);
            }
            sortAccounts();
        } catch (Exception ignored) { }
    }

    private void saveAccounts() {
        JSONArray list = new JSONArray();
        for (Account account : accounts) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", account.id);
                item.put("name", account.name);
                item.put("issuer", account.issuer);
                item.put("secret", account.secret);
                item.put("algorithm", account.algorithm);
                item.put("digits", account.digits);
                item.put("period", account.period);
                item.put("pinned", account.pinned);
                list.put(item);
            } catch (Exception ignored) { }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ACCOUNTS, list.toString()).apply();
    }

    private void sortAccounts() {
        Collections.sort(accounts, new Comparator<Account>() {
            @Override public int compare(Account left, Account right) {
                if (left.pinned != right.pinned) return left.pinned ? -1 : 1;
                return left.display().compareToIgnoreCase(right.display());
            }
        });
    }

    private Account findBySecret(String secret) {
        for (Account account : accounts) if (account.secret.equals(secret)) return account;
        return null;
    }

    // ---------------------------------------------------------------- TOTP 核心

    private String totp(String algorithm, int digits, int period, String secret, long seconds)
            throws GeneralSecurityException {
        byte[] key = decodeBase32(secret);
        if (key.length == 0) throw new GeneralSecurityException("Empty key");
        ByteBuffer counter = ByteBuffer.allocate(8).putLong(seconds / period);
        Mac mac = Mac.getInstance("Hmac" + algorithm);
        mac.init(new SecretKeySpec(key, "Hmac" + algorithm));
        byte[] hash = mac.doFinal(counter.array());
        int offset = hash[hash.length - 1] & 0x0f;
        if (offset + 4 > hash.length) throw new GeneralSecurityException("Invalid HMAC result");
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
        long modulus = digits == 8 ? 100_000_000L : 1_000_000L;
        return String.format(Locale.US, "%0" + digits + "d", binary % modulus);
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

    private String normalize(String value) {
        if (value == null) return "";
        return value.toUpperCase(Locale.US).replaceAll("[^A-Z2-7]", "");
    }

    // ---------------------------------------------------------------- 通用工具

    private TextView text(String value, int size, int tint, int face) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(tint); view.setTypeface(null, face); return view; }
    private TextView text(String value, int size, int tint, Typeface face) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(tint); view.setTypeface(face); return view; }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private int color(String name) { return getResources().getColor(getResources().getIdentifier(name, "color", getPackageName())); }
    private GradientDrawable roundRect(int fill, int stroke) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(6)); drawable.setStroke(dp(1), stroke); return drawable; }
    @SuppressWarnings("unchecked") private <T extends View> T findByTag(View root, Object tag, Class<T> type) { if (tag.equals(root.getTag()) && type.isInstance(root)) return (T) root; if (root instanceof ViewGroup) { ViewGroup group = (ViewGroup) root; for (int index = 0; index < group.getChildCount(); index++) { T found = findByTag(group.getChildAt(index), tag, type); if (found != null) return found; } } return null; }

    private static class Account {
        final String id;
        String name, issuer;
        final String secret;
        final String algorithm;
        final int digits;
        final int period;
        boolean pinned;

        Account(String id, String name, String issuer, String secret, String algorithm, int digits, int period) {
            this.id = id; this.name = name; this.issuer = issuer; this.secret = secret;
            this.algorithm = algorithm; this.digits = digits; this.period = period;
        }

        String display() { return issuer == null || issuer.isEmpty() ? name : issuer + " · " + name; }

        String meta() { return algorithm + " · " + digits + " 位 · " + period + " 秒"; }

        boolean matches(String lowerQuery) {
            return display().toLowerCase(Locale.US).contains(lowerQuery)
                    || secret.toLowerCase(Locale.US).contains(lowerQuery);
        }

        String toOtpAuth() {
            String prefix = (issuer == null || issuer.isEmpty()) ? "" : issuer + ":";
            StringBuilder builder = new StringBuilder("otpauth://totp/").append(Uri.encode(prefix + name))
                    .append("?secret=").append(secret);
            if (issuer != null && !issuer.isEmpty()) builder.append("&issuer=").append(Uri.encode(issuer));
            if (!"SHA1".equals(algorithm)) builder.append("&algorithm=").append(algorithm);
            if (digits != 6) builder.append("&digits=").append(digits);
            if (period != 30) builder.append("&period=").append(period);
            return builder.toString();
        }
    }
}
