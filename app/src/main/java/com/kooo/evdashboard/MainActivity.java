package com.kooo.evdashboard;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import com.kooo.evdashboard.core.*;
import java.io.*;
import java.util.*;

public final class MainActivity extends Activity {
    private static final String[][] TITLES = {
        {"连接 CP", "声音目录", "预设与导出", "自动覆盖", "使用说明"},
        {"1 · 识别与备份", "2 · 选择与修改", "3 · 校验与写入", "任务与恢复"}};
    private static final String[][] SUBTITLES = {
        {"", "分类浏览实际声音", "最多 5 个 · ZIP 备份", "应用启动时执行", "试听、音量与恢复"},
        {"先识别，再导出备份", "选择镜像，再生成副本", "保留备份并全量回读", "写后回读与恢复"}};
    private static final int[][] ICONS = {
        {R.drawable.ic_ui_link, R.drawable.ic_ui_audio, R.drawable.ic_ui_lock, R.drawable.ic_ui_save, R.drawable.ic_ui_flask},
        {R.drawable.ic_ui_board, R.drawable.ic_ui_disk, R.drawable.ic_ui_warn, R.drawable.ic_ui_log}};
    private static final int[][] COLORS = {
        {0xFF2D6BFF, 0xFF16A07A, 0xFF7A5AF8, 0xFFF28C28, 0xFFE5483C},
        {0xFF2D6BFF, 0xFF16A07A, 0xFFE5483C, 0xFF7A5AF8}};
    private static final String[] PAGE_NAMES = {"仪表提示音", "仪表工坊"};

    private AppState state;
    private Ui ui;
    private int page = 1; // Workshop is the first, default tab; audio retains its internal page ID.
    private final int[] section = new int[2];
    private final Map<String, String> drafts = new HashMap<>();

    private FrameLayout detailHost;
    private final Button[] segButtons = new Button[2];
    private final LinearLayout[] navLists = new LinearLayout[2];
    private final View[][] navItems = {new View[5], new View[4]};
    private final TextView[][] navSub = {new TextView[5], new TextView[4]};
    private final View[][] panes = {new View[5], new View[4]};
    private TextView statusTitle, statusText, progressText, taskState, progressPercent, permissionText, cpAddressText;
    private View statusDot;
    private Ui.ProgressTrack progress;

