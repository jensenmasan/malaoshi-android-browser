package com.malaoshi.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 马老师专属 —— 基于 GeckoView(Firefox 内核)的安卓浏览器。
 * 主页为内置 assets/home.html,含"马老师祝您新年快乐"。
 */
public class MainActivity extends Activity {

    private static final String HOME_URL = "file:///android_asset/home.html";
    private static final String SEARCH_PREFIX = "https://cn.bing.com/search?q=";
    static final String APP_VERSION = "1.1";

    private GeckoRuntime runtime;
    private GeckoView geckoView;
    private EditText urlBar;
    private ImageButton btnBack;
    private ImageButton btnForward;
    private ProgressBar progressBar;

    // 授权 / 试用
    private View browserRoot;
    private View licenseGate;
    private TextView gateMessage;
    private TextView deviceCodeView;
    private TextView gateStatus;
    private TextView trialBanner;
    private TextView btnLater;
    private Button btnActivate;
    private EditText licenseInput;
    private boolean gateForced = false;
    private boolean licenseBusy = false;

    private final List<Tab> tabs = new ArrayList<Tab>();
    private int currentIndex = -1;

    private static class Tab {
        final GeckoSession session;
        String title;
        String url = "";
        boolean canGoBack = false;
        boolean canGoForward = false;

