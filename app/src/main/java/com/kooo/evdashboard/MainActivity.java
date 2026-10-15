package com.kooo.evdashboard;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
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
        {"连接 CP", "AVAS · 行人提示音", "锁车提示音", "已保存设置", "驻车测试"},
        {"1 · 识别与备份", "2 · 选择与修改", "3 · 校验与写入", "任务与恢复"}};
    private static final String[][] SUBTITLES = {
        {"", "自定义音频与曲线", "自定义锁车声", "", "谨慎操作"},
        {"先识别，再导出备份", "选择镜像，再生成副本", "保留备份并全量回读", "写后回读与恢复"}};
    private static final int[][] ICONS = {
        {R.drawable.ic_ui_link, R.drawable.ic_ui_audio, R.drawable.ic_ui_lock, R.drawable.ic_ui_save, R.drawable.ic_ui_flask},
        {R.drawable.ic_ui_board, R.drawable.ic_ui_disk, R.drawable.ic_ui_warn, R.drawable.ic_ui_log}};
    private static final int[][] COLORS = {
        {0xFF2D6BFF, 0xFF16A07A, 0xFF7A5AF8, 0xFFF28C28, 0xFFE5483C},
        {0xFF2D6BFF, 0xFF16A07A, 0xFFE5483C, 0xFF7A5AF8}};
    private static final String[] PAGE_NAMES = {"AVAS 与提示音", "仪表盘工坊"};

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
    private TextView statusTitle, statusText, progressText, permissionText, cpAddressText;
    private View statusDot;
    private ProgressBar progress;

    private EditText password, forward, reverse, avasGain, lockGain;
    private Ui.Toggle useAvasAudio, useCurves, enableSsh, clearRoot;
    private TextView avasFileText, lockFileText, fingerprintChip;
    private LinearLayout imageList;
    private TextView slotText, deviceText, selectedText, dangerInfo;
    private View writeRow;
    private File avasAudio, lockAudio;
    private String saveImageId = "";
    private MediaPlayer player;
    private LocalFilePicker filePicker;
    private final Handler clock = new Handler(Looper.getMainLooper());
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {updateStatus(); clock.postDelayed(this, 1000);}
    };

    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {updateStatus(); if (!state.busy.get()) refreshLibrary();}
    };

    // Before API 33 the same-app signature permission protects this receiver.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        state = (AppState) getApplication();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, new IntentFilter("com.kooo.evdashboard.STATUS"), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(updates, new IntentFilter("com.kooo.evdashboard.STATUS"), getPackageName() + ".INTERNAL", null);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 90);
        render(getResources().getConfiguration());
    }

    @Override protected void onResume() {super.onResume(); clock.removeCallbacks(progressTick); clock.post(progressTick); updatePermissions(); try {state.storage(); refreshLibrary();} catch (Exception ignored) {}}
    @Override protected void onPause() {clock.removeCallbacks(progressTick); super.onPause();}
    @Override protected void onDestroy() {clock.removeCallbacks(progressTick); if (filePicker != null) filePicker.close(); unregisterReceiver(updates); if (player != null) player.release(); super.onDestroy();}
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {super.onConfigurationChanged(config); render(config);}

    // ---------------------------------------------------------------- layout

    private void render(android.content.res.Configuration config) {
        if (filePicker != null) {filePicker.close(); filePicker = null;}
        ui = new Ui(this, config);
        View root = ui.compact ? compactRoot() : wideRoot();
        root.setBackgroundColor(ui.bg);
        setContentView(root);
        applySystemBars();
        restoreAudioLabels();
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
        return root;
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
            Button b = new Button(this);
            b.setText(PAGE_NAMES[i]);
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
        navSub[p][s] = new TextView(this);
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
    }

    private View taskBar() {
        LinearLayout container = ui.vertical();
        container.setBackground(ui.rect(ui.surface, 34, 0, 0));
        LinearLayout bar = ui.horizontal();
        bar.setMinimumHeight(ui.px(88));
        bar.setPadding(ui.px(32), ui.px(10), ui.px(32), ui.px(10));
        bar.setBackground(ui.interactive(ui.surface, 34, 0));
        bar.setClickable(true);
        bar.setFocusable(true);
        bar.setOnClickListener(v -> new UiDialog(this, ui, state.title).message(state.status + "\n\n" + state.progress.snapshot().summary() + "\n百分比与剩余时间仅对应当前步骤，不能代表整个任务已完成。重建、分区写入等无法计量的步骤不会显示虚假倒计时。").positive("关闭", Ui.Look.TINT, null).show());
        statusDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        statusDot.setBackground(dot);
        bar.addView(statusDot, ui.lp(ui.px(18), ui.px(18), 0, 0, ui.px(22), 0));
        statusTitle = ui.text("", 28, ui.ink, true);
        bar.addView(statusTitle, ui.lp(Ui.WRAP, Ui.WRAP, 0, 0, ui.px(22), 0));
        statusText = ui.text("", 26, ui.muted, false);
        statusText.setMaxLines(2);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(statusText, ui.lp(0, Ui.WRAP, 1));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ui.accent));
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ui.accent));
        bar.addView(progress, ui.lp(ui.px(360), ui.px(10), ui.px(22), 0, 0, 0));
        container.addView(bar);
        progressText = ui.text("", 24, ui.muted, false);
        container.addView(progressText, ui.lp(Ui.MATCH, Ui.WRAP, ui.px(32), 0, ui.px(32), ui.px(16)));
        return container;
    }

    private void updateStatus() {
        if (statusText == null) return;
        statusTitle.setText(state.title);
        statusTitle.setTextColor(state.failed ? ui.danger : ui.ink);
        statusText.setText(state.status);
        statusText.setTextColor(state.failed ? ui.danger : ui.muted);
        ((GradientDrawable) statusDot.getBackground()).setColor(state.failed ? ui.danger : state.busy.get() ? ui.accent : ui.ok);
        progress.setVisibility(state.busy.get() ? View.VISIBLE : View.GONE);
        TaskProgress.Snapshot snapshot = state.progress.snapshot();
        progress.setIndeterminate(snapshot.percent() < 0);
        if (snapshot.percent() >= 0) progress.setProgress(snapshot.percent());
        progressText.setText(state.busy.get() ? snapshot.stage() + "\n" + snapshot.summary() : state.title.equals("就绪") ? "尚无任务 · 所有镜像处理均在本机完成" : snapshot.summary());
        progressText.setVisibility(state.title.equals("就绪") ? View.GONE : View.VISIBLE);
    }

    // ---------------------------------------------------------------- pane scaffold

    private final class Pane {
        final LinearLayout root = ui.vertical(), header = ui.horizontal(), content = ui.vertical(), footer = ui.horizontal();

        Pane(int p, int s) {
            root.setBackground(ui.rect(ui.surface, 44, 0, 0));
            root.setPadding(ui.px(44), ui.px(36), ui.px(44), ui.px(32));
            header.addView(ui.icon(ICONS[p][s], COLORS[p][s], 48), ui.lp(ui.px(48), ui.px(48), 0, 0, ui.px(20), 0));
            header.addView(ui.text(TITLES[p][s], 42, ui.ink, true), ui.lp(0, Ui.WRAP, 1));
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
    private AudioEngine audio() {return new AudioEngine(this, state::report);}
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
                prefs().edit().putString("sshKnown", offer.host + " " + offer.type + " " + offer.key).apply();
                state.report("已保存当前 CP 主机指纹，后续严格匹配。");
                updateConnection();
            }));
        }))));
        link.add(g);
        link.footer(null, ui.button("连接并读取运行状态", Ui.Look.PRIMARY, click(() -> {String p = pass(); task("检测 CP SSH", () -> state.report(audio().status(p)));})));

        // 1 · AVAS
        Pane avas = new Pane(0, 1);
        avas.add(ui.callout("现场验收：", "实际声音和音量必须现场验收；配置检查不能替代声学及适用地区要求。", Ui.Tone.WARN));
        avas.caption("音频");
        LinearLayout sound = ui.group();
        Ui.SwitchRow custom = toggleRow("useAvas", "使用自定义音频（最长 30 秒）", "尚未选择音频", false);
        useAvasAudio = custom.toggle;
        avasFileText = custom.hint;
        ui.addRow(sound, custom.view);
        ui.addRow(sound, ui.actionRow("选择 AVAS 音频", Ui.Tone.ACCENT, click(() -> pick(12, "audio/*"))));
        ui.addRow(sound, ui.actionRow("在本机试听所选音频", Ui.Tone.ACCENT, click(() -> preview(avasAudio))));
        avas.add(sound);
        avas.caption("曲线");
        LinearLayout curves = ui.group();
        Ui.SwitchRow curve = toggleRow("useCurves", "使用自定义音量 / 速度曲线", "", false);
        useCurves = curve.toggle;
        ui.addRow(curves, curve.view);
        LinearLayout inputs = ui.horizontal();
        inputs.setGravity(Gravity.TOP);
        forward = field(inputs, "forward", "正向速度点\n0/5/10/15/20/25/30 km/h", prefs().getString("forward", ""), InputType.TYPE_CLASS_TEXT, "7 个数值，逗号分隔", "", 1);
        reverse = field(inputs, "reverse", "倒车曲线\n7 个数值，逗号分隔", prefs().getString("reverse", ""), InputType.TYPE_CLASS_TEXT, "7 个数值，逗号分隔", "", 1);
        avasGain = field(inputs, "avasGain", "曲线总音量\n50–200%，默认 100", Integer.toString(prefs().getInt("avasGain", 100)), InputType.TYPE_CLASS_NUMBER, "100", "%", 1);
        ui.addRow(curves, inputs);
        ui.addRow(curves, ui.actionRow("读取当前固件的原曲线", Ui.Tone.ACCENT, click(() -> {
            String p = pass();
            task("读取 AVAS 曲线", () -> {
                int[][] read = audio().curves(p);
                runOnUiThread(() -> {if (forward != null) {forward.setText(AudioEngine.join(read[0])); reverse.setText(AudioEngine.join(read[1])); useCurves.setChecked(true);}});
                state.report("已读取曲线，不采用其他车型的默认数值。尚未修改 CP。");
            });
        })));
        avas.add(curves);
        avas.footer(ui.button("恢复 AVAS 默认资源并开启服务", Ui.Look.TINT, click(() -> {String p = pass(); parked("恢复 AVAS 默认", "仅移除仪表工坊自己的覆盖；检测到其他工具覆盖会停止。", () -> task("恢复 AVAS", () -> audio().restore(p, "avas")));})),
            ui.button("应用 AVAS 设置", Ui.Look.PRIMARY, click(() -> {
                try {
                    String p = pass();
                    File f = useAvasAudio.isChecked() ? avasAudio : null;
                    FilesEx.require(!useAvasAudio.isChecked() || f != null, "请先选择音频");
                    int[] a = useCurves.isChecked() ? AudioEngine.parseOptional(forward.getText().toString()) : null, b = useCurves.isChecked() ? AudioEngine.parseOptional(reverse.getText().toString()) : null;
                    int gain = Integer.parseInt(avasGain.getText().toString());
                    parked("应用 AVAS 设置", "将短暂重启 AVAS 服务。不会写入原始资源；失败时尝试恢复上一覆盖。", () -> task("应用 AVAS", () -> audio().applyAvas(p, f, a, b, gain)));
                } catch (Exception e) {error(e.getMessage());}
            })));

        // 2 · lock tone
        Pane lock = new Pane(0, 2);
        LinearLayout lg = ui.group();
        lockFileText = ui.text("尚未选择音频", 26, ui.muted, false);
        ui.addRow(lg, ui.infoRow(lockFileText));
        ui.addRow(lg, ui.actionRow("选择锁车音频", Ui.Tone.ACCENT, click(() -> pick(13, "audio/*"))));
        ui.addRow(lg, ui.actionRow("在本机试听锁车音频", Ui.Tone.ACCENT, click(() -> preview(lockAudio))));
        ui.divide(lg);
        lockGain = field(lg, "lockGain", "播放音量（25–200%，带峰值限制）", Integer.toString(prefs().getInt("lockGain", 100)), InputType.TYPE_CLASS_NUMBER, "100", "%", 0);
        lock.add(lg);
        lock.footer(ui.button("恢复默认锁车提示音", Ui.Look.TINT, click(() -> {String p = pass(); parked("恢复锁车声", "撤销本应用的锁车声覆盖并重启音效服务。", () -> task("恢复锁车声", () -> audio().restore(p, "lock")));})),
            ui.button("应用锁车提示音", Ui.Look.PRIMARY, click(() -> {
                try {
                    FilesEx.require(lockAudio != null, "请先选择音频");
                    String p = pass();
                    File f = lockAudio;
                    int gain = Integer.parseInt(lockGain.getText().toString());
                    parked("应用锁车提示音", "将短暂重启仪表音效服务，期间提示音可能不可用。", () -> task("应用锁车声", () -> audio().applyLock(p, f, gain)));
                } catch (Exception e) {error(e.getMessage());}
            })));

        // 3 · saved settings
        Pane saved = new Pane(0, 3);
        LinearLayout sg = ui.group();
        Ui.SwitchRow boot = ui.switchRow("开机尝试恢复已保存的 AVAS / 锁车音效", "默认关闭；仅在能独立确认 P 挡且零车速时执行", prefs().getBoolean("reapply", false));
        boot.toggle.setOnCheckedChangeListener((b, on) -> {
            if (!on) {prefs().edit().putBoolean("reapply", false).apply(); updateConnection(); return;}
            new UiDialog(this, ui, "启用开机音效恢复")
                .message("仅在能独立确认 P 挡、速度为零时执行；无法确认或 SSH 指纹变化时停止并提示。临时静音和 AVAS 停止不会开机重放。仅支持空密码自动连接。该自动功能需要实车验收。")
                .negative("取消", () -> boot.toggle.setChecked(false))
                .positive("启用", Ui.Look.PRIMARY, () -> {prefs().edit().putBoolean("reapply", true).apply(); updateConnection();})
                .onCancel(() -> boot.toggle.setChecked(false))
                .show();
        });
        ui.addRow(sg, boot.view);
        saved.add(sg);
        saved.footer(null, ui.button("手动重新应用已保存音效", Ui.Look.PRIMARY, click(() -> {String p = pass(); parked("重新应用音效", "使用已保存音频和曲线，短暂重启对应服务。", () -> task("重新应用音效", () -> audio().reapplySaved(p)));})));

        // 4 · parked tests
        Pane test = new Pane(0, 4);
        test.add(ui.callout("驻车测试：", "AVAS 与车辆提示音承担安全提醒作用。以下静音操作只用于驻车测试，不保存为开机设置，离开测试前必须恢复。", Ui.Tone.WARN));
        LinearLayout tg = ui.group();
        ui.addRow(tg, ui.actionRow("临时停止 AVAS", Ui.Tone.WARN, click(() -> {String p = pass(); parked("临时停止行人提示音", "行人将无法通过此声音判断车辆接近。确认仅在静止测试中使用。", () -> confirm("再次确认停止 AVAS", "测试结束后请立即开启 AVAS，禁止在道路行驶中使用静音状态。", () -> task("驻车测试：停止 AVAS", () -> audio().avasEnabled(p, false))));})));
        ui.addRow(tg, ui.actionRow("开启 AVAS 服务", Ui.Tone.NEUTRAL, click(() -> {String p = pass(); task("开启 AVAS", () -> audio().avasEnabled(p, true));})));
        ui.addRow(tg, ui.actionRow("临时静音启用 / 退出提示（含巡航）", Ui.Tone.WARN, click(() -> {String p = pass(); parked("通用功能提示音测试", "这两项资源除巡航外也可能被其他功能使用。仅限驻车测试，不改变巡航控制逻辑；不得在行驶中使用。", () -> task("通用提示音驻车测试", () -> audio().muteCruiseForTest(p)));})));
        ui.addRow(tg, ui.actionRow("恢复巡航提示音", Ui.Tone.NEUTRAL, click(() -> {String p = pass(); parked("恢复巡航提示音", "移除本应用的两项覆盖并重启音效服务。", () -> task("恢复巡航提示音", () -> audio().restore(p, "cruise")));})));
        test.add(tg);

        return new View[]{link.root, avas.root, lock.root, saved.root, test.root};
    }

    private void updateConnection() {
        if (fingerprintChip == null) return;
        boolean known = SshAccess.trusted(this);
        ui.setChip(fingerprintChip, known ? "指纹已确认" : "指纹未确认", known ? Ui.Tone.OK : Ui.Tone.WARN);
        cpAddressText.setText(SshAccess.host(this) + " : 22  ·  root");
        navSub[0][0].setText(SshAccess.host(this) + " · " + (known ? "指纹已确认" : "指纹未确认"));
        navSub[0][3].setText("开机恢复：" + (prefs().getBoolean("reapply", false) ? "开启" : "关闭"));
    }

    private void restoreAudioLabels() {
        String a = prefs().getString("draftAvas", prefs().getString("avasFile", "")), l = prefs().getString("draftLock", prefs().getString("lockFile", ""));
        if (!a.isEmpty() && new File(a).isFile()) {avasAudio = new File(a); avasFileText.setText("已选择本机 WAV：" + avasAudio.getName());}
        if (!l.isEmpty() && new File(l).isFile()) {lockAudio = new File(l); lockFileText.setText("已选择本机 WAV：" + lockAudio.getName());}
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
        Pane library = new Pane(1, 1);
        library.note("先在下方选择一份来源镜像，再查看 SSH / 密码状态并勾选修改。生成的是独立副本，原备份保持不变；选中不等于允许写入。");
        Button importImage = ui.button("从本机 / U 盘导入镜像", Ui.Look.TINT, click(() -> pick(11, "application/octet-stream")));
        importImage.setMinimumHeight(ui.px(96));
        library.add(importImage);
        library.caption("镜像库 · 选择一份来源镜像");
        imageList = ui.group();
        library.add(imageList);
        library.caption("所选镜像");
        LinearLayout info = ui.group();
        selectedText = ui.text("请先选择一份镜像", 26, ui.ink, false);
        ui.addRow(info, ui.infoRow(selectedText));
        library.add(info);
        library.caption("需要进行的修改（可多选）");
        LinearLayout options = ui.group();
        Ui.SwitchRow ssh = toggleRow("enableSsh", "开启 SSH 开机监听（22 端口）", "", false);
        Ui.SwitchRow blank = toggleRow("clearRoot", "清除 root 密码，允许空密码登录", "", false);
        enableSsh = ssh.toggle;
        clearRoot = blank.toggle;
        ui.addRow(options, ssh.view);
        ui.addRow(options, blank.view);
        library.add(options);
        library.add(ui.callout("注意：", "空密码会让能够访问此 SSH 入口的一方获得 root 权限。锁定账号或未知认证配置不会被静默改写。", Ui.Tone.WARN));
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
        library.add(actions);
        library.footer(ui.button("无需修改 → 第 3 步", Ui.Look.TINT, click(() -> {try {chosen(); select(1, 2);} catch (Exception e) {error(e.getMessage());}})), ui.button("生成新的修改镜像", Ui.Look.PRIMARY, click(() -> {
            boolean sshOn = enableSsh.isChecked(), blankOn = clearRoot.isChecked();
            if (!sshOn && !blankOn) {error("请先勾选需要进行的修改；如果无需修改，可直接进入第 3 步复核。"); return;}
            withStorage("生成镜像", () -> {
                ImageRecord r = chosen();
                ImageRecord result = state.workshop.generate(r, sshOn, blankOn);
                state.selected = result.id;
                state.report("新镜像生成并完整复核通过，原文件未改变；尚未刷写。");
                runOnUiThread(() -> {refreshLibrary(); select(1, 2);});
            });
        })));

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

        return new View[]{device.root, library.root, danger.root, audit.root};
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
                selectedText.setText(r.name + "\n" + r.categoryLabel() + "\nSSH 配置：" + sshLabel(r.socket) + "  / root 密码：" + passwordLabel(r.password) + "\n允许空密码参数：" + (r.blankAllowed ? "是" : "否 / 未知")
                    + "\n固件基线：" + (r.build.isEmpty() ? "未知" : r.build.substring(0, 12)) + "\nSHA-256：" + r.sha + "\n" + r.message);
                dangerInfo.setText(r.name + "\n" + r.categoryLabel() + "\n" + (r.validated ? "结构已校验，可进入写入确认；仍需确认本机备份和兼容性" : "尚未通过结构校验：写入已锁定，请先重新校验"));
                writeRow.setAlpha(r.validated ? 1f : .5f);
            }
        } catch (Exception e) {
            selectedText.setText("镜像库读取失败：" + e.getMessage());
        }
    }

    private View imageRow(ImageRecord r) {
        boolean selected = r.id.equals(state.selected);
        LinearLayout row = ui.horizontal();
        row.setMinimumHeight(ui.px(124));
        row.setPadding(ui.px(26), ui.px(16), ui.px(26), ui.px(16));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(ui.interactive(selected ? ui.accentTint : 0, 0, 0));
        row.addView(ui.radio(selected), ui.lp(ui.px(40), ui.px(40), 0, 0, ui.px(24), 0));
        LinearLayout col = ui.vertical();
        col.addView(ui.text(r.name, 30, ui.ink, true));
        LinearLayout meta = ui.horizontal();
        Ui.Tone tone = switch (r.category) {case "ORIGINAL" -> Ui.Tone.OK; case "MODIFIED" -> Ui.Tone.ACCENT; case "IMPORTED" -> Ui.Tone.NEUTRAL; default -> Ui.Tone.WARN;};
        meta.addView(ui.chip(r.categoryLabel(), tone), ui.lp(Ui.WRAP, ui.px(52), 0, ui.px(8), ui.px(16), 0));
        TextView facts = ui.text((r.slot.isEmpty() ? "未绑定槽位" : r.slot) + "  ·  " + (r.bytes / (1024 * 1024)) + " MiB  ·  " + (r.validated ? "结构已校验" : "待检查 / 检查未通过"), 24, r.validated ? ui.ok : ui.warn, false);
        meta.addView(facts, ui.lp(Ui.WRAP, Ui.WRAP, 0, ui.px(8), 0, 0));
        col.addView(meta);
        row.addView(col, ui.lp(0, Ui.WRAP, 1));
        if (r.original()) row.addView(ui.chip("删除受保护", Ui.Tone.WARN));
        row.setOnClickListener(v -> {if (state.busy.get()) {error("任务进行中，暂不能更换所选镜像"); return;} state.selected = r.id; refreshLibrary();});
        return row;
    }

    // ---------------------------------------------------------------- dialogs

    private void error(String s) {
        new UiDialog(this, ui, "操作未开始").message(s == null ? "未知错误" : s).positive("知道了", Ui.Look.PRIMARY, null).show();
    }

    private void task(String label, AppState.Job job) {try {state.submit(label, job);} catch (Exception e) {error(e.getMessage());}}
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

    private boolean requireStorage() {
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
            .message("仅影响 AVAS / 提示音的 SSH 连接；端口 22，用户 root。不会改变本机分区识别或写入目标。地址变化后需要重新确认指纹，开机音效恢复会关闭。")
            .negative("取消", null).positive("保存地址", Ui.Look.PRIMARY, () -> {
                try {
                    FilesEx.require(!state.busy.get(), "已有任务运行，请结束后再修改");
                    String host = CpAddress.parse(address.getText().toString());
                    if (!host.equals(SshAccess.host(this))) prefs().edit().putString("cpHost", host).remove("sshKnown").putBoolean("reapply", false).apply();
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

    private void parked(String title, String warning, Runnable yes) {
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
            confirm("删除镜像记录？", r.name + "\n文件将移入镜像库的 deleted 暂存目录，可人工恢复；不会触碰车辆分区。", () -> {
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
            UiDialog dialog = new UiDialog(this, ui, "写入前必须确认 · " + r.name).view(l);
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
        String[] labels = {"仪表工坊 0.1.0 测试版 · 数据与组件说明", "EROFS GPL-2.0-or-later", "JSch BSD", "JZlib BSD", "jBCrypt ISC"};
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
                String report = "仪表工坊 0.1.0\n" + (log.isFile() ? FilesEx.readText(log) : state.status);
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
        } else if (request == 12 || request == 13) {
            task("解码并规范化音频", () -> {
                byte[] wav = AudioImport.decode(this, uri, 100);
                File dir = new File(getFilesDir(), "audio");
                FilesEx.require(dir.isDirectory() || dir.mkdir(), "无法创建音频目录");
                File file = new File(dir, UUID.randomUUID() + ".wav");
                try (FileOutputStream out = new FileOutputStream(file)) {out.write(wav); out.getFD().sync();}
                prefs().edit().putString(request == 12 ? "draftAvas" : "draftLock", file.toString()).apply();
                runOnUiThread(() -> {
                    if (request == 12) {
                        avasAudio = file;
                        if (avasFileText != null) {avasFileText.setText("已导入：48 kHz / 单声道 / PCM 16-bit"); useAvasAudio.setChecked(true);}
                    } else {
                        lockAudio = file;
                        if (lockFileText != null) lockFileText.setText("已导入：48 kHz / 单声道 / PCM 16-bit");
                    }
                });
                state.report("音频已保存到本机，尚未应用到 CP。");
            });
        }
    }

    private void preview(File f) {
        try {
            FilesEx.require(f != null && f.isFile(), "请先选择音频");
            if (player != null) player.release();
            player = new MediaPlayer();
            player.setDataSource(f.toString());
            player.prepare();
            player.start();
            Runnable stop = () -> {if (player != null && player.isPlaying()) player.stop();};
            new UiDialog(this, ui, "本机试听（不是 CP 播放）").message("正在通过 Android 音频输出试听。实际车辆提示音需另行验收。").positive("停止", Ui.Look.PRIMARY, stop).onCancel(stop).show();
        } catch (Exception e) {error(e.getMessage());}
    }
}