    private EditText password;
    private Ui.Toggle enableSsh, clearRoot;
    private TextView fingerprintChip;
    private SoundPanel soundPanel;
    private LinearLayout imageList;
    private TextView slotText, deviceText, selectedText, dangerInfo;
    private View writeRow;
    private String saveImageId = "";
    private MediaPlayer player;
    private AudioFocusRequest previewFocus;
    private int previewFocusResult = -1;
    private Runnable previewDone;
    private LocalFilePicker filePicker;
    private final Handler clock = new Handler(Looper.getMainLooper());
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {updateStatus(); clock.postDelayed(this, 1000);}
    };

    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {updateStatus(); updateConnection(); if (!state.busy.get()) {refreshLibrary(); if (soundPanel != null) soundPanel.reload();}}
    };

    @Override protected void attachBaseContext(Context base) {super.attachBaseContext(I18n.localized(base));}

    // Before API 33 the same-app signature permission protects this receiver.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        state = (AppState) getApplication();
        section[0] = SshAccess.trusted(this) ? 1 : 0;
        if (bundle != null) {page = bundle.getInt("workshopPage", 1); int[] savedSections = bundle.getIntArray("workshopSections"); if (savedSections != null && savedSections.length == 2) System.arraycopy(savedSections, 0, section, 0, 2); Bundle savedDrafts = bundle.getBundle("workshopDrafts"); if (savedDrafts != null) for (String key : savedDrafts.keySet()) drafts.put(key, savedDrafts.getString(key, ""));}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, new IntentFilter("com.kooo.evdashboard.STATUS"), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updates, new IntentFilter("com.kooo.evdashboard.STATUS"), getPackageName() + ".INTERNAL", null);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 90);
        render(getResources().getConfiguration());
        clock.post(state::startupAudio);
    }

    @Override protected void onSaveInstanceState(Bundle out) {out.putInt("workshopPage", page); out.putIntArray("workshopSections", section.clone()); Bundle saved = new Bundle(); drafts.forEach(saved::putString); out.putBundle("workshopDrafts", saved); super.onSaveInstanceState(out);}

    @Override protected void onResume() {super.onResume(); clock.removeCallbacks(progressTick); clock.post(progressTick); updatePermissions(); try {state.storage(); refreshLibrary();} catch (Exception ignored) {}}
    @Override protected void onPause() {clock.removeCallbacks(progressTick); stopPreview(); super.onPause();}
    @Override protected void onDestroy() {clock.removeCallbacks(progressTick); if (filePicker != null) filePicker.close(); unregisterReceiver(updates); stopPreview(); if (soundPanel != null) soundPanel.close(); super.onDestroy();}
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {super.onConfigurationChanged(config); render(config);}

    // ---------------------------------------------------------------- layout

    private void render(android.content.res.Configuration config) {
        if (filePicker != null) {filePicker.close(); filePicker = null;}
        if (soundPanel != null) soundPanel.close();
        ui = new Ui(this, config);
        View root = ui.compact ? compactRoot() : wideRoot();
        root.setBackgroundColor(ui.bg);
        setContentView(root);
        applySystemBars();
        select(page, section[page]);
        updateConnection();
        updatePermissions();
        updateStatus();
        refreshLibrary();
    }

    private void applySystemBars() {
        Window w = getWindow();
        w.setStatusBarColor(ui.bg);
        w.setNavigationBarColor(ui.bg);
        WindowInsetsController c = w.getInsetsController();
        if (c != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            c.setSystemBarsAppearance(ui.night ? 0 : light, light);
        }
    }

    private View wideRoot() {
        LinearLayout root = ui.horizontal();
        LinearLayout master = ui.vertical();
        master.setPadding(ui.px(40), ui.px(30), ui.px(28), ui.px(28));
        master.addView(ui.text("仪表工坊", 50, ui.ink, true), ui.lp(Ui.MATCH, Ui.WRAP, ui.px(8), 0, 0, ui.px(20)));
        master.addView(segment(), ui.lp(Ui.MATCH, ui.px(104), 0, 0, 0, ui.px(20)));
        ScrollView navScroll = new ScrollView(this);
        navScroll.setVerticalScrollBarEnabled(false);
        navScroll.addView(navFrame());
        master.addView(navScroll, ui.lp(Ui.MATCH, 0, 1));
        master.addView(sidebarFooter(), ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(18), 0, 0));
        root.addView(master, ui.lp(ui.px(700), Ui.MATCH));
        root.addView(detailColumn(), ui.lp(0, Ui.MATCH, 1));
        return root;
    }

    private View compactRoot() {
        LinearLayout root = ui.vertical();
        root.setPadding(ui.px(32), ui.px(20), ui.px(32), ui.px(20));
        LinearLayout head = ui.horizontal();
        head.addView(ui.text("仪表工坊", 50, ui.ink, true), ui.lp(Ui.WRAP, Ui.WRAP, 0, 0, ui.px(32), 0));
        head.addView(segment(), ui.lp(0, ui.px(104), 1));
        root.addView(head, ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, ui.px(16)));
        HorizontalScrollView nav = new HorizontalScrollView(this);
        nav.setHorizontalScrollBarEnabled(false);
        nav.addView(navFrame());
        root.addView(nav, ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, ui.px(16)));
        root.addView(detailColumn(), ui.lp(Ui.MATCH, 0, 1));
        root.addView(sidebarFooter(), ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(12), 0, 0));
        return root;
    }

    private View sidebarFooter() {
        LinearLayout footer = ui.vertical(); footer.setTag("sidebar-footer");
        LinearLayout warning = ui.vertical(); warning.setTag("permanent-risk-warning");
        warning.setBackground(ui.rect(ui.warnBg, 24, ui.warnLine, 2)); warning.setPadding(ui.px(22), ui.px(18), ui.px(22), ui.px(18));
        LinearLayout heading = ui.horizontal(); heading.addView(ui.icon(R.drawable.ic_ui_warn, ui.warn, 28), ui.lp(ui.px(28), ui.px(28), 0, 0, ui.px(10), 0));
        heading.addView(ui.text(getString(R.string.risk_title), 26, ui.warn, true)); warning.addView(heading);
        warning.addView(ui.text(getString(R.string.risk_body), 23, ui.warn, false), ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(8), 0, 0));
        footer.addView(warning);
        LinearLayout language = ui.horizontal(); language.setPadding(ui.px(4), ui.px(14), 0, 0);
        language.addView(ui.text(getString(R.string.language_label), 25, ui.muted, false), ui.lp(0, Ui.WRAP, 1));
        Button switchLanguage = ui.button(I18n.literal(I18n.english(this) ? "中文" : "English"), Ui.Look.TINT, v -> {
            if (state.busy.get()) {error("请等待任务完成后切换语言"); return;}
            prefs().edit().putString("uiLanguage", I18n.english(this) ? "zh" : "en").commit(); recreate();
        });
        switchLanguage.setTag("language-switch"); switchLanguage.setContentDescription(I18n.literal("中文 / English"));
        language.addView(switchLanguage, ui.lp(Ui.WRAP, Math.max(ui.px(78), Math.round(48 * getResources().getDisplayMetrics().density))));
        footer.addView(language); return footer;
    }

    private View detailColumn() {
        LinearLayout col = ui.vertical();
        if (!ui.compact) col.setPadding(ui.px(12), ui.px(30), ui.px(40), ui.px(24));
        detailHost = new FrameLayout(this);
        View[] audio = audioPanes(), workshop = workshopPanes();
        for (int i = 0; i < audio.length; i++) {panes[0][i] = audio[i]; detailHost.addView(audio[i], new FrameLayout.LayoutParams(-1, -1));}
        for (int i = 0; i < workshop.length; i++) {panes[1][i] = workshop[i]; detailHost.addView(workshop[i], new FrameLayout.LayoutParams(-1, -1));}
        col.addView(detailHost, ui.lp(Ui.MATCH, 0, 1));
        col.addView(taskBar(), ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(22), 0, 0));
        return col;
    }

    private View segment() {
        LinearLayout seg = ui.horizontal();
        seg.setBackground(ui.rect(ui.segment, 32, 0, 0));
        seg.setPadding(ui.px(8), ui.px(8), ui.px(8), ui.px(8));
        for (int display = 0; display < 2; display++) {
            final int i = 1 - display, p = i;
            Button b = new I18n.Action(this);
            b.setText(I18n.english(this) ? (i == 1 ? "Workshop" : "Instrument Sounds") : PAGE_NAMES[i]);
            b.setAllCaps(false);
            b.setStateListAnimator(null);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.px(29));
            b.setOnClickListener(v -> select(p, section[p]));
            segButtons[i] = b;
            seg.addView(b, ui.lp(0, Ui.MATCH, 1));
        }
        return seg;
    }

    private View navFrame() {
        FrameLayout frame = new FrameLayout(this);
        for (int p = 0; p < 2; p++) {
            LinearLayout list = ui.compact ? ui.horizontal() : ui.vertical();
            for (int s = 0; s < TITLES[p].length; s++) {
                View item = ui.compact ? navPill(p, s) : navItem(p, s);
                navItems[p][s] = item;
                list.addView(item, ui.compact ? ui.lp(Ui.WRAP, ui.px(88), 0, 0, ui.px(14), 0) : ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, ui.px(14)));
            }
            navLists[p] = list;
            frame.addView(list, new FrameLayout.LayoutParams(ui.compact ? -2 : -1, -2));
        }
        return frame;
    }

    private View navItem(int p, int s) {
        LinearLayout item = ui.horizontal();
        item.setMinimumHeight(ui.px(132));
        item.setPadding(ui.px(26), 0, ui.px(26), 0);
        item.setClickable(true);
        item.setFocusable(true);
        FrameLayout box = new FrameLayout(this);
        box.setBackground(ui.rect(COLORS[p][s], 22, 0, 0));
        ImageView icon = ui.icon(ICONS[p][s], 0xFFFFFFFF, 44);
        box.addView(icon, new FrameLayout.LayoutParams(ui.px(44), ui.px(44), Gravity.CENTER));
        item.addView(box, ui.lp(ui.px(76), ui.px(76), 0, 0, ui.px(26), 0));
        LinearLayout texts = ui.vertical();
        texts.addView(ui.text(TITLES[p][s], 31, ui.ink, true));
        TextView sub = ui.text(SUBTITLES[p][s], 23, ui.muted, false);
        sub.setSingleLine();
        sub.setEllipsize(TextUtils.TruncateAt.END);
        navSub[p][s] = sub;
        texts.addView(sub);
        item.addView(texts, ui.lp(0, Ui.WRAP, 1));
        item.addView(ui.text("›", 40, ui.muted, false));
        item.setOnClickListener(v -> select(p, s));
        return item;
    }

    private View navPill(int p, int s) {
        TextView t = ui.text(TITLES[p][s], 28, ui.ink, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(ui.px(30), 0, ui.px(30), 0);
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(v -> select(p, s));
        navSub[p][s] = new I18n.Label(this);
        return t;
    }

    private void styleNav(int p, int s, boolean selected) {
        View item = navItems[p][s];
        item.setBackground(ui.interactive(selected ? ui.surface : 0, ui.compact ? 44 : 30, 0));
        item.setElevation(selected ? ui.px(6) : 0);
        if (ui.compact) ((TextView) item).setTextColor(selected ? ui.accent : ui.muted);
    }

    private void select(int p, int s) {
        page = p;
        section[p] = s;
        for (int i = 0; i < 2; i++) {
            boolean on = i == p;
            segButtons[i].setTextColor(on ? ui.ink : ui.muted);
            segButtons[i].setBackground(on ? ui.rect(ui.surface, 26, 0, 0) : ui.interactive(0, 26, 0));
            navLists[i].setVisibility(on ? View.VISIBLE : View.GONE);
            for (int j = 0; j < TITLES[i].length; j++) {
                boolean sel = on && j == section[i];
                styleNav(i, j, sel);
                panes[i][j].setVisibility(sel ? View.VISIBLE : View.GONE);
            }
        }
        if (p == 0 && s == 1 && soundPanel != null) soundPanel.onVisible();
    }

    private View taskBar() {
        LinearLayout container = ui.vertical(); container.setTag("task-panel");
        container.setPadding(ui.px(28), ui.px(18), ui.px(28), ui.px(18));
        container.setBackground(ui.interactive(ui.surface, 28, ui.line)); container.setClickable(true); container.setFocusable(true);
        container.setOnClickListener(v -> {
            TaskProgress.Snapshot snapshot = state.progress.snapshot();
            UiDialog dialog = new UiDialog(this, ui, state.title).compact(1280).message(state.status);
            if (!state.title.equals("就绪")) dialog.message(snapshot.summary());
            dialog.positive("关闭", Ui.Look.TINT, null).show();
        });
        LinearLayout header = ui.horizontal();
        statusDot = new View(this); GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); statusDot.setBackground(dot);
        header.addView(statusDot, ui.lp(ui.px(14), ui.px(14), 0, 0, ui.px(16), 0));
        statusTitle = ui.text("", 29, ui.ink, true); statusTitle.setSingleLine(); statusTitle.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(statusTitle, ui.lp(0, Ui.WRAP, 1));
        taskState = ui.text("", 22, ui.accent, true); taskState.setTag("task-state"); taskState.setGravity(Gravity.CENTER);
        taskState.setPadding(ui.px(16), ui.px(6), ui.px(16), ui.px(6)); header.addView(taskState, ui.lp(Ui.WRAP, Ui.WRAP, ui.px(16), 0, 0, 0));
        header.addView(ui.text("›", 30, ui.muted, false), ui.lp(Ui.WRAP, Ui.WRAP, ui.px(16), 0, 0, 0)); container.addView(header);
        statusText = ui.text("", 25, ui.muted, false); statusText.setTag("task-message"); statusText.setMaxLines(2); statusText.setEllipsize(TextUtils.TruncateAt.END);
        container.addView(statusText, ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(8), 0, 0));
        LinearLayout metrics = ui.horizontal();
        progressText = ui.text("", 24, ui.muted, false); progressText.setTag("task-timing"); metrics.addView(progressText, ui.lp(0, Ui.WRAP, 1));
        progressPercent = ui.text("", 34, ui.accent, true); progressPercent.setTag("task-percentage"); metrics.addView(progressPercent);
        container.addView(metrics, ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(10), 0, 0));
        progress = new Ui.ProgressTrack(ui); progress.setTag("task-progress");
        container.addView(progress, ui.lp(Ui.MATCH, ui.px(14), 0, ui.px(12), 0, ui.px(2)));
        return container;
    }

    private void updateStatus() {
        if (statusText == null) return;
        boolean busy = state.busy.get(), idle = state.title.equals("就绪") && !busy;
        TaskProgress.Snapshot snapshot = state.progress.snapshot();
        int color = idle ? ui.muted : state.failed ? ui.danger : busy ? ui.accent : ui.ok;
        statusTitle.setText(state.title); statusTitle.setTextColor(ui.ink);
        statusText.setText(state.status); statusText.setTextColor(state.failed ? ui.danger : ui.muted);
        ((GradientDrawable) statusDot.getBackground()).setColor(color);
        taskState.setVisibility(idle ? View.GONE : View.VISIBLE);
        taskState.setText(idle ? "待命" : busy ? "进行中" : state.failed ? "未完成" : "已完成");
        taskState.setTextColor(color); taskState.setBackground(ui.rect(idle ? ui.surface2 : state.failed ? ui.dangerBg : busy ? ui.accentTint : ui.okBg, 12, 0, 0));
        progress.setVisibility(busy ? View.VISIBLE : View.GONE); progress.percent(snapshot.percent());
        progressPercent.setVisibility(busy && snapshot.percent() >= 0 ? View.VISIBLE : View.GONE);
        progressPercent.setText(snapshot.percent() + "%");
        progressText.setText(idle ? "" : snapshot.timing());
        ((View) progressText.getParent()).setVisibility(idle ? View.GONE : View.VISIBLE);
    }

    // ---------------------------------------------------------------- pane scaffold

    private final class Pane {
        final LinearLayout root = ui.vertical(), header = ui.horizontal(), content = ui.vertical(), footer = ui.horizontal();

        Pane(int p, int s) {this(p, s, TITLES[p][s], false);}
        Pane(int p, int s, String title, boolean narrow) {
            root.setBackground(ui.rect(ui.surface, 44, 0, 0));
            root.setPadding(ui.px(narrow ? 28 : 44), ui.px(36), ui.px(narrow ? 28 : 44), ui.px(32));
            header.addView(ui.icon(ICONS[p][s], COLORS[p][s], narrow ? 38 : 48), ui.lp(ui.px(narrow ? 38 : 48), ui.px(narrow ? 38 : 48), 0, 0, ui.px(20), 0));
            header.addView(ui.text(title, narrow ? 36 : 42, ui.ink, true), ui.lp(0, Ui.WRAP, 1));
            root.addView(header, ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, ui.px(20)));
            ScrollView scroll = new ScrollView(MainActivity.this);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(content);
            root.addView(scroll, ui.lp(Ui.MATCH, 0, 1));
            root.addView(footer, ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(20), 0, 0));
            footer.setVisibility(View.GONE);
        }

        void add(View v) {content.addView(v, ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, ui.px(18)));}

        void caption(String s) {add(ui.text(s, 23, ui.muted, true));}

        void note(String s) {add(ui.text(s, 26, ui.muted, false));}

        /** Footer buttons: secondary on the left, the main action on the right. */
        void footer(Button left, Button right) {
            footer.setVisibility(View.VISIBLE);
            if (left != null) footer.addView(left);
            footer.addView(new View(MainActivity.this), ui.lp(0, 1, 1));
            if (right != null) footer.addView(right);
        }
    }

    private View.OnClickListener click(Runnable r) {return v -> {try {r.run();} catch (Exception e) {error(e.getMessage());}};}

    private EditText field(ViewGroup parent, String key, String label, String initial, int type, String placeholder, String unit, float weight) {
        EditText e = ui.field(parent, label, drafts.containsKey(key) ? drafts.get(key) : initial, type, placeholder, unit, weight);
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {drafts.put(key, s.toString());}
        });
        return e;
    }

    private Ui.SwitchRow toggleRow(String key, String label, String hint, boolean initial) {
        Ui.SwitchRow row = ui.switchRow(label, hint, "1".equals(drafts.getOrDefault(key, initial ? "1" : "0")));
        row.toggle.setOnCheckedChangeListener((b, on) -> drafts.put(key, on ? "1" : "0"));
        return row;
    }

    // ---------------------------------------------------------------- audio panes

    private String pass() {return password == null ? "" : password.getText().toString();}
    private AudioEngine audio() throws Exception {return new AudioEngine(this, state::report);}
    private SharedPreferences prefs() {return getSharedPreferences("settings", 0);}

    private View[] audioPanes() {
        // 0 · connection
        Pane link = new Pane(0, 0);
        fingerprintChip = ui.chip("", Ui.Tone.NEUTRAL);
        link.header.addView(fingerprintChip);
        link.note("通过 CP SSH 临时覆盖资源，不刷写固件。默认不修改任何声音。");
        LinearLayout g = ui.group();
        cpAddressText = ui.mono("", 32, ui.ink);
        ui.addRow(g, ui.infoRow(cpAddressText));
        ui.addRow(g, ui.actionRow("修改 CP IP 地址", Ui.Tone.ACCENT, click(this::editCpAddress)));
        ui.divide(g);
        password = field(g, "password", "SSH 密码（留空即空密码；不保存）", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, "留空 = 空密码", "", 0);
        ui.addRow(g, ui.actionRow("检测并确认 SSH 主机指纹", Ui.Tone.NEUTRAL, click(() -> task("检测 SSH 主机身份", () -> {
            SshAccess.KeyOffer offer = SshAccess.inspectKey(this);
            runOnUiThread(() -> confirm("确认 CP 主机指纹", "地址：" + offer.host + "\n请确认这是车内 CP。重启后密钥变化也需要重新确认。\n\n" + offer.fingerprint, () -> {
                if (!offer.host.equals(SshAccess.host(this))) {error("CP 地址已变化，请重新检测主机指纹"); return;}
                prefs().edit().putString("sshKnown", offer.host + " " + offer.type + " " + offer.key).putBoolean("sshKeyChanged", false).apply();
                state.report("已保存当前 CP 主机指纹，后续严格匹配。");
                updateConnection();
            }));
        }))));
        link.add(g);
        link.footer(ui.button("恢复旧版覆盖", Ui.Look.TINT, click(() -> {String p = pass(); parked("恢复旧版音效覆盖", "恢复本应用旧版 AVAS、锁车及通用提示音的临时覆盖。", () -> task("恢复旧版音效覆盖", () -> audio().restoreLegacy(p)));})),
            ui.button("读取实际提示音", Ui.Look.PRIMARY, click(() -> {if (soundPanel != null) {soundPanel.refresh(); select(0, 1);}})));
        try {
            soundPanel = new SoundPanel(this, ui, state, this::pass);
            return new View[]{link.root, soundPanel.directory(), soundPanel.presets(), soundPanel.automatic(), soundPanel.help()};
        } catch (Exception e) {
            soundPanel = null;
            View[] result = new View[5]; result[0] = link.root;
            for (int i = 1; i < 5; i++) result[i] = ui.text("声音目录未加载：" + e.getMessage(), 30, ui.danger, false);
            return result;
        }
    }

    private void updateConnection() {
        if (fingerprintChip == null) return;
        boolean known = SshAccess.trusted(this);
        ui.setChip(fingerprintChip, known ? "指纹已确认" : "指纹未确认", known ? Ui.Tone.OK : Ui.Tone.WARN);
        cpAddressText.setText(SshAccess.host(this) + " : 22  ·  root");
        navSub[0][0].setText(SshAccess.host(this) + " · " + (known ? "指纹已确认" : "指纹未确认"));
    }

    // ---------------------------------------------------------------- workshop panes

    private View[] workshopPanes() {
        // 0 · device
        Pane device = new Pane(1, 0);
        device.note("操作顺序：① 识别并备份当前分区 → ② 选择镜像、勾选修改并生成副本 → ③ 复核并写入。导入外部镜像也不能跳过当前分区备份。");
        LinearLayout dg = ui.group();
        LinearLayout slot = ui.horizontal();
        slotText = ui.mono("—", 84, ui.accent);
        slotText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        slot.addView(slotText, ui.lp(Ui.WRAP, Ui.WRAP, 0, 0, ui.px(32), 0));
        deviceText = ui.text("尚未识别 CP 分区及当前槽位", 28, ui.ink, false);
        slot.addView(deviceText, ui.lp(0, Ui.WRAP, 1));
        ui.addRow(dg, ui.infoRow(slot));
        permissionText = ui.text("", 26, ui.muted, false);
        ui.addRow(dg, ui.infoRow(permissionText));
        ui.addRow(dg, ui.actionRow("前往 EVCC 授权本应用权限", Ui.Tone.ACCENT, click(this::openPermissions)));
        ui.addRow(dg, ui.actionRow("检查权限并识别当前 CP 槽位", Ui.Tone.ACCENT, click(() -> withStorage("识别 CP 分区", () -> state.workshop.identify()))));
        device.add(dg);
        device.note("镜像库：当前 Android 用户的 Documents/EVDashboard。卸载应用不会主动删除此目录；请另存重要备份到独立介质。");
        device.footer(ui.button("已有镜像 → 进入第 2 步", Ui.Look.TINT, click(() -> select(1, 1))), ui.button("识别并导出完整备份", Ui.Look.PRIMARY, click(this::beginExport)));

        // 1 · library
        Pane library = new Pane(1, 1, "选择镜像", true);
        Pane editor = new Pane(1, 1, "配置与操作", true);
        library.root.setTag("image-selection"); editor.root.setTag("image-configuration");
        library.note("选择来源镜像，在右侧查看状态、调整配置并生成副本。");
        editor.note("修改会生成独立副本。选中镜像后仍需在第 3 步复核写入。");
        Button importImage = ui.button("从本机 / U 盘导入镜像", Ui.Look.TINT, click(() -> pick(11, "application/octet-stream")));
        importImage.setMinimumHeight(ui.px(96));
        library.add(importImage);
        library.caption("镜像库 · 选择一份来源镜像");
        imageList = ui.group();
        library.add(imageList);
        editor.caption("所选镜像");
        LinearLayout info = ui.group();
        selectedText = ui.text("请先选择一份镜像", 26, ui.ink, false);
        ui.addRow(info, ui.infoRow(selectedText));
        editor.add(info);
        editor.caption("需要进行的修改（可多选）");
        LinearLayout options = ui.group();
        Ui.SwitchRow ssh = toggleRow("enableSsh", "开启 SSH 开机监听（22 端口）", "", false);
        Ui.SwitchRow blank = toggleRow("clearRoot", "清除 root 密码，允许空密码登录", "", false);
        enableSsh = ssh.toggle;
        clearRoot = blank.toggle;
        ui.addRow(options, ssh.view);
        ui.addRow(options, blank.view);
        editor.add(options);
        editor.add(ui.callout("注意：", "空密码会让能够访问此 SSH 入口的一方获得 root 权限。锁定账号或未知认证配置不会被静默改写。", Ui.Tone.WARN));
        LinearLayout actions = ui.group();
        ui.addRow(actions, ui.actionRow("重新读取并校验所选镜像", Ui.Tone.ACCENT, click(() -> withStorage("重新校验镜像", () -> {ImageRecord r = chosen(); state.workshop.verifyGenerated(r); state.report(r.message);}))));
        ui.addRow(actions, ui.actionRow("确认镜像分类（仅待分类导出）", Ui.Tone.NEUTRAL, click(() -> {try {classify(chosen());} catch (Exception e) {error(e.getMessage());}})));
        ui.addRow(actions, ui.actionRow("另存所选镜像到文件 / U 盘", Ui.Tone.NEUTRAL, click(() -> {
            try {
                ImageRecord r = chosen();
                saveImageId = r.id;
                saveLocally(14, "instrument-" + r.id + ".img");
            } catch (Exception e) {error(e.getMessage());}
        })));
        ui.addRow(actions, ui.actionRow("刷新镜像存储列表", Ui.Tone.NEUTRAL, click(this::refreshLibrary)));
        ui.addRow(actions, ui.actionRow("删除所选镜像（保留备份保护）", Ui.Tone.DANGER, click(this::deleteSelected)));
        editor.add(actions);
        editor.footer.setOrientation(LinearLayout.VERTICAL); editor.footer.setGravity(Gravity.FILL_HORIZONTAL);
        editor.footer.setVisibility(View.VISIBLE);
        editor.footer.addView(ui.button("无需修改 → 第 3 步", Ui.Look.TINT, click(() -> {try {chosen(); select(1, 2);} catch (Exception e) {error(e.getMessage());}})), ui.lp(Ui.MATCH, ui.px(88)));
        editor.footer.addView(ui.button("生成新的修改镜像", Ui.Look.PRIMARY, click(() -> {
            boolean sshOn = enableSsh.isChecked(), blankOn = clearRoot.isChecked();
            if (!sshOn && !blankOn) {error("请先勾选需要进行的修改；如果无需修改，可直接进入第 3 步复核。"); return;}
            withStorage("生成镜像", () -> {
                ImageRecord r = chosen();
                ImageRecord result = state.workshop.generate(r, sshOn, blankOn);
                state.selected = result.id;
                state.report("新镜像生成并完整复核通过，原文件未改变；尚未刷写。");
                runOnUiThread(() -> {refreshLibrary(); select(1, 2);});
            });
        })), ui.lp(Ui.MATCH, ui.px(96), 0, ui.px(12), 0, 0));
        LinearLayout imageWorkspace = ui.compact ? ui.vertical() : ui.horizontal(); imageWorkspace.setTag("image-workspace");
        imageWorkspace.addView(library.root, ui.compact ? ui.lp(Ui.MATCH, 0, 1) : ui.lp(0, Ui.MATCH, 1));
        LinearLayout.LayoutParams editorLayout = ui.compact ? ui.lp(Ui.MATCH, 0, 1) : ui.lp(0, Ui.MATCH, 1.1f);
        if (ui.compact) editorLayout.topMargin = ui.px(18); else editorLayout.leftMargin = ui.px(20);
        imageWorkspace.addView(editor.root, editorLayout);

        // 2 · write and delete
        Pane danger = new Pane(1, 2);
        danger.add(ui.callout("风险：", "校验通过不能保证零风险或 A/B 自动回退。写入可能导致仪表损坏、需要售后自费维修；必须保持驻车、供电稳定、应用前台且无 OTA。", Ui.Tone.DANGER));
        LinearLayout current = ui.group();
        dangerInfo = ui.text("请先在第 2 步“选择与修改”中选择一份镜像", 28, ui.ink, false);
        ui.addRow(current, ui.infoRow(dangerInfo));
        danger.caption("所选镜像");
        danger.add(current);
        danger.caption("先复核，再写入（每次写入仍会重新核验）");
        LinearLayout dg2 = ui.group();
        ui.addRow(dg2, ui.actionRow("重新读取并校验所选镜像", Ui.Tone.ACCENT, click(() -> withStorage("重新校验镜像", () -> {ImageRecord r = chosen(); state.workshop.verifyGenerated(r); state.report(r.message);}))));
        writeRow = ui.actionRow("将所选镜像写入当前 CP 槽位", Ui.Tone.DANGER, click(this::confirmFlash));
        ui.addRow(dg2, writeRow);
        danger.add(dg2);
        danger.footer(ui.button("← 返回第 2 步选镜像", Ui.Look.TINT, click(() -> select(1, 1))), null);

        // 3 · tasks and recovery
        Pane audit = new Pane(1, 3);
        audit.note("生成检查、写后回读、启动验收是三个不同阶段。写入不自动重启，也不自动切槽。保留 A/B 不等于保证自动回退。");
        LinearLayout ag = ui.group();
        ui.addRow(ag, ui.actionRow("检查未完成刷写任务（不会重新写入）", Ui.Tone.ACCENT, click(() -> withStorage("核验未完成任务", () -> state.workshop.recoverPending()))));
        ui.addRow(ag, ui.actionRow("导出本次诊断记录", Ui.Tone.NEUTRAL, click(() -> {
            saveLocally(15, "instrument-workshop-report-" + System.currentTimeMillis() + ".txt");
        })));
        ui.addRow(ag, ui.actionRow("版本、数据说明与开源许可", Ui.Tone.NEUTRAL, click(this::licenses)));
        audit.add(ag);

        return new View[]{device.root, imageWorkspace, danger.root, audit.root};
    }

    private ImageRecord chosen() throws Exception {
        state.storage();
        FilesEx.require(!state.selected.isEmpty(), "请先选择镜像");
        return state.library.get(state.selected);
    }

    private void refreshLibrary() {
        if (imageList == null) return;
        DeviceInfo device = state.workshop == null ? null : state.workshop.device;
        slotText.setText(device == null ? "" : device.slot);
        slotText.setVisibility(device == null ? View.GONE : View.VISIBLE);
        deviceText.setText(device == null ? "尚未识别 CP 分区及当前槽位" : device.summary());
        navSub[1][0].setText(device == null ? "尚未识别" : device.slot + " · " + device.block);
        if (state.library == null) return;
        try {
            imageList.removeAllViews();
            List<ImageRecord> records = state.library.list();
            if (records.isEmpty()) ui.addRow(imageList, ui.infoRow(ui.text("暂无镜像。先导出分区或导入文件。", 28, ui.muted, false)));
            for (ImageRecord r : records) ui.addRow(imageList, imageRow(r));
            navSub[1][1].setText(records.size() + " 份镜像" + (state.selected.isEmpty() ? "" : " · 已选中 1"));
            if (state.selected.isEmpty()) {
                selectedText.setText("请先选择一份镜像");
                dangerInfo.setText("请先在第 2 步“选择与修改”中选择一份镜像");
                writeRow.setAlpha(.5f);
            } else {
                ImageRecord r = state.library.get(state.selected);
                selectedText.setText(I18n.literal(r.name) + "\n" + r.categoryLabel() + "\nSSH 配置：" + sshLabel(r.socket) + "  / root 密码：" + passwordLabel(r.password) + "\n允许空密码参数：" + (r.blankAllowed ? "是" : "否 / 未知")
                    + "\n固件基线：" + (r.build.isEmpty() ? "未知" : r.build.substring(0, 12)) + "\nSHA-256：" + r.sha + "\n" + r.message);
                dangerInfo.setText(I18n.literal(r.name) + "\n" + r.categoryLabel() + "\n" + (r.validated ? "结构已校验，可进入写入确认；仍需确认本机备份和兼容性" : "尚未通过结构校验：写入已锁定，请先重新校验"));
                writeRow.setAlpha(r.validated ? 1f : .5f);
            }
        } catch (Exception e) {
            selectedText.setText("镜像库读取失败：" + e.getMessage());
        }
    }

    private View imageRow(ImageRecord r) {
        boolean selected = r.id.equals(state.selected);
        LinearLayout row = ui.horizontal();
        row.setMinimumHeight(ui.px(164));
        row.setPadding(ui.px(20), ui.px(20), ui.px(20), ui.px(20));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(ui.interactive(selected ? ui.accentTint : 0, 0, 0));
        row.addView(ui.radio(selected), ui.lp(ui.px(32), ui.px(32), 0, 0, ui.px(18), 0));
        LinearLayout col = ui.vertical();
        TextView name = ui.text(I18n.literal(r.name), 28, ui.ink, true); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END); col.addView(name);
        LinearLayout meta = ui.horizontal();
        Ui.Tone tone = switch (r.category) {case "ORIGINAL" -> Ui.Tone.OK; case "MODIFIED" -> Ui.Tone.ACCENT; case "IMPORTED" -> Ui.Tone.NEUTRAL; default -> Ui.Tone.WARN;};
        meta.addView(ui.chip(r.categoryLabel(), tone), ui.lp(Ui.WRAP, ui.px(44), 0, ui.px(10), ui.px(12), 0));
        if (r.original()) meta.addView(ui.chip("删除受保护", Ui.Tone.WARN), ui.lp(Ui.WRAP, ui.px(44), 0, ui.px(10), 0, 0));
        col.addView(meta);
        TextView facts = ui.text((r.slot.isEmpty() ? "未绑定槽位" : r.slot) + "  ·  " + (r.bytes / (1024 * 1024)) + " MiB  ·  " + (r.validated ? "结构已校验" : "待检查 / 检查未通过"), 22, r.validated ? ui.ok : ui.warn, false);
        col.addView(facts, ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(10), 0, 0));
        row.addView(col, ui.lp(0, Ui.WRAP, 1));
        row.setOnClickListener(v -> {if (state.busy.get()) {error("任务进行中，暂不能更换所选镜像"); return;} state.selected = r.id; refreshLibrary();});
        return row;
    }

    // ---------------------------------------------------------------- dialogs

    void error(String s) {
        new UiDialog(this, ui, "操作未开始").message(s == null ? "未知错误" : s).positive("知道了", Ui.Look.PRIMARY, null).show();
    }

    void task(String label, AppState.Job job) {try {state.submit(label, job);} catch (Exception e) {error(e.getMessage());}}
    private void withStorage(String label, AppState.Job job) {
        if (!requireStorage()) return;
        try {state.storage(); task(label, job);} catch (Exception e) {error(e.getMessage());}
    }

    private void updatePermissions() {
        if (permissionText == null) return;
        boolean granted = Environment.isExternalStorageManager();
        permissionText.setText(granted ? "文件管理权限：已授权 · 可使用本机 / U 盘文件选择器" : "文件管理权限：未授权 · 请前往 EVCC 授予“所有文件访问权限”");
        permissionText.setTextColor(granted ? ui.ok : ui.warn);
    }

    boolean requireStorage() {
        if (Environment.isExternalStorageManager()) return true;
        new UiDialog(this, ui, "需要文件管理权限")
            .message("用于保存完整分区备份、读取本机 / U 盘镜像及音频。请到 EVCC 的仪表工坊权限页授予“所有文件访问权限”。返回后会自动检查，但不会自动开始导出或写入，请重新点击操作。")
            .negative("稍后", null).positive("前往 EVCC 授权", Ui.Look.PRIMARY, this::openPermissions).show();
        return false;
    }

    private void openPermissions() {
        if (state.busy.get()) {error("请等待当前任务结束后再离开应用授权"); return;}
        Intent intent = new Intent(Intent.ACTION_VIEW, new Uri.Builder().scheme("evcc").authority("permissions").path("/app").appendQueryParameter("package", getPackageName()).build()).setPackage("com.kooo.evcc");
        try {startActivity(intent);}
        catch (ActivityNotFoundException | SecurityException e) {
            new UiDialog(this, ui, "未找到可用的 EVCC 授权入口")
                .message("请安装或更新支持该入口的 EVCC。也可以尝试系统权限页；若车机裁剪了系统设置，需要使用 EVCC 授权。不会假定跳转成功就已经获得权限。")
                .negative("返回", null).positive("尝试系统权限页", Ui.Look.TINT, () -> {
                    try {startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));}
                    catch (ActivityNotFoundException | SecurityException unavailable) {error("车机没有可用的系统权限页面，请在 EVCC 中授权仪表工坊的所有文件访问权限。");}
                }).show();
        }
    }

    private void editCpAddress() {
        if (state.busy.get()) {error("当前任务进行中，不能更改 CP 地址"); return;}
        LinearLayout content = ui.vertical();
        EditText address = ui.field(content, "CP IPv4 地址", SshAccess.host(this), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, CpAddress.DEFAULT, "", 0);
        new UiDialog(this, ui, "修改 CP 连接地址").view(content)
            .message("仅影响仪表提示音的 SSH 连接；端口 22，用户 root。不会改变本机分区识别或写入目标。地址变化后需要重新确认指纹，自动覆盖会关闭。")
            .negative("取消", null).positive("保存地址", Ui.Look.PRIMARY, () -> {
                try {
                    FilesEx.require(!state.busy.get(), "已有任务运行，请结束后再修改");
                    String host = CpAddress.parse(address.getText().toString());
                    if (!host.equals(SshAccess.host(this))) prefs().edit().putString("cpHost", host).remove("sshKnown").putBoolean("sshKeyChanged", false).putBoolean("reapply", false).putBoolean("soundAutoApply", false).apply();
                    render(getResources().getConfiguration());
                } catch (Exception e) {error(e.getMessage());}
            }).show();
    }

    private void beginExport() {
        withStorage("只读识别导出目标", () -> {
            DeviceInfo target = state.workshop.identify();
            runOnUiThread(() -> confirm("确认完整备份的目标", target.summary() + "\n\n将只读复制以上当前 CP 分区，不修改车辆。原有文件不覆盖，完成后请选择原始或已修改镜像。请保持驻车、供电稳定且无 OTA。", () -> withStorage("导出完整分区", () -> {
                target.same(state.workshop.identify());
                ImageRecord record = state.workshop.exportPartition();
                state.selected = record.id;
                runOnUiThread(() -> {refreshLibrary(); select(1, 1); classify(record);});
            })));
        });
    }

    private static String sshLabel(String value) {return switch (value) {case "ENABLED" -> "已配置开机监听"; case "DISABLED" -> "未配置开机监听"; default -> "未知 / 不支持（" + value + "）";};}
    private static String passwordLabel(String value) {return switch (value) {case "EMPTY" -> "空密码"; case "SET" -> "已设置"; case "LOCKED" -> "账号已锁定"; default -> "未知 / 不支持（" + value + "）";};}

    private void confirm(String title, String message, Runnable yes) {
        new UiDialog(this, ui, title).message(message).negative("取消", null).positive("确认", Ui.Look.PRIMARY, yes).show();
    }

    void parked(String title, String warning, Runnable yes) {
        LinearLayout l = ui.vertical();
        l.addView(ui.text(warning, 30, ui.ink, false), ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, ui.px(12)));
        Ui.Toggle c = ui.checkRow(l, "车辆已 P 挡驻车，期间不会驾驶", false);
        UiDialog dialog = new UiDialog(this, ui, title).view(l).negative("取消", null).positive("确认应用", Ui.Look.PRIMARY, yes);
        dialog.positiveEnabled(false);
        c.setOnCheckedChangeListener((b, on) -> dialog.positiveEnabled(on));
        dialog.show();
    }

    private void classify(ImageRecord r) {
        if (!r.origin.equals("DEVICE") || !r.category.equals("UNCLASSIFIED")) {error("仅未分类的分区导出需要确认；历史原始记录不能通过改分类绕过保护。"); return;}
        new UiDialog(this, ui, "确认导出镜像类型")
            .message("分类由你确认，不代表软件已鉴定为原厂未修改。检测结果会独立保留。关闭弹窗则保持待分类。")
            .neutral("稍后", null)
            .negative("已经修改过的镜像", () -> setCategory(r, "MODIFIED"))
            .positive("仪表盘原始镜像", Ui.Look.PRIMARY, () -> {
                try {
                    boolean previous = false;
                    for (ImageRecord other : state.library.list()) if (other.original() && other.group().equals(r.group())) previous = true;
                    Runnable save = () -> setCategory(r, "ORIGINAL");
                    if (previous) confirm("原始镜像已保存过，是否再次保存", "确认后保留本次新的独立文件和记录，不覆盖以前的原始镜像。取消则保持待分类。", save);
                    else save.run();
                } catch (Exception e) {error(e.getMessage());}
            }).show();
    }

    private void setCategory(ImageRecord r, String category) {
        withStorage("确认镜像类型", () -> {state.library.classify(r.id, category); state.report("分类已保存，原有镜像未覆盖。");});
    }

    private void deleteSelected() {
        try {
            FilesEx.require(!state.busy.get(), "任务进行中，暂不能删除镜像");
            ImageRecord r = chosen();
            confirm("删除镜像记录？", I18n.literal(r.name) + "\n文件将移入镜像库的 deleted 暂存目录，可人工恢复；不会触碰车辆分区。", () -> {
                if (r.original()) withStorage("检查剩余原始备份", () -> {
                    int remaining = state.library.remainingOriginals(r);
                    FilesEx.require(remaining > 0, "禁止删除最后一份有效原始镜像");
                    runOnUiThread(() -> confirm("再次确认删除原始镜像", "已实际校验，同一车辆、槽位及固件基线仍有 " + remaining + " 份有效原始镜像未删除。\n确定删除所选记录？", () -> performDelete(r.id, 2)));
                });
                else performDelete(r.id, 1);
            });
        } catch (Exception e) {error(e.getMessage());}
    }

    private void performDelete(String id, int count) {
        withStorage("删除镜像记录", () -> {state.workshop.deleteImage(id, count); state.selected = ""; state.report("记录已移出列表，文件保留在 deleted 暂存目录，可人工恢复。");});
    }

    private void confirmFlash() {
        try {
            ImageRecord r = chosen();
            FilesEx.require(r.validated, "请先完成镜像校验");
            LinearLayout l = ui.vertical();
            UiDialog dialog = new UiDialog(this, ui, "写入前必须确认 · " + I18n.literal(r.name)).view(l);
            dialog.heading("风险提示", ui.danger).message("写入镜像可能破坏仪表盘系统，造成仪表故障、车机或远程连接不可用等严重后果，可能必须到售后自费维修。备份、校验通过及保留 A/B 槽位都不能保证恢复成功，也不能消除启动失败风险。请确认您能够承担这些风险。");
            dialog.heading("操作要求", ui.ink).message("全程保持车辆通电和稳定供电，禁止断电、休眠、重启或 OTA 升级。请保持本应用在前台，尽量不要运行其他软件，尤其是高负载任务；不要强行停止应用。\n\n将写入重新识别出的当前 CP 槽位，不是 Android 槽位。完成全量回读前不要离开；本应用不会自动切槽或重启。");
            LinearLayout checks = ui.vertical();
            checks.setPadding(0, ui.px(8), 0, 0);
            Ui.Toggle risk = ui.checkRow(checks, "我已理解并愿意承担损坏及售后自费维修风险", false), parked = ui.checkRow(checks, "车辆 P 挡驻车，期间不会驾驶", false), power = ui.checkRow(checks, "全程保持稳定供电，不休眠、不重启", false), ota = ui.checkRow(checks, "无 OTA / 其他刷写，保持本应用前台运行", false);
            Ui.Toggle sameContent = ui.checkRow(checks, "仅测试：允许与当前分区完全相同的镜像重写（无功能收益，仍有刷写风险）", false);
            dialog.view(checks).negative("取消", null).positive("核验后开始写入", Ui.Look.DANGER, () -> {
                boolean test = sameContent.isChecked();
                Runnable write = () -> withStorage(test ? "同内容受控重写测试" : "受控写入 CP 分区", () -> state.workshop.flash(r, true, true, true, true, test));
                if (test) confirm("再次确认同内容重写测试", "这不是必要升级，不会带来功能变化。即使内容相同，重新写入仍可能因断电或硬件故障损坏仪表，可能需要售后自费维修。\n\n仅在候选与当前分区摘要完全相同且其他全部校验通过时执行；不跳过备份和全量回读。确定承担风险并测试？", write);
                else write.run();
            });
            dialog.positiveEnabled(false);
            CompoundButton.OnCheckedChangeListener change = (b, on) -> dialog.positiveEnabled(risk.isChecked() && parked.isChecked() && power.isChecked() && ota.isChecked());
            risk.setOnCheckedChangeListener(change);
            parked.setOnCheckedChangeListener(change);
            power.setOnCheckedChangeListener(change);
            ota.setOnCheckedChangeListener(change);
            dialog.show();
        } catch (Exception e) {error(e.getMessage());}
    }

    private void licenses() {
        String[] labels = {"仪表工坊 0.2.3 测试版 · 数据与组件说明", "EROFS GPL-2.0-or-later", "JSch BSD", "JZlib BSD", "jBCrypt ISC"};
        String[] paths = {"licenses/NOTICE.txt", "licenses/erofs/GPL-2.0", "licenses/jsch/LICENSE.txt", "licenses/jsch/LICENSE.JZlib.txt", "licenses/jsch/LICENSE.jBCrypt.txt"};
        new UiDialog(this, ui, "本机说明与开源许可").items(labels, w -> {
            try (InputStream in = getAssets().open(paths[w])) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                FilesEx.transfer(in, out);
                new UiDialog(this, ui, labels[w]).message(new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)).positive("关闭", Ui.Look.TINT, null).show();
            } catch (Exception e) {error(e.getMessage());}
        }).negative("关闭", null).show();
    }

    // ---------------------------------------------------------------- file picking and activity results

    private void pick(int code, String mime) {
        if (state.busy.get()) {error("请等待当前任务结束后再选择文件"); return;}
        if (!requireStorage()) return;
        if (filePicker != null) filePicker.close();
        filePicker = new LocalFilePicker(this, ui);
        filePicker.open(code != 11, file -> importSelectedUri(code, Uri.fromFile(file)));
    }

    private void saveLocally(int request, String name) {
        if (state.busy.get()) {error("请等待当前任务结束后再另存文件"); return;}
        if (!requireStorage()) return;
        String imageId = saveImageId;
        if (filePicker != null) filePicker.close();
        filePicker = new LocalFilePicker(this, ui);
        File protectedRoot = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "EVDashboard");
        filePicker.save(name, protectedRoot, file -> withStorage(request == 14 ? "另存并回读镜像" : "导出诊断记录", () -> {
            // Recheck just before the exclusive creation; never overwrite a backup or prior export.
            File target = LocalFiles.destination(file.getParentFile(), file.getParentFile(), file.getName(), protectedRoot);
            if (request == 14) {
                ImageRecord record = state.library.get(imageId);
                state.progress.stage("校验另存来源", record.bytes);
                FilesEx.require(FilesEx.digest(state.library.file(record), state.progress::advance).equals(record.sha), "源镜像已变化，拒绝另存");
                FilesEx.require(target.createNewFile(), "同名文件已存在，拒绝覆盖");
                state.progress.stage("复制到所选位置", record.bytes);
                try (InputStream in = new FileInputStream(state.library.file(record)); FileOutputStream out = new FileOutputStream(target)) {
                    FilesEx.transfer(in, out, record.bytes, state.progress::advance); out.getFD().sync();
                }
                state.progress.stage("回读另存文件并校验摘要", record.bytes);
                FilesEx.require(FilesEx.digest(target, state.progress::advance).equals(record.sha), "另存文件回读校验失败，文件保留待检查");
                state.report("另存并回读校验通过：\n" + target + "\n镜像库原文件未改变。");
            } else {
                File log = new File(getFilesDir(), "last-task.log");
                String report = "仪表工坊 0.2.3\n" + (log.isFile() ? FilesEx.readText(log) : state.status);
                FilesEx.require(target.createNewFile(), "同名文件已存在，拒绝覆盖");
                try (FileOutputStream out = new FileOutputStream(target)) {out.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.getFD().sync();}
                state.report("诊断记录已导出：\n" + target + "\n不包含密码或 shadow 密码字段。");
            }
        }));
    }

    private String displayName(Uri uri) {
        if ("file".equals(uri.getScheme())) return new File(uri.getPath()).getName();
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {String name = c.getString(0); return name.length() > 100 ? name.substring(0, 100) : name;}
        } catch (Exception ignored) {}
        return "导入镜像";
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        importSelectedUri(request, data.getData());
    }

    private void importSelectedUri(int request, Uri uri) {
        if (request == 11) {
            String name = displayName(uri);
            withStorage("导入并检查镜像", () -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    ImageRecord r = state.workshop.importImage(in, name);
                    state.selected = r.id;
                    state.report("导入与结构检查完成，尚未证明与当前分区兼容。");
                }
            });
        }
    }

    void stopPreview() {
        if (player != null) {player.release(); player = null;}
        if (previewFocus != null) {AudioFocusRequest request = previewFocus; previewFocus = null; getSystemService(AudioManager.class).abandonAudioFocusRequest(request);}
        Runnable done = previewDone; previewDone = null; if (done != null) done.run();
    }
    void preview(File f, Runnable done) {
        previewFocusResult = -1;
        try {
            FilesEx.require(f != null && f.isFile(), "请先选择音频");
            stopPreview(); previewDone = done;
            byte[] source = java.nio.file.Files.readAllBytes(f.toPath()), prepared = SoundPreview.prepare(source);
            File playback = f;
            if (prepared != source) {
                playback = new File(getCacheDir(), "short-sound-preview.wav");
                try (FileOutputStream out = new FileOutputStream(playback)) {out.write(prepared);}
            }
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            MediaPlayer candidate = new MediaPlayer(); player = candidate;
            player.setAudioAttributes(attributes); player.setVolume(1f, 1f);
            player.setDataSource(playback.toString());
            player.setOnCompletionListener(p -> stopPreview());
            player.setOnErrorListener((p, what, extra) -> {stopPreview(); error("音频播放失败：" + what + " / " + extra); return true;});
            player.prepare();
            previewFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change -> {if (change < 0 && player == candidate) stopPreview();}, clock).build();
            previewFocusResult = getSystemService(AudioManager.class).requestAudioFocus(previewFocus);
            FilesEx.require(previewFocusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED, "系统暂不允许媒体试听，请确认车机正常上电，并结束语音或通话后重试");
            if (player == candidate) player.start();
        } catch (Exception e) {android.util.Log.w("SoundPreview", "Preview could not start", e); stopPreview(); error(e.getMessage());}
    }
}
