package com.youqi.studio;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class MainActivity extends Activity implements AgentClient.Listener {
    private static final int PICK_PROJECT = 41;
    private static final int REQUEST_TERMUX = 42;
    private static final int PICK_CHARACTER = 43;
    private static final String STATE_PREFS = "pocket_agent_state";

    private static final int BG = Color.rgb(15, 18, 23);
    private static final int PANEL = Color.rgb(23, 28, 35);
    private static final int PANEL_2 = Color.rgb(31, 38, 47);
    private static final int LINE = Color.rgb(49, 58, 69);
    private static final int TEXT = Color.rgb(234, 238, 243);
    private static final int MUTED = Color.rgb(151, 163, 176);
    private static final int GREEN = Color.rgb(71, 184, 129);
    private static final int USER = Color.rgb(36, 111, 82);
    private static final int BLUE = Color.rgb(110, 168, 254);
    private static final int AMBER = Color.rgb(230, 163, 74);
    private static final int RED = Color.rgb(235, 99, 106);

    private AgentClient agent;
    private TermuxBridge termux;
    private McpManager mcp;
    private TermuxSetup termuxSetup;
    private ProjectStore project;
    private SecurePrefs securePrefs;
    private CharacterCard character;
    private final List<ChatEvent> events = new ArrayList<>();
    private final List<Button> navButtons = new ArrayList<>();
    private FrameLayout content;
    private TextView projectLabel;
    private TextView statusLabel;
    private ProgressBar progress;
    private LinearLayout messageList;
    private ScrollView messageScroll;
    private EditText promptInput;
    private Button sendButton;
    private ListView fileList;
    private ArrayAdapter<String> fileAdapter;
    private List<ProjectStore.Entry> displayedFiles = new ArrayList<>();
    private LinearLayout filesBrowser;
    private LinearLayout editorPane;
    private TextView editorPath;
    private EditText editor;
    private ProjectStore.Entry currentFile;
    private EditText endpointInput;
    private EditText modelInput;
    private EditText apiKeyInput;
    private Spinner providerSpinner;
    private Spinner executionSpinner;
    private ProviderCatalog.Provider selectedProvider;
    private EditText authServerInput;
    private EditText authUsernameInput;
    private EditText authDisplayNameInput;
    private EditText authPasswordInput;
    private CheckBox authConsent;
    private boolean registerMode;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        securePrefs = new SecurePrefs(this);
        verifySession();
    }

    private void verifySession() {
        String token;
        try { token = securePrefs.getAuthToken(); }
        catch (Exception error) { securePrefs.clearSession(); showAuthScreen(false, "登录信息无法读取，请重新登录"); return; }
        if (token.isEmpty() || securePrefs.getBackendUrl().isEmpty()) { showAuthScreen(false, ""); return; }
        showAuthLoading("正在验证账号");
        final String savedToken = token;
        new Thread(() -> {
            try {
                JSONObject response = AuthClient.me(securePrefs.getBackendUrl(), savedToken);
                runOnUiThread(() -> acceptSession(response));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (error instanceof AuthClient.AuthException && ((AuthClient.AuthException) error).status == 401) securePrefs.clearSession();
                    showAuthScreen(false, "账号验证失败：" + error.getMessage());
                });
            }
        }, "YouQi-Auth-Verify").start();
    }

    private void showAuthLoading(String status) {
        LinearLayout page = authPage();
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(GREEN));
        page.addView(spinner, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView label = text(status, 14, MUTED, Typeface.NORMAL);
        label.setPadding(0, dp(14), 0, 0); page.addView(label);
        setContentView(page);
    }

    private void showAuthScreen(boolean register, String errorMessage) {
        registerMode = register;
        LinearLayout page = authPage();
        TextView brand = text("油漆", 32, TEXT, Typeface.BOLD);
        page.addView(brand);
        TextView subtitle = text(register ? "创建账号后开始使用" : "登录你的 Agent 工作台", 14, MUTED, Typeface.NORMAL);
        subtitle.setPadding(0, dp(6), 0, dp(24)); page.addView(subtitle);

        authServerInput = edit("https://你的服务器域名", true);
        authServerInput.setText(securePrefs.getBackendUrl());
        page.addView(authLabel("账号服务器")); page.addView(authServerInput, authFieldParams());
        authUsernameInput = edit("4-24 位字母、数字或下划线", true);
        page.addView(authLabel("用户名")); page.addView(authUsernameInput, authFieldParams());
        if (register) {
            authDisplayNameInput = edit("在应用中显示的名称", true);
            page.addView(authLabel("显示名称")); page.addView(authDisplayNameInput, authFieldParams());
        }
        authPasswordInput = edit("至少 8 位", true);
        authPasswordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        page.addView(authLabel("密码")); page.addView(authPasswordInput, authFieldParams());
        Button submit = button(register ? "注册并登录" : "登录", true);
        submit.setOnClickListener(v -> submitAuth());
        LinearLayout.LayoutParams submitParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        submitParams.setMargins(0, dp(12), 0, dp(8)); page.addView(submit, submitParams);
        Button toggle = button(register ? "已有账号，返回登录" : "没有账号，创建一个", false);
        toggle.setOnClickListener(v -> showAuthScreen(!registerMode, ""));
        page.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        authConsent = new CheckBox(this);
        authConsent.setText("我已阅读并同意用户协议和隐私政策");
        authConsent.setTextColor(MUTED); authConsent.setTextSize(11); authConsent.setButtonTintList(ColorStateList.valueOf(GREEN));
        authConsent.setGravity(Gravity.CENTER); page.addView(authConsent);
        LinearLayout legal = new LinearLayout(this); legal.setGravity(Gravity.CENTER);
        Button terms = button("用户协议", false); terms.setOnClickListener(v -> showLegalDialog(false));
        Button privacy = button("隐私政策", false); privacy.setOnClickListener(v -> showLegalDialog(true));
        legal.addView(terms, new LinearLayout.LayoutParams(dp(110), dp(38)));
        LinearLayout.LayoutParams privacyParams = new LinearLayout.LayoutParams(dp(110), dp(38));
        privacyParams.setMargins(dp(8), 0, 0, 0); legal.addView(privacy, privacyParams); page.addView(legal);
        if (!errorMessage.isEmpty()) {
            TextView error = text(errorMessage, 12, RED, Typeface.NORMAL);
            error.setGravity(Gravity.CENTER); error.setPadding(0, dp(12), 0, 0); page.addView(error);
        }
        setContentView(page);
    }

    private LinearLayout authPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL); page.setGravity(Gravity.CENTER);
        int side = dp(28); page.setPadding(side, dp(28), side, dp(28)); page.setBackgroundColor(BG);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(side + insets.getSystemWindowInsetLeft(), dp(28) + insets.getSystemWindowInsetTop(),
                    side + insets.getSystemWindowInsetRight(), dp(28) + insets.getSystemWindowInsetBottom()); return insets;
        });
        return page;
    }

    private TextView authLabel(String value) {
        TextView label = text(value, 12, MUTED, Typeface.BOLD);
        label.setGravity(Gravity.LEFT); label.setPadding(0, dp(8), 0, dp(5));
        return label;
    }

    private LinearLayout.LayoutParams authFieldParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
    }

    private void submitAuth() {
        String server = authServerInput.getText().toString().trim();
        String username = authUsernameInput.getText().toString().trim();
        String password = authPasswordInput.getText().toString();
        String display = registerMode && authDisplayNameInput != null ? authDisplayNameInput.getText().toString().trim() : "";
        if (authConsent == null || !authConsent.isChecked()) { showToast("请先阅读并同意用户协议和隐私政策"); return; }
        try { AuthClient.normalizeBaseUrl(server); }
        catch (Exception error) { showAuthScreen(registerMode, error.getMessage()); return; }
        showAuthLoading(registerMode ? "正在创建账号" : "正在登录");
        new Thread(() -> {
            try {
                JSONObject response = registerMode ? AuthClient.register(server, username, display, password)
                        : AuthClient.login(server, username, password);
                JSONObject user = response.getJSONObject("user");
                securePrefs.saveSession(server, response.getString("token"), user.optString("display_name", username));
                runOnUiThread(() -> acceptSession(response));
            } catch (Exception error) { runOnUiThread(() -> showAuthScreen(registerMode, error.getMessage())); }
        }, "YouQi-Auth").start();
    }

    private void showLegalDialog(boolean privacy) {
        String title = privacy ? "隐私政策" : "用户协议";
        String body = privacy
                ? "账号后台保存用户名、显示名称、密码单向派生值、账号状态、注册时间和最后登录时间。\n\n"
                + "后台不接收模型 API Key、聊天内容、角色卡、工程文件、工程路径、终端命令或输出。模型请求受所选供应商政策约束。\n\n"
                + "管理员可启停账号、发布公告、开启维护模式和要求升级，但不能查看聊天或工程，也不能从后台向手机执行命令。\n\n"
                + "运营者及权利人：油漆工作室。GitHub：3466967195。"
                : "本软件为油漆工作室所有的闭源专有软件。未经书面许可，不得复制、修改、反编译、转售或提供衍生版本。\n\n"
                + "AI 输出可能有误，文件修改和 Termux 命令可能造成数据损失。启用“完全自动”表示用户理解无需逐项确认的执行风险。\n\n"
                + "用户不得利用本软件从事违法活动、攻击他人系统或侵犯第三方权利。第三方模型服务和 Termux 受各自条款约束。";
        new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("知道了", null).show();
    }

    private void acceptSession(JSONObject response) {
        JSONObject config = response.optJSONObject("config");
        JSONObject user = response.optJSONObject("user");
        if (config != null && config.optBoolean("maintenance", false)
                && (user == null || !"admin".equals(user.optString("role")))) {
            showAuthBlocked("服务维护中", "管理员暂时关闭了客户端访问，请稍后再试。"); return;
        }
        if (config != null && config.optInt("min_version_code", 1) > BuildConfig.VERSION_CODE) {
            showAuthBlocked("需要更新", "当前版本已停止使用，请安装管理员发布的新版本。"); return;
        }
        openWorkspace(config);
    }

    private void showAuthBlocked(String title, String message) {
        LinearLayout page = authPage();
        TextView heading = text(title, 24, TEXT, Typeface.BOLD); page.addView(heading);
        TextView body = text(message, 14, MUTED, Typeface.NORMAL); body.setGravity(Gravity.CENTER); body.setPadding(0, dp(12), 0, dp(20)); page.addView(body);
        Button retry = button("重新检查", true); retry.setOnClickListener(v -> verifySession());
        page.addView(retry, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        setContentView(page);
    }

    private void openWorkspace(JSONObject config) {
        project = new ProjectStore(this); termux = new TermuxBridge(this);
        termuxSetup = new TermuxSetup(this); mcp = new McpManager(this, termux);
        agent = new AgentClient(termux, mcp);
        character = CharacterCard.load(this); applyCharacter(); setContentView(buildRoot()); restoreProject(); showAgentScreen();
        String announcement = config == null ? "" : config.optString("announcement", "").trim();
        if (!announcement.isEmpty()) new AlertDialog.Builder(this).setTitle("公告").setMessage(announcement).setPositiveButton("知道了", null).show();
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(8), dp(10), dp(8));
        TextView title = text("油漆", 19, TEXT, Typeface.BOLD);
        header.addView(title);
        LinearLayout context = new LinearLayout(this);
        context.setOrientation(LinearLayout.VERTICAL);
        context.setPadding(dp(12), 0, 0, 0);
        projectLabel = text("未选择工程", 12, MUTED, Typeface.NORMAL);
        projectLabel.setSingleLine(true);
        context.addView(projectLabel);
        context.addView(text(character == null ? "默认 Agent" : character.name, 11, GREEN, Typeface.BOLD));
        header.addView(context, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        progress.setIndeterminateTintList(ColorStateList.valueOf(GREEN));
        progress.setVisibility(View.GONE);
        header.addView(progress, new LinearLayout.LayoutParams(dp(34), dp(34)));
        Button fresh = iconButton("＋", "新会话");
        fresh.setOnClickListener(v -> newConversation());
        header.addView(fresh, new LinearLayout.LayoutParams(dp(42), dp(42)));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));
        root.addView(divider(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        statusLabel = text("就绪", 11, MUTED, Typeface.NORMAL);
        statusLabel.setGravity(Gravity.CENTER_VERTICAL);
        statusLabel.setPadding(dp(16), 0, dp(16), 0);
        statusLabel.setBackgroundColor(PANEL);
        root.addView(statusLabel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(8), dp(5), dp(8), dp(5));
        addNav(nav, "对话", this::showAgentScreen);
        addNav(nav, "文件", this::showFilesScreen);
        addNav(nav, "配置", this::showSettingsScreen);
        root.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        return root;
    }

    private void addNav(LinearLayout nav, String label, Runnable action) {
        Button button = button(label, false);
        button.setOnClickListener(v -> { selectNav(button); action.run(); });
        navButtons.add(button);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        nav.addView(button, params);
    }

    private void selectNav(Button selected) {
        for (Button button : navButtons) {
            boolean active = button == selected;
            button.setTextColor(active ? TEXT : MUTED);
            button.setBackground(rounded(active ? PANEL_2 : Color.TRANSPARENT, 6, active ? LINE : Color.TRANSPARENT));
        }
    }

    private void showAgentScreen() {
        if (!navButtons.isEmpty()) selectNav(navButtons.get(0));
        content.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout strip = new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(14), dp(8), dp(14), dp(8));
        TextView mode = text(modeLabel(), 11, modeColor(), Typeface.BOLD);
        mode.setGravity(Gravity.CENTER);
        mode.setBackground(rounded(PANEL_2, 5, LINE));
        strip.addView(mode, new LinearLayout.LayoutParams(dp(98), dp(32)));
        TextView hint = text(project.isReady() ? project.snapshot().size() + " 个工程条目" : "点击右侧选择工程", 12, MUTED, Typeface.NORMAL);
        hint.setPadding(dp(10), 0, 0, 0);
        strip.addView(hint, new LinearLayout.LayoutParams(0, dp(32), 1));
        Button folder = button("选择工程", false);
        folder.setOnClickListener(v -> chooseProject());
        strip.addView(folder, new LinearLayout.LayoutParams(dp(92), dp(34)));
        page.addView(strip);

        messageScroll = new ScrollView(this);
        messageScroll.setFillViewport(true);
        messageList = new LinearLayout(this);
        messageList.setOrientation(LinearLayout.VERTICAL);
        messageList.setPadding(dp(12), dp(10), dp(12), dp(18));
        messageScroll.addView(messageList, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(messageScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        renderEvents();

        LinearLayout composer = new LinearLayout(this);
        composer.setGravity(Gravity.BOTTOM | Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(10), dp(8), dp(10), dp(10));
        composer.setBackgroundColor(PANEL);
        promptInput = edit("发消息，或交代一个任务", false);
        promptInput.setMinLines(1);
        promptInput.setMaxLines(5);
        promptInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        promptInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendPrompt(); return true; }
            return false;
        });
        composer.addView(promptInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        sendButton = iconButton("↑", "发送");
        sendButton.setTextSize(20);
        sendButton.setTextColor(Color.WHITE);
        sendButton.setBackground(rounded(GREEN, 6, GREEN));
        sendButton.setOnClickListener(v -> sendPrompt());
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        sendParams.setMargins(dp(8), 0, 0, 0);
        composer.addView(sendButton, sendParams);
        page.addView(composer);
        content.addView(page);
    }

    private void renderEvents() {
        if (messageList == null) return;
        messageList.removeAllViews();
        if (events.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(28), dp(70), dp(28), dp(20));
            empty.addView(text(character == null ? "准备开始" : character.name + " 已就绪", 21, TEXT, Typeface.BOLD));
            TextView copy = text(project.isReady() ? "直接交代任务，我会读取工程并执行。" : "先选择手机上的工程文件夹。", 14, MUTED, Typeface.NORMAL);
            copy.setGravity(Gravity.CENTER);
            copy.setPadding(0, dp(10), 0, 0);
            empty.addView(copy);
            messageList.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        for (ChatEvent event : events) {
            messageList.addView(event.tool ? toolEventView(event) : messageView(event),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        messageScroll.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));
    }

    private View messageView(ChatEvent event) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(event.user ? Gravity.RIGHT | Gravity.TOP : Gravity.LEFT | Gravity.TOP);
        row.setPadding(0, dp(6), 0, dp(6));
        View avatar = avatar(event.user);
        TextView bubble = text(event.body, 15, TEXT, Typeface.NORMAL);
        bubble.setTextIsSelectable(true);
        bubble.setLineSpacing(0, 1.12f);
        bubble.setPadding(dp(13), dp(10), dp(13), dp(10));
        bubble.setBackground(rounded(event.user ? USER : PANEL_2, 7, event.user ? USER : LINE));
        int max = (int) (getResources().getDisplayMetrics().widthPixels * 0.72f);
        bubble.setMaxWidth(max);
        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (event.user) {
            bubbleParams.setMargins(dp(44), 0, dp(9), 0);
            row.addView(bubble, bubbleParams);
            row.addView(avatar, new LinearLayout.LayoutParams(dp(38), dp(38)));
        } else {
            row.addView(avatar, new LinearLayout.LayoutParams(dp(38), dp(38)));
            bubbleParams.setMargins(dp(9), 0, dp(44), 0);
            row.addView(bubble, bubbleParams);
        }
        return row;
    }

    private View avatar(boolean user) {
        if (!user && character != null && !character.sourceUri.isEmpty()) {
            try {
                ImageView image = new ImageView(this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setImageURI(Uri.parse(character.sourceUri));
                image.setBackground(rounded(PANEL_2, 5, LINE));
                image.setClipToOutline(true);
                return image;
            } catch (Exception ignored) { }
        }
        TextView avatar = text(user ? "我" : "漆", 15, Color.WHITE, Typeface.BOLD);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(rounded(user ? BLUE : GREEN, 5, user ? BLUE : GREEN));
        return avatar;
    }

    private View toolEventView(ChatEvent event) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(46), dp(3), dp(18), dp(3));
        box.setLayoutParams(params);
        box.setBackground(rounded(PANEL, 4, LINE));
        box.addView(text(event.title, 11, event.error ? RED : GREEN, Typeface.BOLD));
        TextView detail = text(event.body, 12, MUTED, Typeface.NORMAL);
        detail.setTypeface(Typeface.MONOSPACE);
        detail.setTextIsSelectable(true);
        detail.setMaxLines(18);
        detail.setPadding(0, dp(4), 0, 0);
        box.addView(detail);
        return box;
    }

    private void showFilesScreen() {
        if (navButtons.size() > 1) selectNav(navButtons.get(1));
        content.removeAllViews();
        FrameLayout frame = new FrameLayout(this);
        filesBrowser = new LinearLayout(this);
        filesBrowser.setOrientation(LinearLayout.VERTICAL);
        filesBrowser.setPadding(dp(12), dp(12), dp(12), dp(8));
        LinearLayout toolbar = new LinearLayout(this);
        EditText filter = edit("筛选文件路径", true);
        toolbar.addView(filter, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button refresh = button("刷新", false);
        refresh.setOnClickListener(v -> refreshProject(true));
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(dp(72), dp(46));
        refreshParams.setMargins(dp(8), 0, 0, 0);
        toolbar.addView(refresh, refreshParams);
        filesBrowser.addView(toolbar);
        fileList = new ListView(this);
        fileList.setDividerHeight(1);
        fileList.setDivider(new android.graphics.drawable.ColorDrawable(LINE));
        fileAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(TEXT); view.setTextSize(13); view.setTypeface(Typeface.MONOSPACE);
                return view;
            }
        };
        fileList.setAdapter(fileAdapter);
        fileList.setOnItemClickListener((p, v, position, id) -> {
            ProjectStore.Entry entry = displayedFiles.get(position);
            if (!entry.directory) openEditor(entry);
        });
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        listParams.setMargins(0, dp(10), 0, 0);
        filesBrowser.addView(fileList, listParams);
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { updateFileList(s.toString()); }
            @Override public void afterTextChanged(Editable s) { }
        });
        frame.addView(filesBrowser);

        editorPane = new LinearLayout(this);
        editorPane.setOrientation(LinearLayout.VERTICAL);
        editorPane.setPadding(dp(12), dp(10), dp(12), dp(10));
        editorPane.setBackgroundColor(BG);
        editorPane.setVisibility(View.GONE);
        LinearLayout editorBar = new LinearLayout(this);
        Button back = button("返回", false); back.setOnClickListener(v -> closeEditor());
        editorBar.addView(back, new LinearLayout.LayoutParams(dp(68), dp(42)));
        editorPath = text("", 12, TEXT, Typeface.BOLD);
        editorPath.setSingleLine(true); editorPath.setPadding(dp(10), 0, dp(8), 0);
        editorBar.addView(editorPath, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button save = button("保存", true); save.setOnClickListener(v -> saveCurrentFile());
        editorBar.addView(save, new LinearLayout.LayoutParams(dp(68), dp(42)));
        editorPane.addView(editorBar);
        editor = edit("", false);
        editor.setGravity(Gravity.TOP | Gravity.START); editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(13); editor.setHorizontallyScrolling(true);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        editorParams.setMargins(0, dp(10), 0, 0);
        editorPane.addView(editor, editorParams);
        frame.addView(editorPane);
        content.addView(frame);
        updateFileList("");
    }

    private void showSettingsScreen() {
        if (navButtons.size() > 2) selectNav(navButtons.get(2));
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(12), dp(16), dp(24));

        section(form, "账号");
        form.addView(text(securePrefs.getDisplayName(), 15, TEXT, Typeface.BOLD));
        TextView server = text(securePrefs.getBackendUrl(), 11, MUTED, Typeface.NORMAL);
        server.setPadding(0, dp(4), 0, dp(8)); form.addView(server);
        Button logout = button("退出登录", false);
        logout.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("退出登录")
                .setMessage("本机模型配置和工程授权会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", (dialog, which) -> { securePrefs.clearSession(); showAuthScreen(false, ""); }).show());
        form.addView(logout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        section(form, "角色");
        form.addView(text(character == null ? "当前：默认 Agent" : "当前：" + character.name, 14, TEXT, Typeface.BOLD));
        TextView cardHint = text("支持 SillyTavern PNG / JSON 角色卡", 12, MUTED, Typeface.NORMAL);
        cardHint.setPadding(0, dp(4), 0, dp(8)); form.addView(cardHint);
        LinearLayout cardActions = new LinearLayout(this);
        Button importCard = button("导入角色卡", true); importCard.setOnClickListener(v -> chooseCharacter());
        cardActions.addView(importCard, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button clearCard = button("恢复默认", false); clearCard.setOnClickListener(v -> clearCharacter());
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        clearParams.setMargins(dp(8), 0, 0, 0); cardActions.addView(clearCard, clearParams);
        form.addView(cardActions);

        section(form, "模型连接");
        form.addView(label("模型供应商"));
        providerSpinner = new Spinner(this);
        ArrayAdapter<ProviderCatalog.Provider> providerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, ProviderCatalog.all());
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        providerSpinner.setAdapter(providerAdapter); providerSpinner.setBackground(rounded(PANEL_2, 6, LINE));
        form.addView(providerSpinner, fieldParams());
        selectedProvider = ProviderCatalog.byId(securePrefs.getProvider());
        providerSpinner.setSelection(Math.max(0, ProviderCatalog.all().indexOf(selectedProvider)), false);
        form.addView(label("Base URL 或完整接口地址"));
        endpointInput = edit("https://example.com/v1", true);
        endpointInput.setText(securePrefs.getEndpoint(selectedProvider.id)); form.addView(endpointInput, fieldParams());
        form.addView(label("模型"));
        LinearLayout modelRow = new LinearLayout(this);
        modelInput = edit("选择或输入模型 ID", true);
        modelInput.setText(securePrefs.getModel(selectedProvider.id));
        modelRow.addView(modelInput, new LinearLayout.LayoutParams(0, dp(50), 1));
        Button fetch = button("获取模型", false); fetch.setOnClickListener(v -> fetchModels());
        LinearLayout.LayoutParams fetchParams = new LinearLayout.LayoutParams(dp(96), dp(50));
        fetchParams.setMargins(dp(8), 0, 0, 0); modelRow.addView(fetch, fetchParams);
        form.addView(modelRow, fieldParams());
        form.addView(label("API Key"));
        apiKeyInput = edit(securePrefs.hasApiKey(selectedProvider.id) ? "已保存，留空保持不变" : "输入 API Key", true);
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(apiKeyInput, fieldParams());
        providerSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int position, long id) {
                ProviderCatalog.Provider provider = ProviderCatalog.all().get(position);
                if (selectedProvider != null && selectedProvider.id.equals(provider.id)) return;
                selectedProvider = provider;
                endpointInput.setText(securePrefs.getEndpoint(provider.id)); modelInput.setText(securePrefs.getModel(provider.id));
                apiKeyInput.setText(""); apiKeyInput.setHint(securePrefs.hasApiKey(provider.id) ? "已保存，留空保持不变" : "输入 API Key");
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        Button saveConnection = button("保存模型配置", true); saveConnection.setOnClickListener(v -> saveSettings());
        form.addView(saveConnection, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        section(form, "Agent 权限");
        executionSpinner = new Spinner(this);
        List<String> modes = Arrays.asList("每次询问", "标准自动", "完全自动");
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modes);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        executionSpinner.setAdapter(modeAdapter); executionSpinner.setBackground(rounded(PANEL_2, 6, LINE));
        executionSpinner.setSelection(modeIndex());
        executionSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int position, long id) {
                securePrefs.setExecutionMode(position == 0 ? SecurePrefs.MODE_ASK : position == 2 ? SecurePrefs.MODE_FULL : SecurePrefs.MODE_STANDARD);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        form.addView(executionSpinner, fieldParams());
        TextView modeHelp = text("标准自动：自动编辑工程并执行只读命令；完全自动：所有工程写入和 Termux 命令均不再询问。", 12, AMBER, Typeface.NORMAL);
        modeHelp.setLineSpacing(0, 1.15f); form.addView(modeHelp);

        section(form, "Termux Shell");
        TextView termuxState = text(termuxStatus(), 13, termux.isInstalled() && termux.hasPermission() ? GREEN : AMBER, Typeface.BOLD);
        termuxState.setPadding(0, 0, 0, dp(8)); form.addView(termuxState);
        Button authorize = button("授权并打开 Termux", false); authorize.setOnClickListener(v -> configureTermux());
        form.addView(authorize, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        Button test = button("测试 Shell", false); test.setOnClickListener(v -> testTermuxShell());
        LinearLayout.LayoutParams testParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        testParams.setMargins(0, dp(8), 0, 0); form.addView(test, testParams);
        Button appSettings = button("系统应用权限", false);
        appSettings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        LinearLayout.LayoutParams appParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        appParams.setMargins(0, dp(8), 0, 0); form.addView(appSettings, appParams);

        // MCP section
        section(form, "MCP 插件");
        List<McpServer> servers = mcp.listServers();
        if (servers.isEmpty()) {
            TextView noMcp = text("暂无 MCP 服务器。添加后 Agent 可获得额外工具。", 12, MUTED, Typeface.NORMAL);
            noMcp.setPadding(0, 0, 0, dp(8)); form.addView(noMcp);
        } else {
            for (McpServer s : servers) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                row.setBackground(rounded(PANEL_2, 6, LINE));
                String label = (s.enabled ? "🟢 " : "⚪ ") + s.name + " (" + s.tools.size() + " tools)";
                TextView mcpLabel = text(label, 13, TEXT, Typeface.NORMAL);
                row.addView(mcpLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                Button toggleBtn = button(s.enabled ? "关闭" : "开启", s.enabled);
                toggleBtn.setOnClickListener(v -> {
                    mcp.setEnabled(s.id, !s.enabled);
                    if (!s.enabled) new Thread(() -> {
                        try { mcp.startServer(s); runOnUiThread(() -> { showToast(s.name + " 已启动"); showSettingsScreen(); }); }
                        catch (Exception e) { runOnUiThread(() -> showError("MCP 启动失败: " + e.getMessage())); }
                    }).start();
                    else { showToast(s.name + " 已关闭"); showSettingsScreen(); }
                });
                row.addView(toggleBtn, new LinearLayout.LayoutParams(dp(64), dp(36)));
                Button delBtn = button("✕", false);
                delBtn.setTextColor(RED);
                delBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("移除 MCP 服务器").setMessage("确定移除 " + s.name + "?")
                    .setPositiveButton("移除", (d, w) -> { mcp.removeServer(s.id); showSettingsScreen(); })
                    .setNegativeButton("取消", null).show());
                row.addView(delBtn, new LinearLayout.LayoutParams(dp(44), dp(36)));
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowParams.setMargins(0, dp(4), 0, 0); form.addView(row, rowParams);
            }
        }
        Button addMcp = button("+ 添加 MCP 服务器", true);
        addMcp.setOnClickListener(v -> showAddMcpDialog());
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        addParams.setMargins(0, dp(8), 0, 0); form.addView(addMcp, addParams);

        scroll.addView(form); content.addView(scroll);
    }

    private void section(LinearLayout form, String value) {
        TextView title = text(value, 17, TEXT, Typeface.BOLD);
        title.setPadding(0, dp(16), 0, dp(8)); form.addView(title);
    }

    private void chooseCharacter() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/png", "application/json", "text/json"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_CHARACTER);
    }

    private void importCharacter(Uri uri, int flags) {
        try {
            try { getContentResolver().takePersistableUriPermission(uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) { }
            character = CharacterCard.importFrom(this, uri); character.save(this); applyCharacter();
            events.clear();
            if (!character.firstMessage.isEmpty()) events.add(ChatEvent.assistant(character.firstMessage.replace("{{char}}", character.name).replace("{{user}}", "你")));
            showToast("已导入角色：" + character.name); setContentView(buildRoot()); restoreProject(); showAgentScreen();
        } catch (Exception error) { showError("角色卡导入失败：" + error.getMessage()); }
    }

    private void clearCharacter() {
        CharacterCard.clear(this); character = null; applyCharacter(); events.clear();
        setContentView(buildRoot()); restoreProject(); showSettingsScreen();
    }

    private void applyCharacter() { if (agent != null) agent.setPersonaPrompt(character == null ? "" : character.agentPrompt()); }

    private void fetchModels() {
        String endpoint = endpointInput.getText().toString().trim();
        String key = apiKeyInput.getText().toString().trim();
        try { if (key.isEmpty()) key = securePrefs.getApiKey(selectedProvider.id); }
        catch (Exception error) { showError("无法读取 API Key：" + error.getMessage()); return; }
        if (key.isEmpty()) { showToast("请先填写 API Key"); return; }
        final String apiKey = key;
        setBusy(true, "正在获取模型列表");
        new Thread(() -> {
            try {
                List<String> models = ModelCatalogClient.fetch(ProviderCatalog.modelsUrl(selectedProvider, endpoint), apiKey,
                        "gemini".equals(selectedProvider.id));
                runOnUiThread(() -> { setBusy(false, "已获取 " + models.size() + " 个模型"); showModelPicker(models); });
            } catch (Exception error) { runOnUiThread(() -> { setBusy(false, "获取模型失败"); showError(error.getMessage()); }); }
        }, "PocketAgent-Models").start();
    }

    private void showModelPicker(List<String> models) {
        EditText search = edit("搜索模型", true);
        ListView list = new ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>(models));
        list.setAdapter(adapter);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(12), dp(8), dp(12), 0);
        body.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        body.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("选择模型").setView(body).setNegativeButton("取消", null).create();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { adapter.getFilter().filter(s); }
            @Override public void afterTextChanged(Editable s) { }
        });
        list.setOnItemClickListener((p, v, position, id) -> { modelInput.setText(adapter.getItem(position)); dialog.dismiss(); });
        dialog.show();
    }

    private void saveSettings() {
        String endpoint = endpointInput.getText().toString().trim();
        String model = modelInput.getText().toString().trim();
        if (!endpoint.startsWith("https://") || model.isEmpty()) { showToast("请填写 HTTPS API 地址和模型"); return; }
        try {
            securePrefs.saveConnection(selectedProvider.id, endpoint, model, apiKeyInput.getText().toString());
            apiKeyInput.setText(""); apiKeyInput.setHint("已保存，留空保持不变"); agent.resetConversation(); showToast("模型配置已保存");
        } catch (Exception error) { showError("保存失败：" + error.getMessage()); }
    }

    private String termuxStatus() {
        TermuxSetup.State state = termuxSetup.checkState();
        switch (state) {
            case NOT_INSTALLED: return "未安装 Termux — 点击下方按钮安装";
            case INSTALLED_NO_PERMISSION: return "Termux 已安装，等待授权";
            case READY: return "Termux 已就绪 ✅";
            default: return "未知状态";
        }
    }

    private void configureTermux() {
        TermuxSetup.State state = termuxSetup.checkState();
        switch (state) {
            case NOT_INSTALLED:
                new AlertDialog.Builder(this).setTitle("安装 Termux")
                    .setMessage("油漆需要 Termux 来执行命令。点击确定跳转到应用商店安装。\n\n安装后请打开 Termux 一次完成初始化，然后回到本页面继续。")
                    .setPositiveButton("去安装", (d, w) -> termuxSetup.openInstallPage())
                    .setNegativeButton("取消", null).show();
                break;
            case INSTALLED_NO_PERMISSION:
                requestPermissions(new String[]{"com.termux.permission.RUN_COMMAND"}, REQUEST_TERMUX);
                break;
            case READY:
                // Run bootstrap: auto-configure and install packages
                new AlertDialog.Builder(this).setTitle("配置 Termux 环境")
                    .setMessage("将自动开启外部应用支持并安装 git / ripgrep / python / nodejs。\n\n首次可能需要几分钟。")
                    .setPositiveButton("开始配置", (d, w) -> {
                        setBusy(true, "配置 Termux 环境");
                        new Thread(() -> {
                            try {
                                termuxSetup.runBootstrap(termux);
                                runOnUiThread(() -> { setBusy(false, "就绪"); showToast("Termux 环境配置完成 ✅"); showSettingsScreen(); });
                            } catch (Exception e) {
                                runOnUiThread(() -> { setBusy(false, "配置失败"); showError(e.getMessage()); });
                            }
                        }).start();
                    }).setNegativeButton("取消", null).show();
                break;
        }
    }

    private void testTermuxShell() {
        if (!project.isReady()) { showToast("请先选择工程目录"); return; }
        if (!termux.isInstalled() || !termux.hasPermission()) { showToast("请先完成 Termux 授权"); return; }
        setBusy(true, "正在测试 Termux Shell");
        new Thread(() -> {
            try {
                String result = termux.run("printf 'Shell OK\\n'; pwd; command -v git; command -v rg", project.getTreeUri(), "");
                runOnUiThread(() -> { setBusy(false, "Shell 测试完成"); events.add(ChatEvent.tool("TERMINAL", formatCommandResult("环境检测", result), false)); showAgentScreen(); });
            } catch (Exception error) { runOnUiThread(() -> { setBusy(false, "Shell 测试失败"); showError(error.getMessage() + "\n\n请在 Termux 中运行 termux-setup-storage，并确认 allow-external-apps=true。"); }); }
        }, "PocketAgent-Termux-Test").start();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_TERMUX && results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showToast("Termux 命令权限已授予"); configureTermux();
        } else if (requestCode == REQUEST_TERMUX) showError("未授予 Termux RUN_COMMAND 权限");
    }

    private void chooseProject() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, PICK_PROJECT);
    }

    @Override @SuppressLint("WrongConstant") protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == PICK_CHARACTER) { importCharacter(data.getData(), data.getFlags()); return; }
        if (requestCode != PICK_PROJECT) return;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
            getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit().putString("tree_uri", uri.toString()).apply();
            project.setTreeUri(uri); projectLabel.setText(displayTreeName(uri)); agent.resetConversation(); refreshProject(true);
        } catch (Exception error) { showError(error.getMessage()); }
    }

    private void restoreProject() {
        SharedPreferences state = getSharedPreferences(STATE_PREFS, MODE_PRIVATE);
        String value = state.getString("tree_uri", ""); if (value.isEmpty()) return;
        try { Uri uri = Uri.parse(value); project.setTreeUri(uri); projectLabel.setText(displayTreeName(uri)); refreshProject(false); }
        catch (Exception error) { state.edit().remove("tree_uri").apply(); }
    }

    private String displayTreeName(Uri uri) {
        try {
            String id = android.provider.DocumentsContract.getTreeDocumentId(uri);
            int colon = id.lastIndexOf(':'); return colon >= 0 && colon < id.length() - 1 ? id.substring(colon + 1) : id;
        } catch (Exception ignored) { return "已授权工程"; }
    }

    private void refreshProject(boolean notify) {
        if (!project.isReady()) { if (notify) showToast("请先选择工程目录"); return; }
        setBusy(true, "正在扫描工程");
        new Thread(() -> {
            try { project.refresh(); runOnUiThread(() -> { setBusy(false, project.snapshot().size() + " 个工程条目"); updateFileList(""); if (notify) showToast("工程索引已更新"); }); }
            catch (Exception error) { runOnUiThread(() -> { setBusy(false, "扫描失败"); showError(error.getMessage()); }); }
        }, "PocketAgent-Scan").start();
    }

    private void updateFileList(String query) {
        if (fileAdapter == null) return;
        displayedFiles = project.filter(query); fileAdapter.clear();
        for (ProjectStore.Entry entry : displayedFiles) fileAdapter.add(entry.label());
        fileAdapter.notifyDataSetChanged();
    }

    private void openEditor(ProjectStore.Entry entry) {
        setBusy(true, "正在读取 " + entry.path);
        new Thread(() -> {
            try { String data = project.readFile(entry.path); runOnUiThread(() -> { currentFile = entry; editorPath.setText(entry.path); editor.setText(data); editor.setSelection(0); filesBrowser.setVisibility(View.GONE); editorPane.setVisibility(View.VISIBLE); setBusy(false, "就绪"); }); }
            catch (Exception error) { runOnUiThread(() -> { setBusy(false, "读取失败"); showError(error.getMessage()); }); }
        }, "PocketAgent-Read").start();
    }

    private void closeEditor() { editorPane.setVisibility(View.GONE); filesBrowser.setVisibility(View.VISIBLE); currentFile = null; }

    private void saveCurrentFile() {
        if (currentFile == null) return;
        String path = currentFile.path, data = editor.getText().toString(); setBusy(true, "正在保存 " + path);
        new Thread(() -> {
            try { project.writeFile(path, data); runOnUiThread(() -> { setBusy(false, "已保存 " + path); showToast("保存成功"); }); }
            catch (Exception error) { runOnUiThread(() -> { setBusy(false, "保存失败"); showError(error.getMessage()); }); }
        }, "PocketAgent-Write").start();
    }

    private void sendPrompt() {
        String prompt = promptInput == null ? "" : promptInput.getText().toString().trim(); if (prompt.isEmpty()) return;
        if (!project.isReady()) { showToast("请先选择工程目录"); return; }
        if (project.snapshot().isEmpty()) { showToast("工程仍在扫描，请稍候"); return; }
        String key;
        try { key = securePrefs.getApiKey(); } catch (Exception error) { showError("无法读取 API Key：" + error.getMessage()); return; }
        if (key.trim().isEmpty()) { showSettingsScreen(); showToast("请先填写 API Key"); return; }
        events.add(ChatEvent.user(prompt)); renderEvents(); promptInput.setText(""); setBusy(true, "Agent 正在分析");
        agent.send(prompt, securePrefs.getEndpoint(), securePrefs.getModel(), key, project, this);
    }

    private void newConversation() { agent.resetConversation(); events.clear(); renderEvents(); showToast("已新建会话"); }

    @Override public void onStatus(String status) { runOnUiThread(() -> setBusy(!"Ready".equals(status), translateStatus(status))); }
    @Override public void onAssistant(String value) { runOnUiThread(() -> { events.add(ChatEvent.assistant(value)); renderEvents(); }); }
    @Override public void onError(String value) { runOnUiThread(() -> { setBusy(false, "请求失败"); events.add(ChatEvent.error(value)); renderEvents(); showError(value); }); }
    @Override public void onToolCall(String name, String detail) { runOnUiThread(() -> { events.add(ChatEvent.tool(toolLabel(name), detail, false)); renderEvents(); }); }

    @Override public void requestWriteApproval(String path, String oldContent, String newContent, CompletableFuture<Boolean> decision) {
        String mode = securePrefs.getExecutionMode();
        if (!SecurePrefs.MODE_ASK.equals(mode)) { decision.complete(true); return; }
        runOnUiThread(() -> approvalDialog("批准文件修改", formatDiff(path, oldContent, newContent), "写入", decision));
    }

    @Override public void requestCommandApproval(String command, String workingDirectory, CompletableFuture<Boolean> decision) {
        String mode = securePrefs.getExecutionMode();
        if (SecurePrefs.MODE_FULL.equals(mode) || (SecurePrefs.MODE_STANDARD.equals(mode) && isSafeCommand(command))) { decision.complete(true); return; }
        runOnUiThread(() -> approvalDialog("批准终端命令", "$ " + command + "\n\n目录：" + (workingDirectory.isEmpty() ? "工程根目录" : workingDirectory), "执行", decision));
    }

    private void approvalDialog(String title, String body, String positive, CompletableFuture<Boolean> decision) {
        TextView view = text(body, 12, TEXT, Typeface.NORMAL); view.setTypeface(Typeface.MONOSPACE); view.setTextIsSelectable(true); view.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView scroll = new ScrollView(this); scroll.addView(view);
        new AlertDialog.Builder(this).setTitle(title).setView(scroll)
                .setNegativeButton("拒绝", (d, w) -> decision.complete(false))
                .setPositiveButton(positive, (d, w) -> decision.complete(true))
                .setOnCancelListener(d -> decision.complete(false)).show();
    }

    @Override public void onCommandResult(String command, String result) {
        runOnUiThread(() -> { events.add(ChatEvent.tool("TERMINAL RESULT", formatCommandResult(command, result), false)); renderEvents(); });
    }

    private boolean isSafeCommand(String command) {
        String value = command.trim().toLowerCase(Locale.ROOT);
        if (value.matches(".*(;|&&|\\|\\||>|<|`|\\$\\().*")) return false;
        if (value.contains(" -delete") || value.contains(" -exec") || value.contains("--exec")) return false;
        return value.matches("^(pwd|ls( .*)?|rg( .*)?|grep( .*)?|cat( .*)?|head( .*)?|tail( .*)?|wc( .*)?|file( .*)?|stat( .*)?|which( .*)?|command -v .+|find( .*)?|git (status|diff|log|show|branch|rev-parse)( .*)?|[a-z0-9._/-]+ --version)$");
    }

    private String formatCommandResult(String command, String result) {
        try {
            JSONObject value = new JSONObject(result);
            StringBuilder out = new StringBuilder("$ ").append(command).append("\nexit ").append(value.optInt("exit_code", -1));
            String stdout = value.optString("stdout", "").trim(), stderr = value.optString("stderr", "").trim();
            if (!stdout.isEmpty()) out.append("\n\n").append(stdout); if (!stderr.isEmpty()) out.append("\n\n[stderr]\n").append(stderr);
            return out.length() > 20_000 ? out.substring(0, 20_000) + "\n... 输出已截断" : out.toString();
        } catch (Exception ignored) { return "$ " + command + "\n" + result; }
    }

    private String formatDiff(String path, String oldContent, String newContent) {
        String[] oldLines = oldContent.split("\\R", -1), newLines = newContent.split("\\R", -1);
        int prefix = 0; while (prefix < oldLines.length && prefix < newLines.length && oldLines[prefix].equals(newLines[prefix])) prefix++;
        int suffix = 0; while (suffix < oldLines.length - prefix && suffix < newLines.length - prefix && oldLines[oldLines.length - 1 - suffix].equals(newLines[newLines.length - 1 - suffix])) suffix++;
        StringBuilder out = new StringBuilder("文件：").append(path).append("\n\n");
        for (int i = Math.max(0, prefix - 3); i < prefix; i++) out.append("  ").append(oldLines[i]).append('\n');
        for (int i = prefix; i < oldLines.length - suffix && out.length() < 24000; i++) out.append("- ").append(oldLines[i]).append('\n');
        for (int i = prefix; i < newLines.length - suffix && out.length() < 24000; i++) out.append("+ ").append(newLines[i]).append('\n');
        return out.toString();
    }

    private String toolLabel(String name) {
        switch (name) {
            case "list_files": return "LIST FILES"; case "read_file": return "READ FILE";
            case "search_files": return "SEARCH"; case "write_file": return "WRITE FILE";
            case "run_command": return "RUN COMMAND"; default: return name.toUpperCase(Locale.ROOT);
        }
    }

    private String translateStatus(String status) {
        switch (status) {
            case "Using tools": return "Agent 正在使用工具"; case "Responses API": return "正在连接 Responses API";
            case "Chat Completions": return "正在连接 Chat Completions"; case "Falling back to Chat Completions": return "正在切换兼容接口";
            case "Ready": return "就绪"; default: return status;
        }
    }

    private String modeLabel() {
        String mode = securePrefs.getExecutionMode();
        return SecurePrefs.MODE_FULL.equals(mode) ? "完全自动" : SecurePrefs.MODE_ASK.equals(mode) ? "每次询问" : "标准自动";
    }
    private int modeColor() { return SecurePrefs.MODE_FULL.equals(securePrefs.getExecutionMode()) ? AMBER : GREEN; }
    private int modeIndex() { String mode = securePrefs.getExecutionMode(); return SecurePrefs.MODE_ASK.equals(mode) ? 0 : SecurePrefs.MODE_FULL.equals(mode) ? 2 : 1; }

    private void setBusy(boolean busy, String status) {
        if (progress != null) progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (sendButton != null) sendButton.setEnabled(!busy);
        if (statusLabel != null) statusLabel.setText(status);
    }

    private TextView label(String value) { TextView label = text(value, 12, MUTED, Typeface.BOLD); label.setPadding(0, dp(7), 0, dp(5)); return label; }
    private LinearLayout.LayoutParams fieldParams() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)); p.setMargins(0, 0, 0, dp(7)); return p; }
    private TextView text(String value, float size, int color, int style) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setTypeface(Typeface.create("sans", style)); return v; }
    private EditText edit(String hint, boolean singleLine) { EditText v = new EditText(this); v.setHint(hint); v.setTextColor(TEXT); v.setHintTextColor(MUTED); v.setTextSize(14); v.setSingleLine(singleLine); v.setPadding(dp(12), dp(8), dp(12), dp(8)); v.setBackground(rounded(PANEL_2, 6, LINE)); return v; }
    private Button button(String label, boolean primary) { Button v = new Button(this); v.setText(label); v.setTextSize(13); v.setAllCaps(false); v.setGravity(Gravity.CENTER); v.setPadding(dp(8), 0, dp(8), 0); v.setTextColor(primary ? Color.WHITE : TEXT); v.setBackground(rounded(primary ? GREEN : PANEL_2, 6, primary ? GREEN : LINE)); v.setStateListAnimator(null); return v; }
    private Button iconButton(String label, String description) { Button v = button(label, false); v.setContentDescription(description); v.setTextSize(19); return v; }
    private View divider() { View v = new View(this); v.setBackgroundColor(LINE); return v; }
    private GradientDrawable rounded(int fill, int radiusDp, int stroke) { GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radiusDp)); d.setStroke(dp(1), stroke); return d; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void showToast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private void showError(String value) { new AlertDialog.Builder(this).setTitle("操作失败").setMessage(value).setPositiveButton("确定", null).show(); }

    private void showAddMcpDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(16), dp(24), dp(8));
        form.addView(text("MCP 服务器配置", 16, TEXT, Typeface.BOLD));
        EditText nameInput = edit("名称（如 Filesystem）", true); nameInput.setPadding(0, dp(12), 0, dp(8)); form.addView(nameInput);
        EditText cmdInput = edit("命令（如 npx）", true); cmdInput.setPadding(0, dp(12), 0, dp(8)); form.addView(cmdInput);
        EditText argsInput = edit("参数（如 -y @modelcontextprotocol/server-filesystem /sdcard）", true); argsInput.setPadding(0, dp(12), 0, dp(8)); form.addView(argsInput);
        new AlertDialog.Builder(this).setView(form)
            .setPositiveButton("添加", (d, w) -> {
                String name = nameInput.getText().toString().trim();
                String cmd = cmdInput.getText().toString().trim();
                String args = argsInput.getText().toString().trim();
                if (name.isEmpty() || cmd.isEmpty() || args.isEmpty()) { showToast("请填写完整"); return; }
                McpServer srv = mcp.addServer(name, cmd, args);
                new Thread(() -> {
                    try {
                        mcp.startServer(srv);
                        runOnUiThread(() -> { showToast("MCP " + name + " 已启动，发现 " + srv.tools.size() + " 个工具"); showSettingsScreen(); });
                    } catch (Exception e) {
                        runOnUiThread(() -> showError("MCP 启动失败：" + e.getMessage()));
                    }
                }).start();
            })
            .setNegativeButton("取消", null).show();
    }

    private static final class ChatEvent {
        final boolean user, tool, error; final String title, body;
        ChatEvent(boolean user, boolean tool, boolean error, String title, String body) { this.user = user; this.tool = tool; this.error = error; this.title = title; this.body = body == null ? "" : body.trim(); }
        static ChatEvent user(String value) { return new ChatEvent(true, false, false, "", value); }
        static ChatEvent assistant(String value) { return new ChatEvent(false, false, false, "", value); }
        static ChatEvent tool(String title, String value, boolean error) { return new ChatEvent(false, true, error, title, value); }
        static ChatEvent error(String value) { return tool("ERROR", value, true); }
    }
}
