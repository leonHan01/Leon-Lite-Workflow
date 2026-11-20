package com.leon.workflow.admin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import android.text.Editable;
import android.text.TextWatcher;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements WorkspaceBrowser.Listener {
    private static final int INK = 0xff202b3d, MUTED = 0xff7c879e, BLUE = 0xff4669e8, BACKGROUND = 0xfff6f7fb;
    private final List<Module> modules = new ArrayList<>();
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private SharedPreferences preferences;
    private ServerAddress server;
    private WorkspaceBrowser browser;
    private FrameLayout content;
    private LinearLayout nativePane;
    private LinearLayout errorPane;
    private TextView title, subtitle, errorText;
    private ProgressBar progress;
    private Button refresh;
    private final List<NavigationTab> tabs = new ArrayList<>();
    private String screen = "home", currentPath = "/index.html";
    private boolean accountBusy;

    private record Module(String title, String description, String path, String group, String icon) { }
    private record NavigationTab(LinearLayout container, ImageView icon, TextView label, View indicator) { }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("client", MODE_PRIVATE);
        try { server = new ServerAddress(preferences.getString("server", ServerAddress.DEFAULT)); }
        catch (IllegalArgumentException ignored) { server = new ServerAddress(ServerAddress.DEFAULT); }
        loadModules();
        buildShell();
        createBrowser();
        if (savedInstanceState != null && "web".equals(savedInstanceState.getString("screen"))) {
            open(savedInstanceState.getString("path", "/index.html"));
        } else if (preferences.getBoolean("resume", false)) {
            String path = preferences.getString("lastPath", "/index.html");
            if (knownPath(path)) open(path); else home();
        } else home();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::back);
        }
    }

    private void buildShell() {
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        LinearLayout root = vertical(); root.setBackgroundColor(BACKGROUND);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return android.os.Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
        });
        LinearLayout toolbar = horizontal(); toolbar.setGravity(Gravity.CENTER_VERTICAL); toolbar.setPadding(dp(12), dp(4), dp(8), dp(4));
        Button menu = button("☰", this::showModules); menu.setContentDescription("全部管理模块");
        toolbar.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(52)));
        LinearLayout heading = vertical(); title = text("Leon 管理", 19, INK, true); subtitle = text(server.origin(), 10, MUTED, false);
        subtitle.setSingleLine(true); heading.addView(title); heading.addView(subtitle);
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        refresh = button("↻", () -> { if (screen.equals("web")) { hideError(); browser.reload(); } else home(); });
        refresh.setContentDescription("刷新当前页面"); toolbar.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(52)));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(64)));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));
        content = new FrameLayout(this); root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        nativePane = vertical(); content.addView(nativePane, new FrameLayout.LayoutParams(-1, -1));
        root.addView(bottomNavigation()); setContentView(root);
    }

    private View bottomNavigation() {
        LinearLayout navigation = vertical(); navigation.setBackgroundColor(Color.WHITE);
        View divider = new View(this); divider.setBackgroundColor(0xffedf0f6);
        navigation.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout row = horizontal(); row.setPadding(dp(12), dp(6), dp(12), dp(6));
        String[] names = {"工作台", "工作流", "监控", "设置"};
        int[] icons = {R.drawable.ic_nav_workbench, R.drawable.ic_nav_workflow, R.drawable.ic_nav_monitor, R.drawable.ic_nav_settings};
        Runnable[] actions = {this::home, () -> open("/workflows.html"), () -> open("/monitor.html"), this::settings};
        for (int i = 0; i < names.length; i++) {
            LinearLayout cell = vertical(); cell.setGravity(Gravity.CENTER);
            cell.setMinimumHeight(dp(56)); cell.setClickable(true); cell.setFocusable(true);
            cell.setContentDescription(names[i]); cell.setTooltipText(names[i]);
            cell.setBackground(new RippleDrawable(ColorStateList.valueOf(0x144669e8), null, shape(Color.WHITE, 16)));
            cell.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName("android.widget.Button");
                }
            });
            FrameLayout iconHolder = new FrameLayout(this);
            iconHolder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            View indicator = new View(this); indicator.setBackground(shape(0xffedf1ff, 16)); indicator.setAlpha(0f);
            iconHolder.addView(indicator, new FrameLayout.LayoutParams(-1, -1));
            ImageView icon = new ImageView(this); icon.setImageResource(icons[i]); icon.setImageTintList(ColorStateList.valueOf(MUTED));
            iconHolder.addView(icon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
            cell.addView(iconHolder, new LinearLayout.LayoutParams(dp(52), dp(30)));
            TextView label = text(names[i], 11, MUTED, true); label.setGravity(Gravity.CENTER);
            label.setIncludeFontPadding(false); label.setSingleLine(true); label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(-1, -2); labelSize.topMargin = dp(4);
            cell.addView(label, labelSize);
            Runnable action = actions[i]; cell.setOnClickListener(view -> action.run());
            tabs.add(new NavigationTab(cell, icon, label, indicator));
            row.addView(cell, new LinearLayout.LayoutParams(0, dp(58), 1));
        }
        navigation.addView(row);
        return navigation;
    }

    private void createBrowser() {
        browser = new WorkspaceBrowser(this, server, this, preferences.getBoolean("desktop", false), this::knownPath);
        content.addView(browser.view, 0, new FrameLayout.LayoutParams(-1, -1));
        errorPane = vertical(); errorPane.setGravity(Gravity.CENTER); errorPane.setPadding(dp(32), dp(24), dp(32), dp(24));
        errorPane.setBackgroundColor(BACKGROUND);
        TextView icon = text("⌁", 48, BLUE, true); icon.setGravity(Gravity.CENTER); errorPane.addView(icon);
        TextView heading = text("页面暂时无法打开", 22, INK, true); heading.setGravity(Gravity.CENTER); errorPane.addView(heading);
        errorText = text("", 14, MUTED, false); errorText.setGravity(Gravity.CENTER); errorText.setPadding(0, dp(16), 0, dp(24)); errorPane.addView(errorText);
        errorPane.addView(primary("重试", () -> { hideError(); browser.reload(); }));
        errorPane.addView(button("检查服务地址", this::settings));
        content.addView(errorPane, new FrameLayout.LayoutParams(-1, -1)); errorPane.setVisibility(View.GONE);
    }

    private void home() {
        if (accountBusy) return;
        showNative("home", "工作台");
        LinearLayout page = page();
        TextView brand = text("leon.", 34, BLUE, true); brand.setLetterSpacing(-.04f); page.addView(brand);
        page.addView(text("WORKFLOW / ADMIN", 10, MUTED, true));
        TextView welcome = text("管理日常，\n让工作流随行。", 29, INK, true);
        welcome.setPadding(0, dp(24), 0, dp(12)); page.addView(welcome);
        TextView connection = text("当前服务  ·  " + server.origin(), 12, MUTED, false);
        connection.setPadding(0, 0, 0, dp(24)); page.addView(connection);
        EditText search = new EditText(this); search.setSingleLine(true); search.setTextSize(15); search.setHint("搜索管理功能");
        search.setPadding(dp(16), dp(12), dp(16), dp(12)); search.setBackground(shape(Color.WHITE, 12));
        search.setContentDescription("搜索管理功能"); page.addView(search, new LinearLayout.LayoutParams(-1, dp(52)));
        LinearLayout list = vertical(); page.addView(list);
        renderModules(list, "");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { renderModules(list, value.toString()); }
            @Override public void afterTextChanged(Editable value) { }
        });
        TextView footer = text("LEON WORKFLOW  ·  你的移动管理工作空间", 10, MUTED, false);
        footer.setPadding(0, dp(30), 0, dp(16)); page.addView(footer);
    }

    private void renderModules(LinearLayout list, String query) {
        list.removeAllViews(); String group = ""; LinearLayout row = null; int index = 0;
        String term = query.trim().toLowerCase(Locale.ROOT);
        for (Module module : modules) {
            if (!(module.title + module.description + module.group).toLowerCase(Locale.ROOT).contains(term)) continue;
            if (!group.equals(module.group)) {
                group = module.group; index = 0; row = null;
                TextView label = text(group, 13, MUTED, true); label.setPadding(0, dp(24), 0, dp(12)); list.addView(label);
            }
            if (index++ % 2 == 0) { row = horizontal(); list.addView(row, new LinearLayout.LayoutParams(-1, -2)); }
            LinearLayout card = vertical(); card.setPadding(dp(16), dp(18), dp(12), dp(18)); card.setMinimumHeight(dp(148));
            card.setBackground(shape(Color.WHITE, 14)); card.setElevation(dp(1));
            card.addView(text(module.icon, 24, BLUE, false));
            TextView name = text(module.title, 15, INK, true); name.setPadding(0, dp(12), 0, dp(6)); card.addView(name);
            card.addView(text(module.description, 11, MUTED, false));
            card.setContentDescription(module.title + "，" + module.description); card.setFocusable(true);
            card.setOnClickListener(view -> open(module.path));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -1, 1); size.setMargins(0, 0, dp(8), dp(8)); row.addView(card, size);
            if (index % 2 == 1) {
                // Give a lone card half the row; the spacer is replaced when a second card arrives.
                View spacer = new View(this); spacer.setTag("spacer"); row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
            } else {
                View spacer = row.findViewWithTag("spacer"); if (spacer != null) row.removeView(spacer);
            }
        }
        if (list.getChildCount() == 0) {
            TextView empty = text("没有匹配的管理功能", 14, MUTED, false); empty.setPadding(0, dp(24), 0, 0); list.addView(empty);
        }
    }

    private void settings() {
        if (accountBusy) return;
        showNative("settings", "客户端设置");
        LinearLayout page = page();
        page.addView(text("连接你的工作空间", 25, INK, true));
        paragraph(page, "使用后台的访问令牌登录。登录状态由服务端 Cookie 管理，客户端不保存原始令牌。");
        TextView label = text("HTTPS 服务地址", 13, INK, true); label.setPadding(0, dp(12), 0, dp(8)); page.addView(label);
        EditText address = new EditText(this); address.setSingleLine(true); address.setText(server.origin()); address.setTextSize(15);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setPadding(dp(14), dp(12), dp(14), dp(12)); address.setBackground(shape(Color.WHITE, 10));
        page.addView(address, new LinearLayout.LayoutParams(-1, dp(52)));
        paragraph(page, "默认连接正式服务。更换地址会退出当前账号，并清除本机的管理会话。");
        page.addView(primary("保存服务地址", () -> {
            try {
                ServerAddress next = new ServerAddress(address.getText().toString());
                if (next.origin().equals(server.origin())) { toast("服务地址已保存"); return; }
                new AlertDialog.Builder(this).setTitle("更换服务地址")
                        .setMessage("退出当前服务，连接 " + next.origin() + "？未保存的网页表单会关闭。")
                        .setNegativeButton("取消", null).setPositiveButton("更换", (dialog, which) -> clearSession(next)).show();
            } catch (IllegalArgumentException error) { address.setError(error.getMessage()); }
        }));
        Switch resume = toggle("启动时恢复上次管理页面", preferences.getBoolean("resume", false));
        resume.setOnCheckedChangeListener((button, checked) -> preferences.edit().putBoolean("resume", checked).apply()); page.addView(resume);
        Switch desktop = toggle("使用桌面布局", preferences.getBoolean("desktop", false));
        desktop.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean("desktop", checked).apply(); browser.setDesktop(checked);
        }); page.addView(desktop);
        paragraph(page, "工作流编排支持横屏、缩放和全屏画布。桌面布局适合查看较宽的数据表和画布。");
        page.addView(primary("打开后台高级设置", () -> open("/advanced-settings.html")));
        Button logout = button("退出管理账号", () -> new AlertDialog.Builder(this).setTitle("退出管理账号")
                .setMessage("将撤销此设备的服务端会话，并清除本机登录状态。")
                .setNegativeButton("取消", null).setPositiveButton("退出", (dialog, which) -> clearSession(null)).show());
        logout.setTextColor(0xffb45049); page.addView(logout, new LinearLayout.LayoutParams(-1, dp(56)));
        paragraph(page, "Leon 管理  " + BuildConfig.VERSION_NAME + "\nAndroid 8.0 及以上 · 通过 HTTPS 同步后台功能");
    }

    private void open(String path) {
        if (accountBusy || !knownPath(path)) return;
        screen = "web"; nativePane.setVisibility(View.GONE); browser.view.setVisibility(View.VISIBLE); browser.view.onResume(); hideError();
        currentPath = path; location(path); refresh.setVisibility(View.VISIBLE); browser.open(path);
    }

    private boolean knownPath(String path) {
        if (path == null) return false;
        String clean = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        return clean.equals("/") || clean.equals("/json-record.html") || clean.equals("/diamond.html")
                || modules.stream().anyMatch(module -> module.path.equals(clean));
    }

    private void showModules() {
        if (accountBusy) return;
        String[] names = modules.stream().map(Module::title).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("全部管理功能")
                .setItems(names, (dialog, position) -> open(modules.get(position).path))
                .setNegativeButton("关闭", null).show();
    }

    private void clearSession(ServerAddress next) {
        if (accountBusy) return;
        accountBusy = true; progress.setVisibility(View.VISIBLE); progress.setIndeterminate(true);
        String origin = server.origin(); String cookie = CookieManager.getInstance().getCookie(origin);
        network.execute(() -> {
            boolean success = cookie == null || !cookie.contains("__Host-leon_session=");
            if (!success) {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(origin + "/auth/logout").openConnection();
                    connection.setConnectTimeout(15000); connection.setReadTimeout(15000); connection.setInstanceFollowRedirects(false);
                    connection.setRequestMethod("POST"); connection.setRequestProperty("Origin", origin);
                    connection.setRequestProperty("X-Requested-With", "XMLHttpRequest"); connection.setRequestProperty("Cookie", cookie);
                    connection.setDoOutput(true); connection.setFixedLengthStreamingMode(0); connection.getOutputStream().close();
                    int status = connection.getResponseCode(); success = status == 204 || status == 200;
                } catch (Exception ignored) { /* Never log session credentials or server responses. */ }
                finally { if (connection != null) connection.disconnect(); }
            }
            boolean revoked = success;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                progress.setIndeterminate(false); progress.setVisibility(View.INVISIBLE);
                if (!revoked) { accountBusy = false; toast("无法撤销服务端会话，请检查网络后重试"); return; }
                browser.view.stopLoading();
                CookieManager.getInstance().removeAllCookies(removed -> {
                    CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData();
                    content.removeView(browser.view); content.removeView(errorPane); browser.close();
                    if (next != null) { server = next; preferences.edit().putString("server", next.origin()).remove("lastPath").apply(); }
                    createBrowser(); accountBusy = false; home(); toast(next == null ? "已退出管理账号" : "服务地址已更新");
                });
            });
        });
    }

    @Override public void progress(int value) {
        if (accountBusy) return;
        progress.setProgress(value); progress.setVisibility(screen.equals("web") && value < 100 ? View.VISIBLE : View.INVISIBLE);
    }
    @Override public void location(String path) {
        if (!screen.equals("web")) return;
        String clean = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        String name = clean.equals("/admin-login.html") ? "管理登录" : clean.equals("/json-record.html") ? "JSON 记录详情" : "后台总览";
        for (Module module : modules) if (module.path.equals(clean)) name = module.title;
        title.setText(name); subtitle.setText(server.origin());
        if (knownPath(path)) { currentPath = path; preferences.edit().putString("lastPath", path).apply(); }
        selectTab(clean.equals("/monitor.html") ? 2 : clean.equals("/workflows.html") || clean.equals("/workflow-runs.html") ? 1 : -1);
    }
    @Override public void error(String message) {
        if (!screen.equals("web")) return;
        errorText.setText(message); errorPane.setVisibility(View.VISIBLE); progress.setVisibility(View.INVISIBLE);
    }
    private void hideError() { errorPane.setVisibility(View.GONE); }
    private void showNative(String name, String heading) {
        screen = name; title.setText(heading); subtitle.setText(server.origin()); refresh.setVisibility(View.GONE);
        browser.view.setVisibility(View.GONE); browser.view.onPause(); nativePane.setVisibility(View.VISIBLE); nativePane.removeAllViews(); hideError();
        progress.setVisibility(View.INVISIBLE); selectTab(name.equals("home") ? 0 : 3);
    }
    private void selectTab(int selected) {
        for (int i = 0; i < tabs.size(); i++) {
            NavigationTab tab = tabs.get(i); boolean active = i == selected;
            tab.container.setSelected(active);
            tab.label.setTextColor(active ? BLUE : MUTED);
            tab.icon.setImageTintList(ColorStateList.valueOf(active ? BLUE : MUTED));
            tab.indicator.animate().cancel();
            tab.indicator.animate().alpha(active ? 1f : 0f).setDuration(140).start();
        }
    }
    private LinearLayout page() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); nativePane.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout page = vertical(); page.setPadding(dp(20), dp(20), dp(12), dp(24)); scroll.addView(page); return page;
    }
    private void paragraph(LinearLayout parent, String value) {
        TextView text = text(value, 13, MUTED, false); text.setLineSpacing(dp(4), 1); text.setPadding(0, dp(14), dp(8), dp(18)); parent.addView(text);
    }
    private Switch toggle(String label, boolean checked) {
        Switch control = new Switch(this); control.setText(label); control.setTextSize(14); control.setTextColor(INK); control.setChecked(checked);
        control.setPadding(0, dp(20), dp(8), dp(16)); control.setMinimumHeight(dp(64)); return control;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView label = new TextView(this); label.setText(value); label.setTextSize(size); label.setTextColor(color);
        if (bold) label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return label;
    }
    private Button button(String label, Runnable action) {
        Button result = new Button(this); result.setText(label); result.setAllCaps(false); result.setTextSize(14); result.setTextColor(BLUE);
        result.setMinWidth(0); result.setMinimumWidth(0); result.setPadding(dp(10), 0, dp(10), 0); result.setBackground(shape(Color.TRANSPARENT, 10));
        result.setOnClickListener(view -> action.run()); return result;
    }
    private Button primary(String label, Runnable action) {
        Button result = button(label, action); result.setTextColor(Color.WHITE); result.setBackground(shape(BLUE, 10)); result.setMinimumHeight(dp(50));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2); size.setMargins(0, dp(4), dp(8), dp(14)); result.setLayoutParams(size); return result;
    }
    private GradientDrawable shape(int color, int radius) { GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(dp(radius)); return background; }
    private LinearLayout vertical() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout horizontal() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private void loadModules() {
        try (var input = getAssets().open("modules.json"); var buffer = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int count; while ((count = input.read(bytes)) != -1) buffer.write(bytes, 0, count);
            JSONArray values = new JSONArray(buffer.toString(StandardCharsets.UTF_8.name()));
            for (int i = 0; i < values.length(); i++) {
                JSONObject value = values.getJSONObject(i);
                modules.add(new Module(value.getString("title"), value.getString("description"), value.getString("path"), value.getString("group"), value.getString("icon")));
            }
        } catch (Exception error) { throw new IllegalStateException("Missing Android module catalog", error); }
    }
    private void back() {
        if (accountBusy) return;
        if (screen.equals("web") && browser.view.canGoBack()) { hideError(); browser.view.goBack(); }
        else if (!screen.equals("home")) home();
        else finish();
    }
    @Override public void onBackPressed() { back(); }
    @Override protected void onActivityResult(int request, int result, Intent data) { super.onActivityResult(request, result, data); browser.result(request, result, data); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("screen", screen); state.putString("path", currentPath); super.onSaveInstanceState(state); }
    @Override protected void onPause() { CookieManager.getInstance().flush(); browser.view.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (browser != null && screen.equals("web")) browser.view.onResume(); }
    @Override protected void onDestroy() { browser.close(); network.shutdownNow(); super.onDestroy(); }
}