        Tab(GeckoSession session, String title) {
            this.session = session;
            this.title = title;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlBar = (EditText) findViewById(R.id.urlBar);
        btnBack = (ImageButton) findViewById(R.id.btnBack);
        btnForward = (ImageButton) findViewById(R.id.btnForward);
        ImageButton btnReload = (ImageButton) findViewById(R.id.btnReload);
        ImageButton btnMenu = (ImageButton) findViewById(R.id.btnMenu);
        progressBar = (ProgressBar) findViewById(R.id.progress);
        FrameLayout content = (FrameLayout) findViewById(R.id.content);

        runtime = GeckoRuntime.getDefault(this);

        geckoView = new GeckoView(this);
        geckoView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(geckoView);

        bindLicenseUi();
        primeLicenseUi();
        checkLicense(false);

        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Tab t = currentTab();
                if (t != null) t.session.goBack();
            }
        });

        btnForward.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Tab t = currentTab();
                if (t != null) t.session.goForward();
            }
        });

        btnReload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Tab t = currentTab();
                if (t != null) t.session.reload();
            }
        });

        btnMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });

        urlBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    navigate(urlBar.getText().toString());
                    return true;
                }
                return false;
            }
        });

        urlBar.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    urlBar.selectAll();
                }
            }
        });

        String startUrl = HOME_URL;
        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())
                && intent.getData() != null) {
            startUrl = intent.getData().toString();
        }
        newTab(startUrl);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())
                && intent.getData() != null) {
            newTab(intent.getData().toString());
        }
    }

    // ---------------- 标签页 ----------------

    private Tab currentTab() {
        if (currentIndex < 0 || currentIndex >= tabs.size()) return null;
        return tabs.get(currentIndex);
    }

    private boolean isCurrent(Tab tab) {
        return currentTab() == tab;
    }

    private void newTab() {
        newTab(HOME_URL);
    }

    private void newTab(String url) {
        GeckoSession session = new GeckoSession();
        final Tab tab = new Tab(session, getString(R.string.new_tab));

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(GeckoSession s, String location,
                                         List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                         Boolean hasUserGesture) {
                if (location == null) return;
                tab.url = location;
                if (isCurrent(tab)) updateUrlBar(location);
            }

            @Override
            public void onCanGoBack(GeckoSession s, boolean value) {
                tab.canGoBack = value;
                if (isCurrent(tab)) refreshNavButtons();
            }

            @Override
            public void onCanGoForward(GeckoSession s, boolean value) {
                tab.canGoForward = value;
                if (isCurrent(tab)) refreshNavButtons();
            }
        });

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession s, String pageUrl) {
                if (isCurrent(tab)) {
                    progressBar.setProgress(0);
                    progressBar.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onProgressChange(GeckoSession s, int progress) {
                if (isCurrent(tab)) {
                    progressBar.setProgress(progress);
                    if (progress >= 100) progressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public void onPageStop(GeckoSession s, boolean success) {
                if (isCurrent(tab)) progressBar.setVisibility(View.GONE);
            }
        });

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(GeckoSession s, String title) {
                if (!TextUtils.isEmpty(title)) tab.title = title;
            }
        });

        tabs.add(tab);
        session.open(runtime);
        switchTo(tabs.size() - 1);
        session.loadUri(url);
    }

    private void switchTo(int index) {
        if (index < 0 || index >= tabs.size()) return;
        currentIndex = index;
        Tab tab = tabs.get(index);
        geckoView.setSession(tab.session);
        progressBar.setVisibility(View.GONE);
        refreshNavButtons();
        updateUrlBar(tab.url);
        urlBar.clearFocus();
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab tab = tabs.get(index);
        boolean wasCurrent = (index == currentIndex);
        if (wasCurrent) geckoView.setSession(null);
        tabs.remove(index);
        try {
            if (tab.session.isOpen()) tab.session.close();
        } catch (Exception ignored) {
        }

        if (tabs.isEmpty()) {
            currentIndex = -1;
            newTab();
            return;
        }
        if (wasCurrent) {
            currentIndex = Math.min(index, tabs.size() - 1);
        } else if (index < currentIndex) {
            currentIndex--;
        }
        switchTo(currentIndex);
    }

    // ---------------- 界面刷新 ----------------

    private void refreshNavButtons() {
        Tab t = currentTab();
        boolean back = t != null && t.canGoBack;
        boolean forward = t != null && t.canGoForward;
        btnBack.setEnabled(back);
        btnBack.setAlpha(back ? 1f : 0.3f);
        btnForward.setEnabled(forward);
        btnForward.setAlpha(forward ? 1f : 0.3f);
    }

    private void updateUrlBar(String url) {
        if (urlBar.hasFocus()) return;
        if (url == null || url.startsWith("file:///android_asset/")) {
            urlBar.setText("");
        } else {
            urlBar.setText(url);
        }
    }

    // ---------------- 导航 ----------------

    private void navigate(String input) {
        Tab t = currentTab();
        if (t == null) return;
        String target = toTarget(input);
        if (target == null) return;
        urlBar.clearFocus();
        t.session.loadUri(target);
    }

    private String toTarget(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
        if (s.contains("://")) return s;
        if (s.startsWith("file:") || s.startsWith("about:") || s.startsWith("resource:")) return s;
        if (s.matches("^[\\w\\-]+(\\.[\\w\\-]+)+(/.*)?$") || s.startsWith("localhost")) {
            return "https://" + s;
        }
        try {
            return SEARCH_PREFIX + URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return SEARCH_PREFIX + s;
        }
    }

    private void goHome() {
        Tab t = currentTab();
        if (t != null) t.session.loadUri(HOME_URL);
    }

    // ---------------- 菜单与对话框 ----------------

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, getString(R.string.menu_new_tab));
        popup.getMenu().add(0, 2, 1, getString(R.string.menu_tabs));
        popup.getMenu().add(0, 3, 2, getString(R.string.menu_home));
        popup.getMenu().add(0, 4, 3, getString(R.string.menu_close_tab));
        popup.getMenu().add(0, 5, 4, getString(R.string.menu_about));
        popup.getMenu().add(0, 6, 5, getString(R.string.license_menu));
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        newTab();
                        return true;
                    case 2:
                        showTabsDialog();
                        return true;
                    case 3:
                        goHome();
                        return true;
                    case 4:
                        closeTab(currentIndex);
                        return true;
                    case 5:
                        showAbout();
                        return true;
                    case 6:
                        openLicenseScreen();
                        return true;
                    default:
                        return false;
                }
            }
        });
        popup.show();
    }

    private void showTabsDialog() {
        final String[] items = new String[tabs.size()];
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            String label = !TextUtils.isEmpty(t.title) ? t.title : t.url;
            if (TextUtils.isEmpty(label)) label = getString(R.string.new_tab);
            items[i] = (i == currentIndex ? "●  " : "     ") + label;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_tabs)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switchTo(which);
                    }
                })
                .setPositiveButton(R.string.menu_new_tab, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        newTab();
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void showAbout() {
        String message = getString(R.string.about_version) + "：" + APP_VERSION + "\n"
                + getString(R.string.about_engine) + "：" + getString(R.string.about_engine_value) + "\n\n"
                + getString(R.string.about_note);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.about_title) + " · " + getString(R.string.about_app))
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    // ---------------- 生命周期 ----------------

    @Override
    public void onBackPressed() {
        // 试用到期被拦在授权页时，返回键不生效，避免绕过。
        if (gateForced && licenseGate.getVisibility() == View.VISIBLE) return;
        Tab t = currentTab();
        if (t != null && t.canGoBack) {
            t.session.goBack();
            return;
        }
        if (tabs.size() > 1) {
            closeTab(currentIndex);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到前台时补一次校验：试用到期后立刻拦住，激活后立刻放行。
        if (licenseGate == null || licenseBusy) return;
        if (gateForced && licenseGate.getVisibility() == View.VISIBLE) return;
        if (!LicenseManager.isActivated(this)) checkLicense(false);
    }

    @Override
    protected void onDestroy() {
        for (Tab t : tabs) {
            try {
                if (t.session.isOpen()) t.session.close();
            } catch (Exception ignored) {
            }
        }
        tabs.clear();
        super.onDestroy();
    }

    // ---------------- 授权 / 试用 ----------------

    private void bindLicenseUi() {
        browserRoot = findViewById(R.id.browserRoot);
        licenseGate = findViewById(R.id.licenseGate);
        gateMessage = (TextView) findViewById(R.id.gateMessage);
        deviceCodeView = (TextView) findViewById(R.id.deviceCode);
        gateStatus = (TextView) findViewById(R.id.gateStatus);
        trialBanner = (TextView) findViewById(R.id.trialBanner);
        btnLater = (TextView) findViewById(R.id.btnLater);
        licenseInput = (EditText) findViewById(R.id.licenseInput);
        btnActivate = (Button) findViewById(R.id.btnActivate);

        deviceCodeView.setText(LicenseManager.deviceId(this));

        findViewById(R.id.btnCopyDevice).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyDeviceCode();
            }
        });

        findViewById(R.id.btnCheckAgain).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkLicense(true);
            }
        });

        btnLater.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!gateForced) showBrowser();
            }
        });

        btnActivate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doActivate();
            }
        });

        licenseInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    doActivate();
                    return true;
                }
                return false;
            }
        });
    }

    /** 启动瞬间先用本地缓存决定显示浏览器还是授权页，避免白屏等待网络。 */
    private void primeLicenseUi() {
        if (LicenseManager.isActivated(this)) {
            showBrowser();
            return;
        }
        if (LicenseManager.trialValid(this)) {
            showBrowser();
            showTrialBanner(LicenseManager.trialDaysLeft(this));
            return;
        }
        if (LicenseManager.trialEndsAt(this) > 0) {
            // 本地已知试用结束，先拦住，等服务器确认。
            showGate(true, null);
        }
        // 首次安装（本地无记录）：先放行，由异步校验决定。
    }

    private void checkLicense(final boolean userTriggered) {
        if (licenseBusy) return;
        setLicenseBusy(true);
        if (userTriggered && gateStatus != null) gateStatus.setText(R.string.license_checking);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final LicenseManager.Status status = LicenseManager.fetchStatus(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setLicenseBusy(false);
                        applyStatus(status, userTriggered);
                    }
                });
            }
        }).start();
    }

    private void applyStatus(LicenseManager.Status status, boolean userTriggered) {
        if (status.activated) {
            showBrowser();
            showTrialBanner(0);
            if (userTriggered) toast(getString(R.string.license_activated_ok));
            return;
        }
        if (status.reachable) {
            if (status.trialExpired) {
                showGate(true, null);
            } else {
                showBrowser();
                showTrialBanner(status.daysLeft);
            }
            return;
        }
        // 服务器不可达：按本地缓存判断，拿不准时宁可放行，不要把人挡在门外。
        if (LicenseManager.trialEndsAt(this) > 0 && !LicenseManager.trialValid(this)) {
            showGate(true, getString(R.string.license_offline));
        } else {
            showBrowser();
            if (LicenseManager.trialValid(this)) showTrialBanner(LicenseManager.trialDaysLeft(this));
        }
    }

    private void doActivate() {
        if (licenseBusy) return;
        final String code = licenseInput.getText().toString();
        setLicenseBusy(true);
        gateStatus.setText(R.string.license_activating);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final LicenseManager.ActivateResult result = LicenseManager.activate(MainActivity.this, code);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setLicenseBusy(false);
                        if (result.ok) {
                            licenseInput.setText("");
                            gateStatus.setText("");
                            showBrowser();
                            showTrialBanner(0);
                            toast(getString(R.string.license_activated_ok));
                        } else {
                            gateStatus.setText(result.message);
                        }
                    }
                });
            }
        }).start();
    }

    private void openLicenseScreen() {
        if (LicenseManager.isActivated(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.license_menu)
                    .setMessage("本机已激活，永久有效。\n\n设备码：\n" + LicenseManager.deviceId(this))
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }
        showGate(false, null);
        checkLicense(true);
    }

    private void showGate(boolean forced, String message) {
        gateForced = forced;
        if (message != null) gateMessage.setText(message);
        else gateMessage.setText(R.string.license_expired);
        btnLater.setVisibility(forced ? View.GONE : View.VISIBLE);
        deviceCodeView.setText(LicenseManager.deviceId(this));
        gateStatus.setText("");
        licenseGate.setVisibility(View.VISIBLE);
        browserRoot.setVisibility(View.GONE);
    }

    private void showBrowser() {
        gateForced = false;
        licenseGate.setVisibility(View.GONE);
        browserRoot.setVisibility(View.VISIBLE);
    }

    private void showTrialBanner(int daysLeft) {
        if (daysLeft <= 0) {
            trialBanner.setVisibility(View.GONE);
            return;
        }
        trialBanner.setText(getString(R.string.license_trial_days, daysLeft));
        trialBanner.setVisibility(View.VISIBLE);
    }

    private void setLicenseBusy(boolean busy) {
        licenseBusy = busy;
        if (btnActivate != null) btnActivate.setEnabled(!busy);
    }

    private void copyDeviceCode() {
        String code = LicenseManager.deviceId(this);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("device", code));
        toast(getString(R.string.license_copied));
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
