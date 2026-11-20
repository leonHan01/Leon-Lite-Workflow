package com.leon.workflow.admin;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebResourceRequest;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import java.util.Map;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, qualifiers = "w393dp-h851dp-port")
public class MainActivityTest {
    @Test public void everyWorkbenchEntryUsesProtectedNavigationForOpenRefreshAndLogin() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            ViewGroup root = activity.findViewById(android.R.id.content);
            String catalog;
            try (java.util.Scanner input = new java.util.Scanner(activity.getAssets().open("modules.json"), "UTF-8")) {
                catalog = input.useDelimiter("\\A").next();
            }
            org.json.JSONArray modules = new org.json.JSONArray(catalog);
            assertEquals(13, modules.length());
            for (int i = 0; i < modules.length(); i++) {
                org.json.JSONObject module = modules.getJSONObject(i);
                String destination = module.getString("path"), name = module.getString("title");
                findDescription(root, "工作台").performClick();
                ((View) findText(root, name).getParent()).performClick();
                WebView browser = find(root, WebView.class);
                // A previously loaded document may still be on its login page.
                if (shadowOf(browser).getLastEvaluatedJavascript().contains("document.getElementById('workspaceViews')"))
                    shadowOf(browser).getLastEvaluatedJavascriptCallback().onReceiveValue("false");
                assertEquals(name, "https://121.40.254.112/index.html", shadowOf(browser).getLastLoadedUrl());
                finishWorkspaceLoad(browser);
                assertRouteNavigation(browser, destination);
                shadowOf(browser).getLastEvaluatedJavascriptCallback().onReceiveValue("true");
                shadowOf(browser).pushEntryToHistory("https://121.40.254.112" + destination);
                shadowOf(browser).getWebViewClient().doUpdateVisitedHistory(browser, browser.getUrl(), false);
                findText(root, "↻").performClick();
                assertEquals(name, "https://121.40.254.112/index.html", shadowOf(browser).getLastLoadedUrl());
                assertEquals(name, 0, shadowOf(browser).getReloadInvocations());
                finishWorkspaceLoad(browser);
                assertRouteNavigation(browser, destination);
                shadowOf(browser).getLastEvaluatedJavascriptCallback().onReceiveValue("true");
                String login = "https://121.40.254.112/admin-login.html";
                browser.loadUrl(login);
                shadowOf(browser).getWebViewClient().onPageStarted(browser, login, null);
                finishWorkspaceLoad(browser);
                shadowOf(browser).getLastEvaluatedJavascriptCallback().onReceiveValue("false");
                assertEquals(name + " login must remain visible", login, shadowOf(browser).getLastLoadedUrl());
                WebResourceRequest redirect = request("https://121.40.254.112" + destination);
                boolean intercepted = shadowOf(browser).getWebViewClient().shouldOverrideUrlLoading(browser, redirect);
                if (destination.equals("/index.html")) {
                    assertFalse(intercepted);
                    browser.loadUrl(redirect.getUrl().toString());
                } else assertTrue(name, intercepted);
                assertEquals(name, "https://121.40.254.112/index.html", shadowOf(browser).getLastLoadedUrl());
                finishWorkspaceLoad(browser);
                assertRouteNavigation(browser, destination);
                shadowOf(browser).getLastEvaluatedJavascriptCallback().onReceiveValue("true");
            }
        }
    }

    @Test public void statisticsFromWorkbenchBootstrapsThroughTheProtectedWorkspace() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            ViewGroup root = activity.findViewById(android.R.id.content);
            ((View) findText(root, "歌曲统计").getParent()).performClick();
            WebView browser = find(root, WebView.class);
            assertEquals("Statistics must not request its proxy-denied HTML path",
                    "https://121.40.254.112/index.html", shadowOf(browser).getLastLoadedUrl());
            assertEquals(View.VISIBLE, browser.getVisibility());
            finishWorkspaceLoad(browser);
            assertStatisticsNavigation(browser);
            assertTrue(shadowOf(browser).getLastEvaluatedJavascript().contains("history.replaceState(null,'',"));
            assertTrue(shadowOf(browser).getLastEvaluatedJavascript().contains("historyMode:\"none\""));
            shadowOf(browser).getLastEvaluatedJavascriptCallback().onReceiveValue("true");
            assertEquals("https://121.40.254.112/index.html", shadowOf(browser).getLastLoadedUrl());
        }
    }

    @Test public void refreshingStatisticsReturnsThroughTheWorkspaceShell() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            ViewGroup root = activity.findViewById(android.R.id.content);
            ((View) findText(root, "歌曲统计").getParent()).performClick();
            WebView browser = find(root, WebView.class);
            finishWorkspaceLoad(browser);
            shadowOf(browser).getLastEvaluatedJavascriptCallback().onReceiveValue("true");
            // History API navigation changes the visible URL without an HTTP request.
            shadowOf(browser).pushEntryToHistory("https://121.40.254.112/music-statistics.html");
            shadowOf(browser).getWebViewClient().doUpdateVisitedHistory(browser, browser.getUrl(), false);
            findText(root, "↻").performClick();
            assertEquals("Refresh must not issue a request to the blocked statistics HTML",
                    "https://121.40.254.112/index.html", shadowOf(browser).getLastLoadedUrl());
            assertEquals(0, shadowOf(browser).getReloadInvocations());
            finishWorkspaceLoad(browser);
            assertStatisticsNavigation(browser);
        }
    }

    @Test public void statisticsTargetSurvivesLoginAndRedirectsThroughTheProtectedEntry() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            ViewGroup root = activity.findViewById(android.R.id.content);
            ((View) findText(root, "歌曲统计").getParent()).performClick();
            WebView browser = find(root, WebView.class);
            String login = "https://121.40.254.112/admin-login.html";
            browser.loadUrl(login);
            shadowOf(browser).getWebViewClient().onPageStarted(browser, login, null);
            finishWorkspaceLoad(browser);
            shadowOf(browser).getLastEvaluatedJavascriptCallback().onReceiveValue("false");
            assertEquals("A login page must not trigger a bootstrap loop", login, shadowOf(browser).getLastLoadedUrl());
            WebResourceRequest redirect = new WebResourceRequest() {
                public Uri getUrl() { return Uri.parse("https://121.40.254.112/music-statistics.html"); }
                public boolean isForMainFrame() { return true; }
                public boolean isRedirect() { return true; }
                public boolean hasGesture() { return false; }
                public String getMethod() { return "GET"; }
                public Map<String, String> getRequestHeaders() { return java.util.Collections.emptyMap(); }
            };
            assertTrue(shadowOf(browser).getWebViewClient().shouldOverrideUrlLoading(browser, redirect));
            assertEquals("https://121.40.254.112/index.html", shadowOf(browser).getLastLoadedUrl());
            finishWorkspaceLoad(browser);
            assertStatisticsNavigation(browser);
        }
    }

    @Test public void opensWorkflowsAndReturnsToNativeHome() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            ViewGroup root = activity.findViewById(android.R.id.content);
            assertNotNull(findText(root, "后台总览"));
            assertNotNull(findText(root, "歌曲统计"));
            assertNotNull(findText(root, "数据库管理"));
            View card = (View) findText(root, "工作流编排").getParent(); card.performClick();
            WebView browser = find(root, WebView.class);
            assertEquals("https://121.40.254.112/index.html", shadowOf(browser).getLastLoadedUrl());
            assertEquals(View.VISIBLE, browser.getVisibility());
            activity.onBackPressed();
            assertEquals(View.GONE, browser.getVisibility());
            assertNotNull(findText(root, "管理日常，\n让工作流随行。"));
        }
    }

    @Test public void filtersModulesAndValidatesTheServerAddress() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            ViewGroup root = activity.findViewById(android.R.id.content);
            find(root, EditText.class).setText("数据库");
            assertNotNull(findText(root, "数据库管理"));
            assertNull(findText(root, "歌曲管理"));
            ((View) findText(root, "设置").getParent()).performClick();
            EditText address = find(root, EditText.class); address.setText("http://insecure.example");
            findText(root, "保存服务地址").performClick();
            assertNotNull(address.getError());
            assertEquals(ServerAddress.DEFAULT, activity.getSharedPreferences("client", 0).getString("server", ServerAddress.DEFAULT));
        }
    }

    @Test public void disablesLocalFilesAndAcceptsOnlyChosenContentUploads() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            ViewGroup root = activity.findViewById(android.R.id.content);
            ((View) findText(root, "歌曲管理").getParent()).performClick();
            WebView browser = find(root, WebView.class);
            assertFalse(browser.getSettings().getAllowFileAccess());
            assertFalse(browser.getSettings().getAllowContentAccess());
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, browser.getSettings().getMixedContentMode());
            final Uri[][] selection = {null};
            WebChromeClient.FileChooserParams params = new WebChromeClient.FileChooserParams() {
                public int getMode() { return MODE_OPEN_MULTIPLE; }
                public String[] getAcceptTypes() { return new String[]{"audio/*"}; }
                public boolean isCaptureEnabled() { return false; }
                public CharSequence getTitle() { return "歌曲"; }
                public String getFilenameHint() { return null; }
                public Intent createIntent() { return new Intent(); }
            };
            ValueCallback<Uri[]> callback = uris -> selection[0] = uris;
            shadowOf(browser).getWebChromeClient().onShowFileChooser(browser, callback, params);
            Intent picker = shadowOf(activity).getNextStartedActivity();
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.getAction());
            assertTrue(picker.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
            Intent result = new Intent();
            ClipData clip = ClipData.newRawUri("music", Uri.parse("content://documents/audio/1"));
            clip.addItem(new ClipData.Item(Uri.parse("file:///data/private-file")));
            clip.addItem(new ClipData.Item(Uri.parse("content://documents/audio/2")));
            result.setClipData(clip);
            activity.onActivityResult(WorkspaceBrowser.REQUEST_UPLOAD, MainActivity.RESULT_OK, result);
            assertArrayEquals(new Uri[]{Uri.parse("content://documents/audio/1"), Uri.parse("content://documents/audio/2")}, selection[0]);
        }
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView label && text.contentEquals(label.getText())) return label;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            TextView result = findText(group.getChildAt(i), text); if (result != null) return result;
        }
        return null;
    }
    private static void finishWorkspaceLoad(WebView browser) {
        shadowOf(browser).getWebViewClient().onPageFinished(browser, browser.getUrl());
        // Deliver the injection callback; the next script performs the real workspace navigation.
        shadowOf(browser).getLastEvaluatedJavascriptCallback().onReceiveValue("null");
    }
    private static void assertStatisticsNavigation(WebView browser) {
        assertRouteNavigation(browser, "/music-statistics.html");
    }
    private static void assertRouteNavigation(WebView browser, String destination) {
        String script = shadowOf(browser).getLastEvaluatedJavascript();
        // Android's JSON quoting may escape slashes; both forms denote the same route.
        assertTrue(script, script.replace("\\/", "/").contains("m.navigate(\"" + destination + "\""));
        assertFalse(script, script.contains("location.assign"));
    }
    private static View findDescription(View view, String description) {
        if (view.isClickable() && description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View result = findDescription(group.getChildAt(i), description); if (result != null) return result;
        }
        return null;
    }
    private static WebResourceRequest request(String url) {
        return new WebResourceRequest() {
            public Uri getUrl() { return Uri.parse(url); }
            public boolean isForMainFrame() { return true; }
            public boolean isRedirect() { return true; }
            public boolean hasGesture() { return false; }
            public String getMethod() { return "GET"; }
            public Map<String, String> getRequestHeaders() { return java.util.Collections.emptyMap(); }
        };
    }
    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            T result = find(group.getChildAt(i), type); if (result != null) return result;
        }
        return null;
    }
}
