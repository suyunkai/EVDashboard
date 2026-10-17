package com.kooo.evdashboard;

import android.content.*;
import android.net.Uri;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.kooo.evdashboard.core.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Supplier;

/** Scrollable, fixed-size sound cards. All per-sound actions live in the detail dialog. */
final class SoundPanel implements AutoCloseable {
    private final MainActivity activity;
    private final Ui ui;
    private final AppState state;
    private final Supplier<String> password;
    private final SoundCatalog catalog;
    private final SoundStore store;
    private final BuiltinPresets builtin;
    private final LinearLayout directory, grid, categoryBar, presetList, builtinList;
    private final TextView summary, presetCount;
    private TextView automaticStatus;
    private Ui.SwitchRow automaticRow;
    private final ScrollView soundScroll;
    private final Map<String, List<SoundStore.Entry>> groups = new LinkedHashMap<>();
    private List<SoundStore.Entry> entries = new ArrayList<>();
    private Map<String, SoundStore.Rule> rules = new LinkedHashMap<>();
    private String category = "常用";
    private boolean readRequested, closed;
    private volatile Runnable reopenEditor;
    private LocalFilePicker picker;
    private UiDialog detail;

    SoundPanel(MainActivity activity, Ui ui, AppState state, Supplier<String> password) throws Exception {
        this.activity = activity; this.ui = ui; this.state = state; this.password = password;
        catalog = AudioEngine.catalog(activity); store = AudioEngine.store(activity, catalog); builtin = new BuiltinPresets(activity, catalog);
        directory = shell("实际音效");
        LinearLayout head = ui.horizontal(); summary = ui.text("先连接 CP，读取当前实际提示音", 25, ui.muted, false);
        head.addView(summary, ui.lp(0, Ui.WRAP, 1));
        head.addView(ui.button("读取 / 刷新", Ui.Look.TINT, v -> refresh())); directory.addView(head);
        HorizontalScrollView categories = new HorizontalScrollView(activity); categories.setHorizontalScrollBarEnabled(false);
        categoryBar = ui.horizontal(); categories.addView(categoryBar);
        directory.addView(categories, ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(16), 0, ui.px(18)));
        grid = ui.vertical(); soundScroll = new ScrollView(activity); soundScroll.setTag("sound-scroll");
        soundScroll.addView(grid); soundScroll.setFillViewport(false); soundScroll.setScrollbarFadingEnabled(false);
        directory.addView(soundScroll, ui.lp(Ui.MATCH, 0, 1));
        presetsView = shell("预设音效");
        ScrollView page = new ScrollView(activity); page.setTag("preset-scroll"); page.setScrollbarFadingEnabled(false);
        LinearLayout body = ui.vertical(); page.addView(body); presetsView.addView(page, ui.lp(Ui.MATCH, 0, 1));
        LinearLayout builtinHead = ui.horizontal(); builtinHead.setGravity(Gravity.BOTTOM);
        builtinHead.addView(ui.text("内置预设", 32, ui.ink, true), ui.lp(Ui.WRAP, Ui.WRAP, 0, 0, ui.px(20), 0));
        TextView builtinHint = ui.text("选择一套内置音效，勾选需要的声音并逐个试听，点击“应用”写入“实际音效”，再按需调整每个声音的音量。", 22, ui.muted, false);
        builtinHint.setSingleLine(); builtinHint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        builtinHead.addView(builtinHint, ui.lp(0, Ui.WRAP, 1)); body.addView(builtinHead, ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, ui.px(12)));
        builtinList = ui.vertical(); body.addView(builtinList, ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, ui.px(6)));
        View divider = new View(activity); divider.setBackgroundColor(ui.line); body.addView(divider, ui.lp(Ui.MATCH, ui.px(2), 0, ui.px(6), 0, ui.px(18)));
        LinearLayout mineHead = ui.horizontal(); mineHead.setGravity(Gravity.BOTTOM);
        mineHead.addView(ui.text("我的预设", 32, ui.ink, true), ui.lp(Ui.WRAP, Ui.WRAP, 0, 0, ui.px(20), 0));
        presetCount = ui.text("", 24, ui.muted, false); presetCount.setSingleLine(); mineHead.addView(presetCount, ui.lp(0, Ui.WRAP, 1));
        body.addView(mineHead, ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, ui.px(12)));
        LinearLayout actions = buttonRow(smallButton("保存预设", Ui.Look.PRIMARY, v -> namePreset()),
            smallButton("导入 ZIP", Ui.Look.TINT, v -> importZip()), smallButton("导出 ZIP", Ui.Look.TINT, v -> exportZip()));
        body.addView(actions, ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, ui.px(16)));
        presetList = ui.vertical(); body.addView(presetList, ui.lp(Ui.MATCH, Ui.WRAP));
        reload();
    }
    private final LinearLayout presetsView;
    View directory() {return directory;}
    View presets() {return presetsView;}
    private SharedPreferences preferences() {return activity.getSharedPreferences("settings", 0);}
    private LinearLayout shell(String title) {
        LinearLayout root = ui.vertical(); root.setBackground(ui.rect(ui.surface, 44, 0, 0));
        root.setPadding(ui.px(40), ui.px(32), ui.px(40), ui.px(28));
        root.addView(ui.text(title, 42, ui.ink, true), ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, ui.px(18))); return root;
    }
    View automatic() {
        LinearLayout root = shell("自动覆盖");
        Ui.SwitchRow automatic = ui.switchRow("开机与唤醒自动覆盖", "按当前配置应用替换与音量；默认关闭", preferences().getBoolean("soundAutoApply", false));
        automaticRow = automatic;
        automatic.toggle.setOnCheckedChangeListener((button, enabled) -> {
            if (enabled && (!SshAccess.trusted(activity) || entries.isEmpty() || !password.get().isEmpty())) {
                automatic.toggle.setChecked(false); activity.error("请先使用空密码连接并读取声音目录；自动连接不保存 SSH 密码"); return;
            }
            preferences().edit().putBoolean("soundAutoApply", enabled).apply();
            if (enabled) AutoApplyService.start(activity, "ENABLED"); else AutoApplyService.stop(activity);
        });
        root.addView(automatic.view);
        LinearLayout notes = ui.vertical(); notes.setPadding(ui.px(28), ui.px(22), ui.px(28), ui.px(22)); notes.setBackground(ui.rect(ui.surface2, 24, ui.line, 2));
        notes.addView(ui.text("启用后监听开机、用户切换和 STR 唤醒。CP 尚未就绪会等待重连，超时后仍定期检查；仅在当前资源与配置不一致时恢复。SSH 密钥变化无需确认，音效应用不再弹出驻车确认。", 26, ui.ink, false));
        automaticStatus = ui.text(preferences().getString("startupStatus", "等待开机或唤醒事件"), 25, ui.accent, true);
        automaticStatus.setTag("automatic-startup-status"); notes.addView(automaticStatus, ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(14), 0, ui.px(14)));
        notes.addView(ui.text("请先打开应用一次并允许后台运行。亮屏时每分钟核对配置，系统定期任务负责补启监听服务；强制停止后仍需重新打开应用。休眠时不主动唤醒车机，空闲时不持有唤醒锁。", 24, ui.muted, false));
        root.addView(notes, ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(18), 0, ui.px(22)));
        root.addView(buttonRow(
            smallButton("立即应用全部配置", Ui.Look.PRIMARY, v -> applyAll()),
            smallButton("停止下次自动覆盖", Ui.Look.TINT, v -> {automatic.toggle.setChecked(false); state.report("已关闭下次启动自动覆盖，当前 CP 声音保持现状。");}),
            smallButton("后台运行设置", Ui.Look.TINT, v -> {
                try {activity.startActivity(new android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}
                catch (RuntimeException error) {activity.error("此系统未提供后台运行设置入口，请在系统设置中允许后台运行");}
            })));
        return root;
    }
    View help() {
        LinearLayout root = shell("声音与恢复说明");
        ScrollView scroll = new ScrollView(activity); LinearLayout content = ui.vertical(); scroll.addView(content); root.addView(scroll, ui.lp(Ui.MATCH, 0, 1));
        content.addView(ui.text("点击卡片管理声音", 34, ui.ink, true), ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, ui.px(8)));
        content.addView(ui.text("选择分类后上下滑动查看全部声音。Tick/Tock、雷达快慢、普通/轻音等关联素材在同一张卡片中选择。试听使用 Android 媒体音频输出，短音会缓冲后重复播放。", 28, ui.muted, false));
        content.addView(ui.text("内置预设", 34, ui.ink, true), ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(24), 0, ui.px(8)));
        content.addView(ui.text("在“预设音效”里选择内置音效，按分类勾选要使用的声音，可全选、全不选并逐个试听。点击“应用”只更新本机的“实际音效”配置（每个声音的音量重置为 100%），随后可在声音卡片里单独调音量；“应用并写入 CP”会同时切换车机。未勾选的声音保持原有配置。", 28, ui.muted, false));
        content.addView(ui.text("文件替换与音量分开保存", 34, ui.ink, true), ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(24), 0, ui.px(8)));
        content.addView(ui.text("音量 0% 为静音，100% 为所选源文件，最高 200%（约 +6 dB），大音量带限幅。每次调音生成独立音频，不修改原声或替换源。可单独取消替换或恢复音量。", 28, ui.muted, false));
        content.addView(ui.text("同一素材可能服务多个提醒", 34, ui.ink, true), ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(24), 0, ui.px(8)));
        content.addView(ui.text("共用文件的提醒会一起变化。低速提示音按风格及主轨/辅助轨管理，前进与倒车可能共用；素材试听不等于随车速变化的混音。调用未确认的参考/诊断资源支持试听和导出。", 28, ui.muted, false));
        content.addView(ui.text("应用后才会切换 CP", 34, ui.ink, true), ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(24), 0, ui.px(8)));
        content.addView(ui.text("保存配置后点击“应用”才切换 CP；导入和载入预设也先保存在本机。若显示待应用，请重新应用。CP 切换失败会尝试恢复上一组覆盖，任务状态未确认时请查看记录，避免重复操作。", 28, ui.muted, false));
        return root;
    }
    void onVisible() {
        if (!readRequested && !state.busy.get() && SshAccess.trusted(activity)) {readRequested = true; refresh();}
    }
    private void job(String label, AppState.Job action) {
        if (state.busy.get()) {activity.error("请等待当前任务完成"); return;}
        activity.task(label, () -> {try {action.run();} finally {activity.runOnUiThread(() -> {if (!closed) reload();});}});
    }
    void refresh() {readRequested = true; String pass = password.get(); job("读取 CP 实际提示音", () -> new AudioEngine(activity, state::report).refresh(pass));}
    void reload() {
        if (closed) return;
        if (automaticRow != null) automaticRow.toggle.setChecked(preferences().getBoolean("soundAutoApply", false));
        if (automaticStatus != null) automaticStatus.setText(I18n.text(activity, preferences().getString("startupStatus", "等待开机或唤醒事件")));
        try {
            entries = store.entries(); rules = store.rules(); cards(); presetCards(); builtinCards();
            if (!state.busy.get() && reopenEditor != null) {
                Runnable reopen = reopenEditor; reopenEditor = null;
                if (directory.isShown()) reopen.run();
            }
        }
        catch (Exception e) {activity.error(e.getMessage());}
    }
    private void cards() {
        try {
            groups.clear();
            for (SoundStore.Entry entry : entries) {SoundCatalog.Spec spec = catalog.spec(entry.path); if (spec.category.equals(category)) groups.computeIfAbsent(spec.group, k -> new ArrayList<>()).add(entry);}
            List<String> names = new ArrayList<>(groups.keySet());
            summary.setText(entries.isEmpty() ? "先连接 CP，读取当前实际提示音" : "已读取 " + entries.size() + " 个素材 · " + category + " " + names.size() + " 项");
            categoryBar.removeAllViews();
            for (String name : SoundCatalog.CATEGORIES) {
                Button button = ui.button(name, name.equals(category) ? Ui.Look.PRIMARY : Ui.Look.TINT, v -> {category = name; cards(); soundScroll.scrollTo(0, 0);});
                categoryBar.addView(button, ui.lp(Ui.WRAP, ui.px(82), 0, 0, ui.px(12), 0));
            }
            grid.removeAllViews(); int columns = ui.compact ? 2 : 4;
            for (int start = 0; start < names.size(); start += columns) {
                LinearLayout row = ui.horizontal(); row.setGravity(Gravity.TOP);
                for (int c = 0; c < columns; c++) {
                    int index = start + c; View card = index < names.size() ? card(names.get(index), groups.get(names.get(index))) : new View(activity);
                    LinearLayout.LayoutParams lp = ui.lp(0, ui.px(184), 1); lp.setMargins(0, 0, c == columns - 1 ? 0 : ui.px(16), 0); row.addView(card, lp);
                }
                grid.addView(row, ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, ui.px(16)));
            }
            if (names.isEmpty()) grid.addView(emptyState());
        } catch (Exception e) {activity.error(e.getMessage());}
    }
    private View emptyState() {
        boolean unread = entries.isEmpty();
        LinearLayout box = ui.vertical(); box.setGravity(Gravity.CENTER_HORIZONTAL); box.setPadding(ui.px(40), ui.px(72), ui.px(40), ui.px(72));
        box.setBackground(ui.rect(ui.surface2, 28, ui.line, 2));
        FrameLayout tile = new FrameLayout(activity); tile.setBackground(ui.rect(ui.accentTint, 26, 0, 0));
        tile.addView(ui.icon(R.drawable.ic_ui_audio, ui.accent, 52), new FrameLayout.LayoutParams(ui.px(52), ui.px(52), Gravity.CENTER));
        box.addView(tile, ui.lp(ui.px(104), ui.px(104), 0, 0, 0, ui.px(24)));
        TextView title = ui.text(unread ? "尚未读取实际提示音" : "当前 CP 没有此分类的声音", 34, ui.ink, true); title.setGravity(Gravity.CENTER); box.addView(title);
        if (unread) {
            TextView hint = ui.text("读取后将自动按实际资源映射为声音卡片", 26, ui.muted, false); hint.setGravity(Gravity.CENTER);
            box.addView(hint, ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(10), 0, 0));
            box.addView(ui.button("读取实际提示音", Ui.Look.PRIMARY, v -> refresh()), ui.lp(Ui.WRAP, ui.px(96), 0, ui.px(30), 0, 0));
        }
        return box;
    }
    private TextView badge(String label, int color, int fill) {
        TextView t = ui.text(label, 21, color, true); t.setGravity(Gravity.CENTER); t.setSingleLine();
        t.setPadding(ui.px(12), 0, ui.px(12), 0); t.setBackground(ui.rect(fill, 10, 0, 0)); return t;
    }
    private View card(String name, List<SoundStore.Entry> sounds) throws Exception {
        int replaced = 0, gain = 0, active = 0; boolean blocked = false, pending = false, writable = false;
        for (SoundStore.Entry e : sounds) {
            SoundStore.Rule r = rules.get(e.path);
            if (r != null) {if (!r.replacement.isEmpty()) replaced++; if (r.gain != 100) gain++;}
            if ((r != null && !e.current.equals(store.cachedRender(r))) || (r == null && e.overlay.equals("OWN"))) pending = true;
            if (e.overlay.equals("OWN")) active++;
            if (e.original.isEmpty() || e.overlay.equals("LEGACY")) blocked = true;
            writable |= catalog.spec(e.path).writable();
        }
        LinearLayout card = ui.vertical(); card.setTag("sound-card"); card.setPadding(ui.px(20), ui.px(16), ui.px(20), ui.px(16));
        card.setBackground(ui.interactive(ui.surface2, 24, pending ? ui.mix(ui.line, ui.accent, .35f) : ui.line)); card.setClickable(true); card.setFocusable(true);
        LinearLayout title = ui.horizontal();
        FrameLayout icon = new FrameLayout(activity); icon.setBackground(ui.rect(ui.accentTint, 14, 0, 0));
        icon.addView(ui.icon(R.drawable.ic_ui_audio, ui.accent, 28), new FrameLayout.LayoutParams(ui.px(28), ui.px(28), Gravity.CENTER));
        title.addView(icon, ui.lp(ui.px(48), ui.px(48), 0, 0, ui.px(14), 0));
        TextView label = ui.text(name, 28, ui.ink, true); label.setMaxLines(2); label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.addView(label, ui.lp(0, Ui.WRAP, 1)); title.addView(ui.text("›", 30, ui.muted, false), ui.lp(Ui.WRAP, Ui.WRAP, ui.px(6), 0, 0, 0));
        card.addView(title, ui.lp(Ui.MATCH, 0, 1));
        String source = replaced > 0 ? "已替换" + (sounds.size() > 1 ? " " + replaced : "") : "原声";
        String amount = gain == 0 ? "100%" : sounds.size() == 1 ? volumeText(rules.get(sounds.get(0).path).gain) : "调音量 " + gain + " 项";
        LinearLayout tags = ui.horizontal();
        tags.addView(badge(source, replaced > 0 ? ui.accent : ui.muted, replaced > 0 ? ui.accentTint : ui.surface), ui.lp(Ui.WRAP, ui.px(32), 0, 0, ui.px(8), 0));
        tags.addView(badge(amount, gain > 0 ? ui.accent : ui.muted, gain > 0 ? ui.accentTint : ui.surface), ui.lp(Ui.WRAP, ui.px(32)));
        card.addView(tags, ui.lp(Ui.MATCH, ui.px(34), 0, ui.px(6), 0, ui.px(8)));
        LinearLayout bottom = ui.horizontal();
        String status = blocked ? "覆盖待恢复" : !writable ? "试听 / 导出" : pending ? "待应用" : active > 0 ? "覆盖已生效" : "系统原声";
        TextView statusText = ui.text(status, 21, blocked ? ui.warn : pending ? ui.accent : ui.muted, false); statusText.setSingleLine();
        bottom.addView(statusText, ui.lp(0, Ui.WRAP, 1));
        if (sounds.size() > 1) bottom.addView(ui.text(sounds.size() + " 个素材", 20, ui.muted, false));
        card.addView(bottom, ui.lp(Ui.MATCH, ui.px(28)));
        card.setContentDescription(I18n.text(activity, name + "，" + source + "，音量 " + amount + "，" + status));
        card.setOnClickListener(v -> showSound(name, sounds, 0)); return card;
    }
    private boolean editable(SoundStore.Entry e) throws Exception {
        return catalog.spec(e.path).writable() && !e.original.isEmpty() && !e.overlay.equals("FOREIGN") && !e.overlay.equals("LEGACY");
    }
    private Button smallButton(String label, Ui.Look look, View.OnClickListener click) {
        Button b = ui.button(label, look, click); b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.px(27));
        b.setPadding(ui.px(16), 0, ui.px(16), 0); b.setSingleLine(); b.setBackground(ui.interactive(look == Ui.Look.PRIMARY ? ui.accent : ui.surface, 18, look == Ui.Look.PRIMARY ? 0 : ui.line));
        return b;
    }
    private int actionHeight() {return Math.max(ui.px(78), Math.round(48 * activity.getResources().getDisplayMetrics().density));}
    private LinearLayout buttonRow(Button... buttons) {
        LinearLayout row = ui.horizontal();
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams lp = ui.lp(0, actionHeight(), 1); if (i > 0) lp.leftMargin = ui.px(12); row.addView(buttons[i], lp);
        }
        return row;
    }
    private void enabled(Button button, boolean enabled) {button.setEnabled(enabled); button.setAlpha(enabled ? 1f : .38f);}
    private void showSound(String name, List<SoundStore.Entry> sounds, int selected) {
        Map<String, SoundStore.Rule> drafts = new LinkedHashMap<>();
        for (SoundStore.Entry e : sounds) drafts.put(e.path, rules.getOrDefault(e.path, new SoundStore.Rule(e.original, "", 100)));
        showSound(name, sounds, selected, drafts);
    }
    private void showSound(String name, List<SoundStore.Entry> sounds, int selected, Map<String, SoundStore.Rule> drafts) {
        try {
            if (state.busy.get()) {activity.error("请等待任务完成后管理声音"); return;}
            SoundStore.Entry e = sounds.get(selected); SoundCatalog.Spec spec = catalog.spec(e.path); SoundStore.Rule rule = drafts.get(e.path);
            boolean writable = editable(e);
            UiDialog dialog = new UiDialog(activity, ui, name).compact(1280).onDismiss(activity::stopPreview); detail = dialog;
            SoundWave wave = new SoundWave(store.bytes(e.current));
            LinearLayout info = ui.vertical(); info.setTag("sound-information");
            LinearLayout facts = ui.horizontal();
            TextView metadata = ui.text(String.format(Locale.ROOT, I18n.string(activity, "%.2f 秒  ·  %s  ·  %.0f kHz"), wave.seconds(), wave.channels == 1 ? "单声道" : "双声道", wave.rate / 1000.0), 26, ui.muted, false);
            facts.addView(metadata, ui.lp(0, Ui.WRAP, 1));
            Button resource = smallButton("资源详情", Ui.Look.TINT, v -> new UiDialog(activity, ui, "资源信息").compact(1280).message(spec.path + "\n\n" + spec.name).positive("关闭", Ui.Look.TINT, null).show());
            facts.addView(resource, ui.lp(Ui.WRAP, actionHeight())); info.addView(facts);
            String scope = !writable ? "此素材仅支持试听和导出" : spec.category.equals("低速") ? "前进 / 倒车共用素材，实际风格由车辆设置决定" : spec.events > 1 ? "共用 " + spec.events + " 个提醒事件，修改会同步影响" : "调整后点击保存并应用";
            info.addView(ui.text(scope, 23, !writable ? ui.warn : ui.muted, false), ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(4), 0, ui.px(12)));
            if (SoundPreview.shortSound(wave)) info.addView(ui.text("短音试听含起始缓冲并重复 3 次，实际文件不变", 22, ui.muted, false), ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, ui.px(10)));
            dialog.view(info);
            if (sounds.size() > 1) {
                HorizontalScrollView scroll = new HorizontalScrollView(activity); LinearLayout variants = ui.horizontal(); scroll.addView(variants); scroll.setHorizontalScrollBarEnabled(false);
                for (int i = 0; i < sounds.size(); i++) {
                    final int index = i; Button b = smallButton(catalog.spec(sounds.get(i).path).variant, i == selected ? Ui.Look.PRIMARY : Ui.Look.TINT, v -> {dialog.dismiss(); showSound(name, sounds, index, drafts);});
                    variants.addView(b, ui.lp(Ui.WRAP, actionHeight(), 0, 0, ui.px(10), 0));
                }
                dialog.view(scroll);
            }
            final String[] replacement = {rule.replacement};
            SeekBar volume = new SeekBar(activity); volume.setTag("sound-volume"); volume.setMax(200); volume.setProgress(rule.gain); volume.setEnabled(writable);
            volume.setProgressTintList(android.content.res.ColorStateList.valueOf(ui.accent)); volume.setThumbTintList(android.content.res.ColorStateList.valueOf(ui.accent));
            LinearLayout block = ui.vertical(); block.setPadding(ui.px(24), ui.px(16), ui.px(24), ui.px(16)); block.setBackground(ui.rect(ui.surface2, 24, 0, 0));
            LinearLayout volumeHead = ui.horizontal(); volumeHead.addView(ui.text("独立音量", 28, ui.ink, true), ui.lp(0, Ui.WRAP, 1));
            TextView amount = ui.text(volumeText(rule.gain), 28, ui.accent, true); volumeHead.addView(amount);
            Button reset = smallButton("重置", Ui.Look.TINT, v -> volume.setProgress(100)); enabled(reset, writable);
            volumeHead.addView(reset, ui.lp(Ui.WRAP, actionHeight(), ui.px(16), 0, 0, 0)); block.addView(volumeHead);
            volume.setContentDescription(I18n.text(activity, "声音音量，0 到 200 百分比"));
            volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int n, boolean user) {amount.setText(volumeText(n)); drafts.put(e.path, new SoundStore.Rule(e.original, replacement[0], n));}
                public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {}
            });
            block.addView(volume, ui.lp(Ui.MATCH, ui.px(64)));
            LinearLayout scale = ui.horizontal();
            for (String text : new String[]{"0% 静音", "100% 原音量", "200% 增强"}) {TextView t = ui.text(text, 21, ui.muted, false); t.setGravity(text.startsWith("0") ? Gravity.START : text.startsWith("100") ? Gravity.CENTER : Gravity.END); scale.addView(t, ui.lp(0, Ui.WRAP, 1));}
            block.addView(scale); dialog.view(block);
            LinearLayout controls = ui.vertical(); controls.setTag("sound-actions"); controls.setPadding(0, ui.px(16), 0, 0);
            LinearLayout sourceHead = ui.horizontal(); TextView source = ui.text(replacement[0].isEmpty() ? "音频来源 · 系统原声" : "音频来源 · 替换音频", 26, ui.ink, true);
            sourceHead.addView(source, ui.lp(0, Ui.WRAP, 1));
            Button cancelSource = smallButton("取消替换", Ui.Look.TINT, null); enabled(cancelSource, writable && !replacement[0].isEmpty());
            cancelSource.setOnClickListener(v -> {replacement[0] = ""; drafts.put(e.path, new SoundStore.Rule(e.original, "", volume.getProgress())); source.setText("音频来源 · 系统原声"); enabled(cancelSource, false);});
            sourceHead.addView(cancelSource, ui.lp(Ui.WRAP, actionHeight())); controls.addView(sourceHead);
            Button original = smallButton("试听原声", Ui.Look.TINT, null); enabled(original, !e.original.isEmpty());
            Button current = smallButton("试听当前", Ui.Look.TINT, null);
            Button adjusted = smallButton("试听调整后", Ui.Look.TINT, null); enabled(adjusted, writable);
            for (Button b : new Button[]{original, current, adjusted}) b.setSoundEffectsEnabled(false);
            Button[] playing = {null};
            Runnable resetPreview = () -> {playing[0] = null; original.setText("试听原声"); current.setText("试听当前"); adjusted.setText("试听调整后");};
            java.util.function.BiConsumer<Button, String> play = (button, sha) -> {
                if (!dialog.isShowing()) return;
                if (playing[0] == button) {activity.stopPreview(); return;}
                activity.stopPreview(); playing[0] = button; button.setText("停止试听");
                try {activity.preview(store.asset(sha), resetPreview);} catch (Exception error) {resetPreview.run(); activity.error(error.getMessage());}
            };
            original.setOnClickListener(v -> play.accept(original, e.original)); current.setOnClickListener(v -> play.accept(current, e.current));
            adjusted.setOnClickListener(v -> {
                if (playing[0] == adjusted) {activity.stopPreview(); return;}
                activity.stopPreview(); SoundStore.Rule preview = new SoundStore.Rule(e.original, replacement[0], volume.getProgress());
                job("生成试听音频", () -> {String sha = store.render(preview); activity.runOnUiThread(() -> play.accept(adjusted, sha));});
            });
            controls.addView(buttonRow(original, current, adjusted), ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(10), 0, ui.px(12)));
            Button replace = smallButton("替换音频", Ui.Look.TINT, v -> {dialog.dismiss(); chooseReplacement(e, name, sounds, selected, drafts);}); enabled(replace, writable);
            Button export = smallButton("导出声音", Ui.Look.TINT, v -> exportSound(e, new SoundStore.Rule(e.original, replacement[0], volume.getProgress()), volume.getProgress()));
            Button restore = smallButton("恢复默认", Ui.Look.TINT, v -> {replacement[0] = ""; volume.setProgress(100); drafts.put(e.path, new SoundStore.Rule(e.original, "", 100)); source.setText("音频来源 · 系统原声"); enabled(cancelSource, false);}); enabled(restore, writable);
            controls.addView(buttonRow(replace, export, restore));
            controls.addView(ui.text("本机试听  ·  音量处理生成独立文件，原音频保留", 22, ui.muted, false), ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(12), 0, 0));
            dialog.view(controls).negative("关闭", null);
            boolean canSave = false; for (SoundStore.Entry sound : sounds) canSave |= editable(sound);
            if (canSave) {
                dialog.neutral("仅保存", () -> saveDrafts(sounds, drafts, false));
                dialog.positive("保存并应用", Ui.Look.PRIMARY, () -> saveDrafts(sounds, drafts, true));
            }
            dialog.show();
        } catch (Exception ex) {activity.error(ex.getMessage());}
    }
    private static String volumeText(int gain) {return gain == 0 ? "静音" : gain + "%";}
    private void saveDrafts(List<SoundStore.Entry> sounds, Map<String, SoundStore.Rule> drafts, boolean apply) {
        try {
            Map<String, SoundStore.Rule> edits = new LinkedHashMap<>();
            for (SoundStore.Entry e : sounds) if (editable(e)) edits.put(e.path, drafts.get(e.path));
            String pass = password.get();
            Runnable save = () -> job(apply ? "应用声音卡片配置" : "保存声音卡片配置", () -> {
                Map<String, SoundStore.Rule> changed = store.rules();
                for (Map.Entry<String, SoundStore.Rule> edit : edits.entrySet()) {if (edit.getValue().changed()) changed.put(edit.getKey(), edit.getValue()); else changed.remove(edit.getKey());}
                store.rules(changed);
                if (apply) new AudioEngine(activity, state::report).apply(pass, false, edits.keySet());
                else state.report("此卡片的音频与音量配置已保存到本机，尚未应用到 CP。");
            });
            save.run();
        } catch (Exception e) {activity.error(e.getMessage());}
    }
    private void chooseReplacement(SoundStore.Entry entry, String name, List<SoundStore.Entry> sounds, int selected, Map<String, SoundStore.Rule> drafts) {
        if (!activity.requireStorage() || state.busy.get()) return; closePicker();
        Runnable reopen = () -> {if (!closed) showSound(name, sounds, selected, drafts);};
        picker = new LocalFilePicker(activity, ui).onCancel(reopen);
        picker.open(true, file -> job("导入替换源音频", () -> {
            try {
                SoundWave original = new SoundWave(store.bytes(entry.original));
                String source = store.put(AudioImport.decode(activity, Uri.fromFile(file), 100, original.channels));
                activity.runOnUiThread(() -> drafts.put(entry.path, new SoundStore.Rule(entry.original, source, drafts.get(entry.path).gain)));
                state.report("替换源已导入编辑器，保存后才会更新配置。");
            } finally {reopenEditor = reopen;}
        }));
    }
    private void exportSound(SoundStore.Entry entry, SoundStore.Rule rule, int gain) {
        List<String> labels = new ArrayList<>(), choices = new ArrayList<>();
        if (!entry.original.isEmpty()) {labels.add("导出系统原声"); choices.add("original");}
        labels.add("导出 CP 当前声音"); choices.add("current");
        if (!entry.original.isEmpty() && catalogWritable(entry.path)) {labels.add("导出配置声音（含音量）"); choices.add("configured");}
        if (!rule.replacement.isEmpty()) {labels.add("导出未调音的替换源"); choices.add("replacement");}
        UiDialog dialog = new UiDialog(activity, ui, "导出声音 WAV").compact(1280);
        for (int start = 0; start < labels.size(); start += 2) {
            List<Button> buttons = new ArrayList<>();
            for (int index = start; index < Math.min(start + 2, labels.size()); index++) {
                String kind = choices.get(index);
                Button option = smallButton(labels.get(index), Ui.Look.TINT, v -> {
                    activity.stopPreview(); dialog.dismiss();
                    String filename = entry.path.substring(entry.path.lastIndexOf('/') + 1).replaceAll("(?i)\\.wav$", "") + "-" + kind + ".wav";
                    save(filename, file -> job("导出单个声音", () -> {
                        String sha = kind.equals("original") ? entry.original : kind.equals("current") ? entry.current : kind.equals("replacement") ? rule.replacement : store.render(new SoundStore.Rule(entry.original, rule.replacement, gain));
                        exclusive(file, out -> out.write(store.bytes(sha))); state.report("声音已导出：" + I18n.literal(file.toString()));
                    }));
                });
                option.setSingleLine(false); buttons.add(option);
            }
            dialog.view(buttonRow(buttons.toArray(new Button[0])));
        }
        dialog.negative("取消", null).show();
    }
    private boolean catalogWritable(String path) {try {return catalog.spec(path).writable();} catch (Exception e) {return false;}}
    private void namePreset() {
        if (state.busy.get()) {activity.error("请等待任务完成"); return;}
        LinearLayout form = ui.vertical(); EditText name = ui.field(form, "预设名称", "", InputType.TYPE_CLASS_TEXT, "例如：日常出行", "", 0);
        new UiDialog(activity, ui, "保存预设 · 最多 5 个").view(form).negative("取消", null).positive("保存", Ui.Look.PRIMARY, () -> {String chosenName = name.getText().toString(); job("保存声音预设", () -> {store.savePreset(chosenName); state.report("预设已保存，包含文件替换与独立音量配置。");});}).show();
    }
    private void presetCards() throws Exception {
        List<SoundStore.Preset> presets = store.presets(); presetCount.setText("已保存 " + presets.size() + " / 5 · 当前配置 " + rules.size() + " 个素材"); presetList.removeAllViews();
        if (presets.isEmpty()) presetList.addView(ui.text("保存常用配置后可快速切换；ZIP 会包含规则、原声与替换源。", 24, ui.muted, false));
        int columns = ui.compact ? 2 : 3;
        for (int start = 0; start < presets.size(); start += columns) {
            LinearLayout row = ui.horizontal(); row.setBaselineAligned(false);
            for (int c = 0; c < columns; c++) {
                LinearLayout.LayoutParams lp = ui.lp(0, Ui.WRAP, 1); if (c > 0) lp.leftMargin = ui.px(14);
                if (start + c >= presets.size()) {row.addView(new View(activity), lp); continue;}
                SoundStore.Preset preset = presets.get(start + c);
                LinearLayout card = ui.horizontal(); card.setTag("preset-card"); card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(ui.px(20), ui.px(16), ui.px(20), ui.px(16)); card.setMinimumHeight(ui.px(100));
                card.setBackground(ui.interactive(ui.surface2, 24, ui.line)); card.setClickable(true); card.setFocusable(true);
                FrameLayout tile = new FrameLayout(activity); tile.setBackground(ui.rect(ui.accentTint, 14, 0, 0));
                tile.addView(ui.icon(R.drawable.ic_ui_audio, ui.accent, 30), new FrameLayout.LayoutParams(ui.px(30), ui.px(30), Gravity.CENTER));
                card.addView(tile, ui.lp(ui.px(56), ui.px(56), 0, 0, ui.px(16), 0));
                LinearLayout names = ui.vertical();
                TextView name = ui.text(I18n.literal(preset.name), 30, ui.ink, true); name.setSingleLine(); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                names.addView(name);
                TextView hint = ui.text("点击载入、应用或删除", 21, ui.muted, false); hint.setSingleLine(); hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
                names.addView(hint, ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(2), 0, 0));
                card.addView(names, ui.lp(0, Ui.WRAP, 1)); card.addView(ui.text("›", 30, ui.muted, false), ui.lp(Ui.WRAP, Ui.WRAP, ui.px(8), 0, 0, 0));
                card.setOnClickListener(v -> presetActions(preset)); row.addView(card, lp);
            }
            presetList.addView(row, ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, ui.px(14)));
        }
    }
    private void builtinCards() {
        builtinList.removeAllViews();
        List<SoundPreset> presets = builtin.list();
        if (presets.isEmpty()) {builtinList.addView(ui.text("此安装包没有内置预设", 26, ui.muted, false)); return;}
        int columns = ui.compact ? 1 : 2;
        for (int start = 0; start < presets.size(); start += columns) {
            LinearLayout row = ui.horizontal(); row.setBaselineAligned(false);
            for (int c = 0; c < columns; c++) {
                LinearLayout.LayoutParams lp = ui.lp(0, Ui.WRAP, 1); if (c > 0) lp.leftMargin = ui.px(14);
                if (start + c >= presets.size()) {row.addView(new View(activity), lp); continue;}
                SoundPreset preset = presets.get(start + c);
                LinearLayout card = ui.vertical(); card.setTag("builtin-preset-card");
                card.setPadding(ui.px(22), ui.px(18), ui.px(22), ui.px(16));
                card.setBackground(ui.interactive(ui.surface2, 26, ui.mix(ui.line, ui.accent, .35f))); card.setClickable(true); card.setFocusable(true);
                LinearLayout heading = ui.horizontal(); heading.setGravity(Gravity.CENTER_VERTICAL);
                FrameLayout tile = new FrameLayout(activity); tile.setBackground(ui.rect(ui.accentTint, 16, 0, 0));
                tile.addView(ui.icon(R.drawable.ic_ui_audio, ui.accent, 32), new FrameLayout.LayoutParams(ui.px(32), ui.px(32), Gravity.CENTER));
                heading.addView(tile, ui.lp(ui.px(60), ui.px(60), 0, 0, ui.px(16), 0));
                LinearLayout names = ui.vertical();
                TextView name = ui.text(preset.name, 32, ui.ink, true); name.setSingleLine(); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                names.addView(name);
                names.addView(ui.text(preset.items().size() + " 个声音 · " + preset.categories().size() + " 个分类", 22, ui.muted, false), ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(2), 0, 0));
                heading.addView(names, ui.lp(0, Ui.WRAP, 1)); heading.addView(ui.text("›", 34, ui.accent, true)); card.addView(heading);
                TextView description = ui.text(preset.description, 22, ui.muted, false); description.setMinLines(2); description.setMaxLines(2); description.setEllipsize(android.text.TextUtils.TruncateAt.END);
                card.addView(description, ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(10), 0, 0));
                card.setContentDescription(I18n.text(activity, "内置预设 " + preset.name + "，" + preset.items().size() + " 个声音，点击选择并应用"));
                card.setOnClickListener(v -> showBuiltin(preset)); row.addView(card, lp);
            }
            builtinList.addView(row, ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, ui.px(14)));
        }
    }
    /** Sounds as selectable cards, grouped by category, with per-sound preview; Apply writes the ticked sounds into the actual-sound configuration. */
    private void showBuiltin(SoundPreset preset) {
        if (state.busy.get()) {activity.error("请等待任务完成后选择内置预设"); return;}
        try {
            Map<String, SoundStore.Entry> usable = new HashMap<>();
            for (SoundStore.Entry e : entries) if (editable(e)) usable.put(e.path, e);
            Set<String> chosen = new LinkedHashSet<>();
            for (SoundPreset.Item item : preset.items()) if (usable.containsKey(item.path)) chosen.add(item.path);
            UiDialog dialog = new UiDialog(activity, ui, preset.name).compact(1780).bodyHeight(.66f).onDismiss(activity::stopPreview);
            boolean read = !entries.isEmpty();
            dialog.pinned(ui.text(preset.description, 26, ui.ink, false));
            if (!read) dialog.pinned(ui.text("尚未读取 CP 实际提示音：现在可以试听，读取后才能勾选并应用。", 26, ui.warn, true));
            else if (usable.size() < preset.items().size()) dialog.pinned(ui.text("灰色的声音在当前 CP 上不可用，不会应用。", 23, ui.muted, false));
            TextView summary = ui.text("", 28, ui.ink, true);
            Button all = smallButton("全选", Ui.Look.TINT, null), none = smallButton("全不选", Ui.Look.TINT, null);
            LinearLayout bar = ui.horizontal(); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(0, ui.px(6), 0, ui.px(6));
            bar.addView(summary, ui.lp(0, Ui.WRAP, 1)); bar.addView(all, ui.lp(Ui.WRAP, actionHeight())); bar.addView(none, ui.lp(Ui.WRAP, actionHeight(), ui.px(12), 0, 0, 0));
            if (!read) {all.setVisibility(View.GONE); none.setVisibility(View.GONE);}
            bar.setPadding(0, ui.px(12), 0, ui.px(6)); dialog.pinned(bar);
            Map<String, Ui.Toggle> boxes = new LinkedHashMap<>(), categoryBoxes = new LinkedHashMap<>();
            Map<String, View> soundCards = new HashMap<>(); Map<String, TextView> categoryCounts = new HashMap<>();
            List<Button> playButtons = new ArrayList<>(); Button[] playing = {null}; boolean[] syncing = {false};
            Runnable[] sync = new Runnable[1];
            Runnable resetPreview = () -> {playing[0] = null; for (Button b : playButtons) b.setText("试听");};
            int columns = ui.compact ? 2 : 3;
            for (String category : preset.categories()) {
                List<SoundPreset.Item> inCategory = preset.inCategory(category);
                LinearLayout head = ui.horizontal(); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(ui.px(4), ui.px(22), ui.px(4), ui.px(12));
                Ui.Toggle categoryBox = new Ui.Toggle(ui, true); categoryBoxes.put(category, categoryBox);
                if (!read) categoryBox.setVisibility(View.GONE);
                head.addView(categoryBox, ui.lp(Ui.WRAP, Ui.WRAP, 0, 0, ui.px(16), 0));
                head.addView(ui.text(category, 32, ui.ink, true), ui.lp(Ui.WRAP, Ui.WRAP, 0, 0, ui.px(14), 0));
                TextView count = ui.text("", 24, ui.muted, false); categoryCounts.put(category, count);
                head.addView(count, ui.lp(0, Ui.WRAP, 1));
                if (read) {head.setClickable(true); head.setOnClickListener(v -> categoryBox.toggle());}
                categoryBox.setOnCheckedChangeListener((b, on) -> {
                    if (syncing[0]) return;
                    for (SoundPreset.Item item : inCategory) if (usable.containsKey(item.path)) {if (on) chosen.add(item.path); else chosen.remove(item.path);}
                    sync[0].run();
                });
                dialog.view(head);
                for (int start = 0; start < inCategory.size(); start += columns) {
                    LinearLayout row = ui.horizontal(); row.setBaselineAligned(false); row.setPadding(0, 0, 0, ui.px(16));
                    for (int c = 0; c < columns; c++) {
                        LinearLayout.LayoutParams lp = ui.lp(0, Ui.MATCH, 1); if (c < columns - 1) lp.rightMargin = ui.px(16);
                        if (start + c >= inCategory.size()) {row.addView(new View(activity), lp); continue;}
                        SoundPreset.Item item = inCategory.get(start + c); boolean available = usable.containsKey(item.path);
                        LinearLayout card = ui.vertical(); card.setTag("builtin-sound-card"); card.setPadding(ui.px(20), ui.px(18), ui.px(20), ui.px(16));
                        soundCards.put(item.path, card);
                        LinearLayout top = ui.horizontal(); top.setGravity(Gravity.CENTER_VERTICAL);
                        Ui.Toggle box = new Ui.Toggle(ui, true); boxes.put(item.path, box); box.setEnabled(available); if (!read) box.setVisibility(View.GONE);
                        top.addView(box, ui.lp(Ui.WRAP, Ui.WRAP, 0, 0, read ? ui.px(16) : 0, 0));
                        TextView title = ui.text(item.group, 28, available ? ui.ink : ui.muted, true); title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        top.addView(title, ui.lp(0, Ui.WRAP, 1)); card.addView(top);
                        LinearLayout tags = ui.horizontal();
                        if (!item.variant.equals("默认")) tags.addView(badge(item.variant, ui.accent, ui.accentTint), ui.lp(Ui.WRAP, ui.px(32), 0, 0, ui.px(8), 0));
                        tags.addView(badge(String.format(Locale.ROOT, "%.1f 秒", item.seconds), ui.muted, ui.surface), ui.lp(Ui.WRAP, ui.px(32), 0, 0, ui.px(8), 0));
                        if (read && !available) tags.addView(badge("当前 CP 不可用", ui.warn, ui.warnBg), ui.lp(Ui.WRAP, ui.px(32)));
                        card.addView(tags, ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(12), 0, ui.px(18)));
                        card.addView(new View(activity), ui.lp(Ui.MATCH, 0, 1));
                        Button play = smallButton("试听", Ui.Look.TINT, null); play.setSoundEffectsEnabled(false); playButtons.add(play);
                        play.setOnClickListener(v -> {
                            if (playing[0] == play) {activity.stopPreview(); return;}
                            activity.stopPreview(); playing[0] = play; play.setText("停止");
                            try {
                                File file = new File(activity.getCacheDir(), "builtin-preview.wav");
                                Files.write(file.toPath(), builtin.audio(preset, item)); activity.preview(file, resetPreview);
                            } catch (Exception error) {resetPreview.run(); activity.error(error.getMessage());}
                        });
                        card.addView(play, ui.lp(Ui.MATCH, actionHeight()));
                        card.setAlpha(!read || available ? 1f : .55f);
                        if (available) {card.setClickable(true); card.setOnClickListener(v -> box.toggle());}
                        box.setOnCheckedChangeListener((b, on) -> {if (syncing[0]) return; if (on) chosen.add(item.path); else chosen.remove(item.path); sync[0].run();});
                        card.setContentDescription(I18n.text(activity, item.label() + String.format(Locale.ROOT, "，%.1f 秒", item.seconds)));
                        row.addView(card, lp);
                    }
                    dialog.view(row);
                }
            }
            all.setOnClickListener(v -> {chosen.clear(); chosen.addAll(usable.keySet()); sync[0].run();});
            none.setOnClickListener(v -> {chosen.clear(); sync[0].run();});
            dialog.negative("取消", null)
                .neutral("应用并写入 CP", () -> applyBuiltin(preset, chosen, true))
                .positive("应用", Ui.Look.PRIMARY, () -> applyBuiltin(preset, chosen, false));
            sync[0] = () -> {
                syncing[0] = true;
                for (Map.Entry<String, Ui.Toggle> b : boxes.entrySet()) b.getValue().setChecked(chosen.contains(b.getKey()));
                for (Map.Entry<String, View> card : soundCards.entrySet()) {
                    boolean on = chosen.contains(card.getKey());
                    card.getValue().setBackground(ui.interactive(on ? ui.mix(ui.surface2, ui.accent, .12f) : ui.surface2, 24, on ? ui.accent : ui.line));
                }
                for (Map.Entry<String, Ui.Toggle> c : categoryBoxes.entrySet()) {
                    int available = 0, on = 0;
                    for (SoundPreset.Item item : preset.inCategory(c.getKey())) if (usable.containsKey(item.path)) {available++; if (chosen.contains(item.path)) on++;}
                    c.getValue().setChecked(available > 0 && on == available); c.getValue().setEnabled(available > 0); c.getValue().setAlpha(available > 0 ? 1f : .35f);
                    categoryCounts.get(c.getKey()).setText(read ? "已选 " + on + " / " + preset.inCategory(c.getKey()).size() : "");
                }
                syncing[0] = false;
                summary.setText(read ? "已选 " + chosen.size() + " / " + usable.size() : preset.items().size() + " 个声音可试听");
                dialog.positiveEnabled(!chosen.isEmpty()); dialog.neutralEnabled(!chosen.isEmpty());
            };
            sync[0].run(); dialog.show();
        } catch (Exception e) {activity.error(e.getMessage());}
    }
    private void applyBuiltin(SoundPreset preset, Set<String> chosen, boolean toCp) {
        List<SoundPreset.Item> picked = new ArrayList<>();
        for (SoundPreset.Item item : preset.items()) if (chosen.contains(item.path)) picked.add(item);
        if (picked.isEmpty()) return;
        String pass = password.get();
        job(toCp ? "应用内置预设并写入 CP" : "应用内置预设", () -> {
            Map<String, byte[]> audio = new LinkedHashMap<>();
            for (SoundPreset.Item item : picked) audio.put(item.path, builtin.audio(preset, item));
            PresetApply.Result result = PresetApply.apply(store, catalog, store.entries(), audio);
            String done = "内置预设“" + preset.name + "”已应用到实际音效：" + result.applied.size() + " 个声音已写入本机配置。"
                + (result.skipped.isEmpty() ? "" : result.skipped.size() + " 个无法应用（" + result.skipped.values().iterator().next() + "）。");
            if (toCp && !result.applied.isEmpty()) {
                state.report(done + "正在写入 CP。");
                new AudioEngine(activity, state::report).apply(pass, false, new LinkedHashSet<>(result.applied));
            } else state.report(done + (result.applied.isEmpty() ? "" : "可在“实际音效”里逐个调整音量；点击“应用全部配置”或声音卡片里的“保存并应用”后才会写入 CP。"));
        });
    }
    private void presetActions(SoundStore.Preset preset) {
        new UiDialog(activity, ui, I18n.literal(preset.name))
            .items(new String[]{"载入配置", "载入并应用到 CP", "删除此预设"}, index -> {
                if (index == 0) job("载入预设", () -> {store.loadPreset(preset.id); state.report("预设已载入，点击应用全部配置后切换 CP。");});
                else if (index == 1) {String pass = password.get(); job("切换声音预设", () -> {store.loadPreset(preset.id); new AudioEngine(activity, state::report).apply(pass, false);});}
                else new UiDialog(activity, ui, "删除预设？").message(I18n.literal(preset.name)).negative("取消", null).positive("删除", Ui.Look.DANGER, () -> job("删除声音预设", () -> store.deletePreset(preset.id))).show();
            }).negative("关闭", null).show();
    }
    private void applyAll() {String pass = password.get(); job("应用全部仪表提示音", () -> new AudioEngine(activity, state::report).apply(pass, false));}
    private void exportZip() {save("instrument-sounds.zip", file -> job("导出声音配置 ZIP", () -> {exclusive(file, store::exportZip); state.report("配置与相关音频已导出：" + file);}));}
    private void importZip() {
        if (!activity.requireStorage() || state.busy.get()) return; closePicker(); picker = new LocalFilePicker(activity, ui);
        picker.openZip(file -> job("导入声音配置 ZIP", () -> {try (InputStream in = new FileInputStream(file)) {store.importZip(in);} state.report("ZIP 配置与音频已导入到本机，尚未应用。应用时会核对当前 CP 原声摘要。");}));
    }
    private interface Writer {void write(OutputStream output) throws Exception;}
    private static void exclusive(File file, Writer writer) throws Exception {
        FilesEx.require(file.createNewFile(), "同名文件已存在，拒绝覆盖"); boolean success = false;
        try {try (FileOutputStream out = new FileOutputStream(file)) {writer.write(out);} success = true;}
        finally {if (!success) Files.deleteIfExists(file.toPath());}
    }
    private void save(String name, java.util.function.Consumer<File> action) {
        if (!activity.requireStorage() || state.busy.get()) return; closePicker(); picker = new LocalFilePicker(activity, ui);
        picker.save(name, new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "EVDashboard"), action);
    }
    private void closePicker() {if (picker != null) picker.close();}
    @Override public void close() {closed = true; closePicker(); if (detail != null) detail.dismiss();}
}
