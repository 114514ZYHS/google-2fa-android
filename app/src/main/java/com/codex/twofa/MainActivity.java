package com.codex.twofa;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
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

/**
 * 2FA 验证器主界面。
 *
 * 布局分两块：
 *   上部 约 3/4 —— 账户管理区（搜索、导入导出、账户卡片列表）
 *   下部 约 1/4 —— 快速验证码区（当前选中账户的大号验证码与倒计时）
 *
 * 算法层面不做任何限制：位数支持 1~18 任意值，算法支持 SHA1/256/512，
 * 周期支持 5~300 秒，全部按输入值真实计算。
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "accounts";
    private static final String KEY_ACCOUNTS = "items";
    private static final String KEY_SELECTED = "selected";
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String[] ALGORITHMS = {"SHA1", "SHA256", "SHA512"};

    /** 位数允许范围。RFC 6238 只规定了 6/8，但实际实现普遍放宽，这里取安全上限 18。 */
    private static final int MIN_DIGITS = 1;
    private static final int MAX_DIGITS = 18;
    private static final int MIN_PERIOD = 5;
    private static final int MAX_PERIOD = 300;

    private static final int REQUEST_IMPORT_FILE = 4001;
    private static final int REQUEST_EXPORT_FILE = 4002;

    /** 中文数字，用于「验证器一」「验证器二」这样的默认命名。 */
    private static final String[] CN_NUMBERS = {
            "一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十"
    };

    private final ArrayList<Account> accounts = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private AccountAdapter adapter;
    private RecyclerView accountList;
    private View emptyState;
    private TextInputLayout searchWrap;
    private TextInputEditText searchInput;
    private TextView globalTimer;
    private TextView quickCode;
    private TextView quickMeta;
    private TextInputLayout quickWrap;
    private TextInputEditText quickSecret;
    private LinearProgressIndicator quickProgress;

    private String query = "";
    private String selectedId = null;

    /** 快速验证区使用的参数，与输入框内容配合，独立于账户列表。 */
    private String quickAlgorithm = "SHA1";
    private int quickDigits = 6;
    private int quickPeriod = 30;

    /** 只有初始化完全成功后才启动秒级刷新，避免在异常状态下反复触发。 */
    private boolean ready = false;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!ready) return;
            refreshCodes();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            bindViews();
            loadAccounts();
            renderAccounts();
            selectInitial();
            ready = true;
        } catch (Throwable throwable) {
            // 任何初始化异常都不允许直接闪退：把原因显示出来，便于定位。
            ready = false;
            showFatal(throwable);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(ticker);
        if (ready) handler.post(ticker);
    }

    @Override protected void onPause() { handler.removeCallbacks(ticker); super.onPause(); }

    /** 用最朴素的方式把异常展示出来，避免用户只看到「已停止运行」。 */
    private void showFatal(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        android.util.Log.e("2FA", "初始化失败", throwable);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        TextView view = new TextView(this);
        view.setText("启动失败：\n\n" + writer.toString());
        view.setTextSize(12);
        view.setTypeface(Typeface.MONOSPACE);
        view.setPadding(32, 32, 32, 32);
        view.setTextIsSelectable(true);
        scroll.addView(view);
        setContentView(scroll);
    }


    // ---------------------------------------------------------------- 视图绑定

    private void bindViews() {
        accountList = findViewById(R.id.accountList);
        emptyState = findViewById(R.id.emptyState);
        searchWrap = findViewById(R.id.searchWrap);
        searchInput = findViewById(R.id.searchInput);
        globalTimer = findViewById(R.id.globalTimer);
        quickCode = findViewById(R.id.quickCode);
        quickMeta = findViewById(R.id.quickMeta);
        quickWrap = findViewById(R.id.quickWrap);
        quickSecret = findViewById(R.id.quickSecret);
        quickProgress = findViewById(R.id.quickProgress);

        adapter = new AccountAdapter();
        accountList.setLayoutManager(new LinearLayoutManager(this));
        accountList.setAdapter(adapter);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.US);
                renderAccounts();
            }
        });

        findViewById(R.id.btnAdd).setOnClickListener(v -> showEditDialog(null));
        findViewById(R.id.btnImport).setOnClickListener(v -> showImportDialog());
        findViewById(R.id.btnExport).setOnClickListener(v -> showExportDialog());
        findViewById(R.id.btnSort).setOnClickListener(v -> showSortDialog());

        // ---- 快速验证区：输入密钥即时出码 ----
        // 输入框内容一变就重算，不做任何格式校验
        quickSecret.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable s) {
                updateQuickPanel();
            }
        });
        findViewById(R.id.btnQuickCopy).setOnClickListener(v -> copyQuickCode());
    }

    // ---------------------------------------------------------------- 列表渲染

    private void renderAccounts() {
        List<Account> visible = visibleAccounts();
        adapter.submit(visible);

        boolean nothingAtAll = accounts.isEmpty();
        boolean noMatch = !nothingAtAll && visible.isEmpty();
        emptyState.setVisibility(nothingAtAll || noMatch ? View.VISIBLE : View.GONE);
        accountList.setVisibility(nothingAtAll || noMatch ? View.GONE : View.VISIBLE);
        if (noMatch) {
            TextView title = emptyState.findViewById(R.id.emptyTitle);
            if (title != null) title.setText(getString(R.string.empty_title));
        }
        refreshCodes();
    }

    private List<Account> visibleAccounts() {
        List<Account> result = new ArrayList<>();
        for (Account account : accounts) {
            if (query.isEmpty() || account.matches(query)) result.add(account);
        }
        return result;
    }

    private void selectInitial() {
        // 快速验证区独立于账户列表，启动时不自动填充，留空等用户输入
        updateQuickPanel();
    }

    private Account selectedAccount() {
        if (selectedId == null) return accounts.isEmpty() ? null : accounts.get(0);
        for (Account account : accounts) if (account.id.equals(selectedId)) return account;
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    private void select(Account account) {
        selectedId = account.id;
        adapter.setSelectedId(selectedId);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SELECTED, selectedId).apply();
        // 点击账户时把它的参数与密钥带到快速区，方便直接复制
        quickAlgorithm = account.algorithm;
        quickDigits = account.digits;
        quickPeriod = account.period;
        if (quickSecret != null) quickSecret.setText(account.secret);
        updateQuickPanel();
    }

    // ---------------------------------------------------------------- 定时刷新

    private void refreshCodes() {
        // 定时器可能在视图尚未绑定完成时就被触发（或在初始化异常后），
        // 这里必须做完整判空，否则会抛 NullPointerException 导致闪退。
        if (adapter == null) return;

        if (!accounts.isEmpty()) {
            long now = System.currentTimeMillis() / 1000L;
            int period = currentPeriod();
            int remaining = (int) (period - (now % period));
            if (globalTimer != null) {
                globalTimer.setText(remaining + "s");
                globalTimer.setBackgroundResource(R.drawable.bg_timer_pill);
                globalTimer.setTextColor(remaining <= 5 ? color("app_warning") : color("app_primary"));
            }
        }
        adapter.notifyDataSetChanged();
        updateQuickPanel();
    }

    /** 当前选中账户的周期，没有账户时回退到 30 秒。 */
    private int currentPeriod() {
        Account account = selectedAccount();
        return account == null ? 30 : account.period;
    }

    /**
     * 快速验证区：直接按输入框里的密钥实时出码。
     *
     * 设计原则与账户添加一致 —— 不校验、不阻拦：
     *   输入框里只要有内容，就一定给出验证码。
     *   密钥的取值完全由 deriveKey() 兜底（Base32 解不出就用原始字节）。
     */
    private void updateQuickPanel() {
        // 视图尚未就绪时直接返回，避免空指针
        if (quickCode == null || quickProgress == null || quickSecret == null) return;

        String raw = quickSecret.getText() == null ? "" : quickSecret.getText().toString().trim();
        if (quickMeta != null) quickMeta.setText("");

        if (raw.isEmpty()) {
            quickCode.setText(getString(R.string.quick_placeholder));
            quickCode.setTextColor(color("app_muted"));
            safeProgress(quickProgress, 0, 1);
            return;
        }

        long now = System.currentTimeMillis() / 1000L;
        int period = clampPeriod(quickPeriod);
        int digits = clampDigits(quickDigits);
        int remaining = (int) (period - (now % period));

        String value = computeQuickCode(raw, now);
        if (value == null) {
            quickCode.setText(getString(R.string.quick_placeholder));
            quickCode.setTextColor(color("app_muted"));
        } else {
            quickCode.setText(groupCode(value));
            quickCode.setTextColor(remaining <= 5 ? color("app_warning") : color("app_ink"));
        }

        if (quickMeta != null) {
            quickMeta.setText(quickAlgorithm + " · " + digits + "位 · " + period + "s");
        }
        safeProgress(quickProgress, remaining, period);
        quickProgress.setIndicatorColor(remaining <= 5 ? color("app_warning") : color("app_primary"));
    }

    /** 快速区专用：按当前参数直接算码，任何异常都返回 null 而不是抛出。 */
    private String computeQuickCode(String secret, long seconds) {
        try {
            return totp(quickAlgorithm, clampDigits(quickDigits),
                    clampPeriod(quickPeriod), secret, seconds);
        } catch (GeneralSecurityException | RuntimeException exception) {
            return null;
        }
    }

    /** 复制快速区当前显示的验证码。 */
    private void copyQuickCode() {
        if (quickSecret == null) return;
        String raw = quickSecret.getText() == null ? "" : quickSecret.getText().toString().trim();
        if (raw.isEmpty()) {
            toast(getString(R.string.quick_nothing_to_copy));
            return;
        }
        String value = computeQuickCode(raw, System.currentTimeMillis() / 1000L);
        if (value == null) {
            toast(getString(R.string.quick_nothing_to_copy));
            return;
        }
        copyText("验证码", value, getString(R.string.quick_copied));
    }

    /**
     * 安全设置进度条。ProgressIndicator 在 max 为 0 或 progress 越界时会抛异常，
     * 因此统一在这里做边界收敛，避免任何情况下把 Activity 打崩。
     */
    private void safeProgress(LinearProgressIndicator indicator, int progress, int max) {
        if (indicator == null) return;
        int safeMax = Math.max(1, max);
        int safeProgress = Math.max(0, Math.min(progress, safeMax));
        try {
            indicator.setMax(safeMax);
            indicator.setProgress(safeProgress);
        } catch (RuntimeException ignored) { }
    }


    /** 计算验证码，失败返回 null。位数不限，按实际位数生成。 */
    private String computeCode(Account account, long seconds) {
        try {
            return totp(account.algorithm, account.digits, account.period, account.secret, seconds);
        } catch (GeneralSecurityException | RuntimeException exception) {
            return null;
        }
    }

    /** 把验证码按 3~4 位分组，便于人眼核对；组不满时按 4 位切分，过短则原样显示。 */
    private String groupCode(String code) {
        if (code == null || code.isEmpty()) return code;
        if (code.length() == 6) return code.substring(0, 3) + " " + code.substring(3);
        if (code.length() == 8) return code.substring(0, 4) + " " + code.substring(4);
        if (code.length() >= 4) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < code.length(); i++) {
                if (i > 0 && i % 4 == 0) builder.append(' ');
                builder.append(code.charAt(i));
            }
            return builder.toString();
        }
        return code;
    }

    /** 计算不出验证码时显示的占位掩码，长度与位数一致。 */
    private String maskFor(int digits) {
        int safe = Math.max(1, digits);
        StringBuilder builder = new StringBuilder();
        int half = Math.max(1, safe / 2);
        for (int i = 0; i < safe; i++) {
            if (i == half && safe >= 4) builder.append(' ');
            builder.append('-');
        }
        return builder.toString();
    }

    private void copyCode(Account account) {
        String value = computeCode(account, System.currentTimeMillis() / 1000L);
        if (value == null) {
            toast("无法计算验证码，请检查密钥");
            return;
        }
        int remaining = (int) (account.period - (System.currentTimeMillis() / 1000L) % account.period);
        copyText("验证码", value, "已复制，剩余 " + remaining + " 秒");
    }

    private void copyText(String label, String value, String message) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        toast(message);
    }

    // ---------------------------------------------------------------- 账户增删改

    /**
     * 新增或编辑账户。account 为 null 表示新增。
     * 名称留空时自动使用「验证器N」。
     */
    private void showEditDialog(Account account) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_edit_account, null, false);

        TextInputLayout nameWrap = content.findViewById(R.id.nameWrap);
        TextInputLayout secretWrap = content.findViewById(R.id.secretWrap);
        TextInputEditText nameInput = content.findViewById(R.id.nameInput);
        TextInputEditText secretInput = content.findViewById(R.id.secretInput);
        TextInputLayout digitsWrap = content.findViewById(R.id.digitsWrap);
        TextInputEditText digitsInput = content.findViewById(R.id.digitsInput);
        TextInputEditText periodInput = content.findViewById(R.id.periodInput);
        MaterialButtonToggleGroup groupAlgorithm = content.findViewById(R.id.groupAlgorithm);
        MaterialButtonToggleGroup groupDigits = content.findViewById(R.id.groupDigits);

        final String[] algorithm = {account == null ? "SHA1" : account.algorithm};
        final int[] digits = {account == null ? 6 : account.digits};

        // 编辑已有账户时预填
        if (account != null) {
            nameInput.setText(account.name);
            nameInput.setSelection(account.name.length());
            secretInput.setText(account.secret);
            periodInput.setText(String.valueOf(account.period));
        }

        applyAlgorithmSelection(groupAlgorithm, algorithm[0]);
        applyDigitsSelection(groupDigits, digits[0], digitsWrap, digitsInput);

        groupAlgorithm.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.algSha1) algorithm[0] = "SHA1";
            else if (checkedId == R.id.algSha256) algorithm[0] = "SHA256";
            else if (checkedId == R.id.algSha512) algorithm[0] = "SHA512";
        });

        groupDigits.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.dig6) { digits[0] = 6; digitsWrap.setVisibility(View.GONE); }
            else if (checkedId == R.id.dig7) { digits[0] = 7; digitsWrap.setVisibility(View.GONE); }
            else if (checkedId == R.id.dig8) { digits[0] = 8; digitsWrap.setVisibility(View.GONE); }
            else { digitsWrap.setVisibility(View.VISIBLE); digitsInput.requestFocus(); }
        });

        // 粘贴 otpauth:// 链接时自动识别参数，并回填到界面
        secretInput.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable s) {
                String raw = s.toString().trim();
                if (!raw.toLowerCase(Locale.US).startsWith("otpauth://")) return;
                Account parsed = parseOtpAuth(raw);
                if (parsed == null) return;
                algorithm[0] = parsed.algorithm;
                digits[0] = parsed.digits;
                applyAlgorithmSelection(groupAlgorithm, parsed.algorithm);
                applyDigitsSelection(groupDigits, parsed.digits, digitsWrap, digitsInput);
                periodInput.setText(String.valueOf(parsed.period));
                if (nameInput.getText().toString().trim().isEmpty() && !parsed.name.isEmpty()) {
                    nameInput.setText(parsed.name);
                }
            }
        });

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(account == null ? R.string.dialog_add_title : R.string.dialog_edit_title)
                .setView(content)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, null);

        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();
        // 自行处理点击，校验不通过时不关闭对话框
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String rawName = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
                    String rawSecret = secretInput.getText() == null ? "" : secretInput.getText().toString().trim();
                    String periodText = periodInput.getText() == null ? "30" : periodInput.getText().toString().trim();

                    // 自定义位数：超出常规范围时收敛到安全区间，而不是拦着不让加
                    if (groupDigits.getCheckedButtonId() == R.id.digCustom) {
                        String custom = digitsInput.getText() == null ? "" : digitsInput.getText().toString().trim();
                        int parsed = parseIntSafe(custom, 6);
                        digits[0] = clampDigits(parsed);
                    }
                    digitsWrap.setError(null);

                    // 周期同样收敛到安全区间
                    int period = clampPeriod(parseIntSafe(periodText, 30));

                    if (rawSecret.isEmpty()) {
                        secretWrap.setError("请输入密钥");
                        return;
                    }
                    secretWrap.setError(null);

                    // 不做合法性校验、不做重复校验 —— 任何非空输入都能加进来
                    Account parsed = parseAccount(rawName, rawSecret, algorithm[0], digits[0], period);
                    if (parsed == null) {
                        // 理论上只有输入全空白才会走到这里
                        secretWrap.setError("请输入密钥");
                        return;
                    }

                    if (account == null) {
                        accounts.add(parsed);
                        sortAccounts();
                        saveAccounts();
                        selectedId = parsed.id;
                        renderAccounts();
                        select(parsed);
                        toast("已添加 " + parsed.display());
                    } else {
                        account.name = parsed.name;
                        account.issuer = parsed.issuer;
                        account.algorithm = parsed.algorithm;
                        account.digits = parsed.digits;
                        account.period = parsed.period;
                        saveAccounts();
                        renderAccounts();
                        updateQuickPanel();
                        toast("已保存");
                    }
                    dialog.dismiss();
                });
    }

    private void applyAlgorithmSelection(MaterialButtonToggleGroup group, String algorithm) {
        if ("SHA256".equals(algorithm)) group.check(R.id.algSha256);
        else if ("SHA512".equals(algorithm)) group.check(R.id.algSha512);
        else group.check(R.id.algSha1);
    }

    private void applyDigitsSelection(MaterialButtonToggleGroup group, int digits,
                                      TextInputLayout wrap, TextInputEditText input) {
        if (digits == 6) { group.check(R.id.dig6); wrap.setVisibility(View.GONE); }
        else if (digits == 7) { group.check(R.id.dig7); wrap.setVisibility(View.GONE); }
        else if (digits == 8) { group.check(R.id.dig8); wrap.setVisibility(View.GONE); }
        else {
            group.check(R.id.digCustom);
            wrap.setVisibility(View.VISIBLE);
            input.setText(String.valueOf(digits));
        }
    }

    /**
     * 解析用户输入为账户对象。name 为空时自动命名为「验证器N」。
     *
     * 支持 otpauth:// 链接与任意密钥文本。
     * **不做合法性拦截**：只要输入非空，就一定构造出账户。
     * 密钥能否算出验证码由 deriveKey() 保证，而不是在这里拒绝用户。
     */
    private Account parseAccount(String rawName, String rawSecret, String algorithm, int digits, int period) {
        String value = rawSecret == null ? "" : rawSecret.trim();
        if (value.isEmpty()) return null;

        if (value.toLowerCase(Locale.US).startsWith("otpauth://")) {
            Account parsed = parseOtpAuth(value);
            if (parsed == null) return null;
            String name = rawName == null ? "" : rawName.trim();
            return new Account(UUID.randomUUID().toString(),
                    name.isEmpty() ? parsed.name : name,
                    parsed.issuer, parsed.secret, parsed.algorithm, parsed.digits, parsed.period);
        }

        // 原样保存用户输入，不做清洗 —— 显示与再次编辑时保持所见即所得
        String secret = value;
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) name = nextDefaultName();
        return new Account(UUID.randomUUID().toString(), name, "", secret, algorithm, digits, period);
    }

    /** 「验证器一」「验证器二」…… 依次取第一个未被占用的编号。 */
    private String nextDefaultName() {
        int index = 0;
        while (true) {
            String candidate = "验证器" + numberLabel(index);
            if (!nameUsed(candidate)) return candidate;
            index++;
        }
    }

    private boolean nameUsed(String name) {
        for (Account account : accounts) if (name.equals(account.name)) return true;
        return false;
    }

    private String numberLabel(int index) {
        if (index < CN_NUMBERS.length) return CN_NUMBERS[index];
        return String.valueOf(index + 1);
    }

    // ---------------------------------------------------------------- otpauth 解析

    private Account parseOtpAuth(String uri) {
        try {
            Uri parsed = Uri.parse(uri);
            if (!"otpauth".equalsIgnoreCase(parsed.getScheme())) return null;
            String type = parsed.getHost();
            if (type == null || !"totp".equalsIgnoreCase(type)) return null;

            // label 形如 "Issuer:account"，也可能只写 account
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
            if (name.isEmpty()) name = nextDefaultName();

            String secret = normalize(param(parsed, "secret"));
            if (secret.isEmpty()) return null;
            if (decodeBase32(secret).length == 0) return null;

            String algorithm = normalizeAlgorithm(param(parsed, "algorithm"));
            // 位数：信任链接里的值并做边界裁剪，不再限制只能是 6 或 8
            int digits = parseIntSafe(param(parsed, "digits"), 6);
            if (digits < MIN_DIGITS || digits > MAX_DIGITS) digits = 6;

            int period = parseIntSafe(param(parsed, "period"), 30);
            if (period < MIN_PERIOD || period > MAX_PERIOD) period = 30;

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

    private int parseIntSafe(String value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value.trim()); } catch (RuntimeException exception) { return fallback; }
    }

    // ---------------------------------------------------------------- 排序与菜单

    private void sortAccounts() {
        Collections.sort(accounts, new Comparator<Account>() {
            @Override public int compare(Account a, Account b) {
                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
                return a.display().compareToIgnoreCase(b.display());
            }
        });
    }

    private void showSortDialog() {
        String[] items = {"按名称排序", "按添加顺序排序", "按发行方排序"};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_sort)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        for (Account account : accounts) account.pinned = false;
                        sortAccounts();
                    } else if (which == 1) {
                        Collections.sort(accounts, (a, b) -> 0);
                    } else {
                        Collections.sort(accounts, (a, b) ->
                                a.issuer.compareToIgnoreCase(b.issuer));
                    }
                    saveAccounts();
                    renderAccounts();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showAccountMenu(Account account) {
        String pinLabel = account.pinned ? getString(R.string.action_unpin) : getString(R.string.action_pin);
        String[] items = {
                getString(R.string.action_edit),
                pinLabel,
                getString(R.string.action_copy_secret),
                getString(R.string.action_delete)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(account.display())
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: showEditDialog(account); break;
                        case 1:
                            account.pinned = !account.pinned;
                            sortAccounts();
                            saveAccounts();
                            renderAccounts();
                            break;
                        case 2:
                            copyText("2FA 密钥", account.secret, getString(R.string.toast_secret_copied));
                            break;
                        case 3: confirmDelete(account); break;
                        default: break;
                    }
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    private void confirmDelete(Account account) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_delete)
                .setMessage("确定删除「" + account.display() + "」？此操作不可撤销。")
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    accounts.remove(account);
                    if (account.id.equals(selectedId)) {
                        selectedId = accounts.isEmpty() ? null : accounts.get(0).id;
                    }
                    saveAccounts();
                    renderAccounts();
                    updateQuickPanel();
                    toast(getString(R.string.toast_deleted));
                })
                .show();
    }

    // ---------------------------------------------------------------- 导入导出

    private void showImportDialog() {
        String[] items = {getString(R.string.action_import) + "（剪贴板）", "从文件导入"};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_import)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) importFromClipboard();
                    else {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        startActivityForResult(intent, REQUEST_IMPORT_FILE);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void importFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null
                || clipboard.getPrimaryClip().getItemCount() == 0) {
            toast(getString(R.string.toast_clipboard_empty));
            return;
        }
        CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) {
            toast(getString(R.string.toast_clipboard_empty));
            return;
        }
        importPayload(text.toString());
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_IMPORT_FILE) {
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                if (stream == null) { toast("无法读取文件"); return; }
                importPayload(readAll(stream));
            } catch (Exception exception) {
                toast("读取失败：" + exception.getMessage());
            }
        } else if (requestCode == REQUEST_EXPORT_FILE) {
            try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
                if (stream == null) { toast("无法写入文件"); return; }
                stream.write(buildExportPayload().getBytes(StandardCharsets.UTF_8));
                toast("已导出 " + accounts.size() + " 个账户");
            } catch (Exception exception) {
                toast("写入失败：" + exception.getMessage());
            }
        }
    }

    private String readAll(InputStream stream) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append('\n');
        }
        return builder.toString();
    }

    /**
     * 导入多行文本。支持 otpauth:// 链接、「名称,密钥」简写，以及裸密钥行。
     *
     * 与手动添加保持一致：**不做合法性拦截**。
     * 只要这一行不是空行、不是注释，就当作一条密钥导入。
     */
    private int importPayload(String payload) {
        int added = 0, skipped = 0, invalid = 0;
        if (payload == null) return 0;

        String[] lines = payload.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            Account account = null;
            if (line.toLowerCase(Locale.US).startsWith("otpauth://")) {
                account = parseOtpAuth(line);
            } else if (line.contains(",") || line.contains("\t")) {
                account = parseShorthand(line);
            }
            if (account == null) {
                // 裸密钥行：原样收下，给一个默认名称
                account = new Account(UUID.randomUUID().toString(), nextDefaultName(), "",
                        line, "SHA1", 6, 30);
            }

            if (findBySecret(account.secret) != null) { skipped++; continue; }
            accounts.add(account);
            added++;
        }

        if (added > 0) {
            sortAccounts();
            saveAccounts();
            renderAccounts();
            if (selectedId == null) selectInitial();
        }
        toast("导入完成：新增 " + added + " 个，跳过重复 " + skipped + " 个");
        return added;
    }

    /** 「名称,密钥」简写。名称里可能含逗号，因此从右往左找分隔点。不做密钥合法性校验。 */
    private Account parseShorthand(String line) {
        for (int index = line.length() - 1; index > 0; index--) {
            char separator = line.charAt(index);
            if (separator != ',' && separator != '\t') continue;
            String name = line.substring(0, index).trim();
            String secret = line.substring(index + 1).trim();
            if (name.isEmpty() || secret.isEmpty()) continue;
            return new Account(UUID.randomUUID().toString(), name, "", secret,
                    "SHA1", 6, 30);
        }
        return null;
    }

    private boolean isPureBase32(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return false;
        return trimmed.toUpperCase(Locale.US).matches("[A-Z2-7\\s\\-]+");
    }

    private void showExportDialog() {
        if (accounts.isEmpty()) { toast("还没有账户"); return; }
        String[] items = {"导出为 otpauth 链接文本", "复制到剪贴板"};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_export)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("text/plain");
                        intent.putExtra(Intent.EXTRA_TITLE, "2fa-backup.txt");
                        startActivityForResult(intent, REQUEST_EXPORT_FILE);
                    } else {
                        copyText("2FA 备份", buildExportPayload(), "已复制到剪贴板");
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private String buildExportPayload() {
        StringBuilder builder = new StringBuilder();
        builder.append("# 2FA 验证器备份\n");
        builder.append("# 每行一个账户，可直接重新导入\n\n");
        for (Account account : accounts) builder.append(account.toOtpAuth()).append('\n');
        return builder.toString();
    }

    // ---------------------------------------------------------------- 持久化

    private void loadAccounts() {
        accounts.clear();
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        selectedId = preferences.getString(KEY_SELECTED, null);
        String raw = preferences.getString(KEY_ACCOUNTS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                accounts.add(new Account(
                        item.optString("id", UUID.randomUUID().toString()),
                        item.optString("name", "未命名"),
                        item.optString("issuer", ""),
                        item.optString("secret", ""),
                        normalizeAlgorithm(item.optString("algorithm", "SHA1")),
                        clampDigits(parseIntSafe(item.optString("digits", "6"), 6)),
                        clampPeriod(parseIntSafe(item.optString("period", "30"), 30))));
                accounts.get(accounts.size() - 1).pinned = item.optBoolean("pinned", false);
            }
        } catch (Exception ignored) { }
        sortAccounts();
    }

    private int clampDigits(int value) {
        if (value < MIN_DIGITS || value > MAX_DIGITS) return 6;
        return value;
    }

    private int clampPeriod(int value) {
        if (value < MIN_PERIOD || value > MAX_PERIOD) return 30;
        return value;
    }

    private void saveAccounts() {
        JSONArray array = new JSONArray();
        try {
            for (Account account : accounts) {
                JSONObject item = new JSONObject();
                item.put("id", account.id);
                item.put("name", account.name);
                item.put("issuer", account.issuer);
                item.put("secret", account.secret);
                item.put("algorithm", account.algorithm);
                item.put("digits", account.digits);
                item.put("period", account.period);
                item.put("pinned", account.pinned);
                array.put(item);
            }
        } catch (Exception ignored) { }
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        preferences.edit()
                .putString(KEY_ACCOUNTS, array.toString())
                .putString(KEY_SELECTED, selectedId)
                .apply();
    }

    private Account findBySecret(String secret) {
        for (Account account : accounts) if (account.secret.equals(secret)) return account;
        return null;
    }

    // ---------------------------------------------------------------- TOTP 核心

    /**
     * RFC 6238 TOTP。位数不做限制，按 10^digits 取模，1~18 位都能正确输出。
     * 超过 18 位会溢出 long，因此上游已做边界限制。
     */
    private String totp(String algorithm, int digits, int period, String secret, long seconds)
            throws GeneralSecurityException {
        byte[] key = deriveKey(secret);
        if (key.length == 0) throw new GeneralSecurityException("Empty key");
        if (digits < 1) throw new GeneralSecurityException("Invalid digits");
        if (period < 1) throw new GeneralSecurityException("Invalid period");

        ByteBuffer counter = ByteBuffer.allocate(8).putLong(seconds / period);
        String macName = "Hmac" + algorithm;
        Mac mac = Mac.getInstance(macName);
        mac.init(new SecretKeySpec(key, macName));
        byte[] hash = mac.doFinal(counter.array());

        int offset = hash[hash.length - 1] & 0x0f;
        if (offset + 4 > hash.length) throw new GeneralSecurityException("Invalid HMAC result");
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);

        // 通用取模：10^digits，不再写死 6 位或 8 位
        long modulus = powerOfTen(digits);
        long value = binary % modulus;
        return String.format(Locale.US, "%0" + digits + "d", value);
    }

    private long powerOfTen(int digits) {
        long result = 1;
        for (int i = 0; i < digits; i++) result *= 10L;
        return result;
    }

    /**
     * 标准 Base32 解码（RFC 4648）。
     * 只接受 A-Z 与 2-7，遇到其它字符即返回空数组，
     * 由 deriveKey() 决定是否退回原始字节。
     */
    private byte[] decodeBase32(String value) {
        String clean = normalize(value);
        if (clean.isEmpty()) return new byte[0];
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

    /**
     * 从用户输入派生 HMAC 密钥。**永不失败**——这是「不设任何阻拦」的关键。
     *
     * 取密钥的顺序：
     *   1. 标准 Base32 解码（绝大多数验证器给出的密钥都是这种）
     *   2. 解码结果为空时，退回 UTF-8 原始字节
     *
     * 也就是说，哪怕只敲几个字母、甚至带了空格或其他符号，
     * 也一定能得到一段非空字节参与 HMAC 运算，从而算出验证码。
     */
    private byte[] deriveKey(String value) {
        if (value == null) return new byte[0];

        // 1) 先试标准 Base32
        byte[] decoded = decodeBase32(value);
        if (decoded.length > 0) return decoded;

        // 2) 退回原始字节（只去掉首尾空白，其余原样使用）
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return new byte[0];
        return trimmed.getBytes(StandardCharsets.UTF_8);
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.toUpperCase(Locale.US).replaceAll("[^A-Z2-7]", "");
    }

    // ---------------------------------------------------------------- 工具

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int color(String name) {
        return getResources().getColor(getResources().getIdentifier(name, "color", getPackageName()));
    }

    /** 简化版 TextWatcher，只关心 afterTextChanged。 */
    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
    }

    // ---------------------------------------------------------------- 列表适配器

    private class AccountAdapter extends RecyclerView.Adapter<AccountAdapter.Holder> {

        private final List<Account> items = new ArrayList<>();
        private String highlightedId;

        void submit(List<Account> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        void setSelectedId(String id) {
            highlightedId = id;
            notifyDataSetChanged();
        }

        @NonNull @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_account, parent, false);
            return new Holder(view);
        }

        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(items.get(position));
        }

        @Override public int getItemCount() { return items.size(); }

        class Holder extends RecyclerView.ViewHolder {
            final TextView name, meta, code, pin;
            final MaterialButton more, copy;
            final LinearProgressIndicator progress;

            Holder(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.rowName);
                meta = itemView.findViewById(R.id.rowMeta);
                code = itemView.findViewById(R.id.rowCode);
                pin = itemView.findViewById(R.id.rowPin);
                more = itemView.findViewById(R.id.rowMore);
                copy = itemView.findViewById(R.id.rowCopy);
                progress = itemView.findViewById(R.id.rowProgress);
            }

            void bind(Account account) {
                name.setText(account.name);
                if (account.issuer == null || account.issuer.isEmpty()) {
                    meta.setText(account.meta());
                } else {
                    meta.setText(account.issuer + " · " + account.meta());
                }
                pin.setVisibility(account.pinned ? View.VISIBLE : View.GONE);

                long now = System.currentTimeMillis() / 1000L;
                int period = clampPeriod(account.period);
                int remaining = (int) (period - (now % period));
                String value = computeCode(account, now);
                if (value == null) {
                    code.setText(maskFor(account.digits));
                    code.setTextColor(color("app_muted"));
                } else {
                    code.setText(groupCode(value));
                    code.setTextColor(remaining <= 5 ? color("app_warning") : color("app_ink"));
                }

                safeProgress(progress, remaining, period);

                // 选中态：描边加粗，让当前账户一目了然
                boolean isSelected = account.id.equals(highlightedId);
                itemView.setSelected(isSelected);

                itemView.setOnClickListener(v -> select(account));
                copy.setOnClickListener(v -> copyCode(account));
                more.setOnClickListener(v -> showAccountMenu(account));
                itemView.setOnLongClickListener(v -> { showAccountMenu(account); return true; });
                code.setOnClickListener(v -> copyCode(account));
            }
        }
    }

    // ---------------------------------------------------------------- 数据模型

    private static class Account {
        final String id;
        String name, issuer;
        final String secret;
        String algorithm;
        int digits;
        int period;
        boolean pinned;

        Account(String id, String name, String issuer, String secret,
                String algorithm, int digits, int period) {
            this.id = id;
            this.name = name;
            this.issuer = issuer == null ? "" : issuer;
            this.secret = secret;
            this.algorithm = algorithm;
            this.digits = digits;
            this.period = period;
        }

        String display() {
            return issuer == null || issuer.isEmpty() ? name : issuer + " · " + name;
        }

        String meta() {
            return algorithm + " · " + digits + " 位 · " + period + " 秒";
        }

        boolean matches(String lowerQuery) {
            return display().toLowerCase(Locale.US).contains(lowerQuery)
                    || secret.toLowerCase(Locale.US).contains(lowerQuery);
        }

        String toOtpAuth() {
            String prefix = (issuer == null || issuer.isEmpty()) ? "" : issuer + ":";
            StringBuilder builder = new StringBuilder("otpauth://totp/")
                    .append(Uri.encode(prefix + name))
                    .append("?secret=").append(secret);
            if (issuer != null && !issuer.isEmpty()) {
                builder.append("&issuer=").append(Uri.encode(issuer));
            }
            if (!"SHA1".equals(algorithm)) builder.append("&algorithm=").append(algorithm);
            if (digits != 6) builder.append("&digits=").append(digits);
            if (period != 30) builder.append("&period=").append(period);
            return builder.toString();
        }
    }
}
