package com.pocketagent.app;

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
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class MainActivity extends Activity implements AgentClient.Listener {
    private static final int PICK_PROJECT = 41;
    private static final String STATE_PREFS = "pocket_agent_state";
    private static final int INK = Color.rgb(24, 32, 29);
    private static final int PAPER = Color.rgb(247, 248, 245);
    private static final int SURFACE = Color.WHITE;
    private static final int LINE = Color.rgb(216, 221, 216);
    private static final int BRAND = Color.rgb(20, 108, 82);
    private static final int BRAND_DARK = Color.rgb(13, 79, 60);
    private static final int ACCENT = Color.rgb(217, 119, 6);
    private static final int MUTED = Color.rgb(94, 107, 101);

    private final AgentClient agent = new AgentClient();
    private final StringBuilder transcript = new StringBuilder();
    private ProjectStore project;
    private SecurePrefs securePrefs;
    private FrameLayout content;
    private TextView projectLabel;
    private TextView statusLabel;
    private TextView transcriptView;
    private ScrollView transcriptScroll;
    private EditText promptInput;
    private Button sendButton;
    private ProgressBar progress;
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
    private final List<Button> navButtons = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        project = new ProjectStore(this);
        securePrefs = new SecurePrefs(this);
        setContentView(buildRoot());
        restoreProject();
        showAgentScreen();
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAPER);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(8), dp(12), dp(8));
        TextView mark = text(">_", 22, Color.WHITE, Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(rounded(BRAND, 6, BRAND));
        header.addView(mark, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(text("Pocket Agent", 19, INK, Typeface.BOLD));
        projectLabel = text("未选择工程", 12, MUTED, Typeface.NORMAL);
        projectLabel.setSingleLine(true);
        titles.addView(projectLabel);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        progress.setIndeterminateTintList(ColorStateList.valueOf(BRAND));
        progress.setVisibility(View.GONE);
        header.addView(progress, new LinearLayout.LayoutParams(dp(32), dp(32)));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(12), dp(7), dp(12), dp(7));
        addNav(nav, "Agent", this::showAgentScreen);
        addNav(nav, "文件", this::showFilesScreen);
        addNav(nav, "设置", this::showSettingsScreen);
        root.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        statusLabel = text("就绪", 12, MUTED, Typeface.NORMAL);
        statusLabel.setGravity(Gravity.CENTER_VERTICAL);
        statusLabel.setPadding(dp(16), 0, dp(16), 0);
        statusLabel.setBackgroundColor(Color.rgb(238, 241, 237));
        root.addView(statusLabel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));
        return root;
    }

    private void addNav(LinearLayout nav, String label, Runnable action) {
        Button button = button(label, false);
        button.setOnClickListener(view -> {
            selectNav(button);
            action.run();
        });
        navButtons.add(button);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1);
        if (!navButtons.isEmpty()) params.setMargins(dp(3), 0, dp(3), 0);
        nav.addView(button, params);
    }

    private void selectNav(Button selected) {
        for (Button button : navButtons) {
            boolean active = button == selected;
            button.setTextColor(active ? Color.WHITE : INK);
            button.setBackground(rounded(active ? BRAND : Color.TRANSPARENT, 6, active ? BRAND : LINE));
        }
    }

    private void showAgentScreen() {
        if (!navButtons.isEmpty()) selectNav(navButtons.get(0));
        content.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(10), dp(14), dp(10));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button choose = button("选择工程", true);
        choose.setOnClickListener(view -> chooseProject());
        actions.addView(choose, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button reset = button("新会话", false);
        reset.setOnClickListener(view -> {
            agent.resetConversation();
            transcript.setLength(0);
            renderTranscript();
            showToast("已创建新会话");
        });
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        resetParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(reset, resetParams);
        page.addView(actions);

        transcriptView = text("", 15, INK, Typeface.NORMAL);
        transcriptView.setTextIsSelectable(true);
        transcriptView.setLineSpacing(0, 1.18f);
        transcriptView.setPadding(dp(14), dp(14), dp(14), dp(14));
        transcriptScroll = new ScrollView(this);
        transcriptScroll.setFillViewport(true);
        transcriptScroll.setBackground(rounded(SURFACE, 6, LINE));
        transcriptScroll.addView(transcriptView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        scrollParams.setMargins(0, dp(10), 0, dp(10));
        page.addView(transcriptScroll, scrollParams);
        renderTranscript();

        LinearLayout compose = new LinearLayout(this);
        compose.setGravity(Gravity.BOTTOM);
        promptInput = edit("描述要检查或修改的内容", false);
        promptInput.setMinLines(1);
        promptInput.setMaxLines(5);
        promptInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        promptInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendPrompt();
                return true;
            }
            return false;
        });
        compose.addView(promptInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        sendButton = button("发送", true);
        sendButton.setOnClickListener(view -> sendPrompt());
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(76), dp(48));
        sendParams.setMargins(dp(8), 0, 0, 0);
        compose.addView(sendButton, sendParams);
        page.addView(compose);
        content.addView(page);
    }

    private void showFilesScreen() {
        if (navButtons.size() > 1) selectNav(navButtons.get(1));
        content.removeAllViews();
        FrameLayout frame = new FrameLayout(this);
        filesBrowser = new LinearLayout(this);
        filesBrowser.setOrientation(LinearLayout.VERTICAL);
        filesBrowser.setPadding(dp(14), dp(10), dp(14), dp(10));

        LinearLayout toolbar = new LinearLayout(this);
        EditText filter = edit("筛选路径", true);
        toolbar.addView(filter, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button refresh = button("刷新", false);
        refresh.setOnClickListener(view -> refreshProject(true));
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(dp(76), dp(46));
        refreshParams.setMargins(dp(8), 0, 0, 0);
        toolbar.addView(refresh, refreshParams);
        filesBrowser.addView(toolbar);

        fileList = new ListView(this);
        fileList.setDividerHeight(1);
        fileList.setBackgroundColor(SURFACE);
        fileAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        fileList.setAdapter(fileAdapter);
        fileList.setOnItemClickListener((parent, view, position, id) -> {
            ProjectStore.Entry entry = displayedFiles.get(position);
            if (!entry.directory) openEditor(entry);
        });
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        listParams.setMargins(0, dp(10), 0, 0);
        filesBrowser.addView(fileList, listParams);
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateFileList(s.toString()); }
            @Override public void afterTextChanged(Editable s) { }
        });
        frame.addView(filesBrowser);

        editorPane = new LinearLayout(this);
        editorPane.setOrientation(LinearLayout.VERTICAL);
        editorPane.setPadding(dp(14), dp(10), dp(14), dp(10));
        editorPane.setBackgroundColor(PAPER);
        editorPane.setVisibility(View.GONE);
        LinearLayout editorBar = new LinearLayout(this);
        editorBar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("返回", false);
        back.setOnClickListener(view -> closeEditor());
        editorBar.addView(back, new LinearLayout.LayoutParams(dp(70), dp(42)));
        editorPath = text("", 13, INK, Typeface.BOLD);
        editorPath.setSingleLine(true);
        editorPath.setPadding(dp(10), 0, dp(8), 0);
        editorBar.addView(editorPath, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button save = button("保存", true);
        save.setOnClickListener(view -> saveCurrentFile());
        editorBar.addView(save, new LinearLayout.LayoutParams(dp(70), dp(42)));
        editorPane.addView(editorBar);
        editor = edit("", false);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(13);
        editor.setHorizontallyScrolling(true);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
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
        form.setPadding(dp(18), dp(18), dp(18), dp(18));
        form.addView(label("Responses API 地址"));
        endpointInput = edit("https://api.openai.com/v1/responses", true);
        endpointInput.setText(securePrefs.getEndpoint());
        form.addView(endpointInput, fieldParams());
        form.addView(label("模型"));
        modelInput = edit("gpt-5.3-codex", true);
        modelInput.setText(securePrefs.getModel());
        form.addView(modelInput, fieldParams());
        form.addView(label("API Key"));
        apiKeyInput = edit(securePrefs.hasApiKey() ? "已安全保存，留空则保持不变" : "sk-...", true);
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(apiKeyInput, fieldParams());
        Button save = button("保存设置", true);
        save.setOnClickListener(view -> saveSettings());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        saveParams.setMargins(0, dp(8), 0, dp(12));
        form.addView(save, saveParams);
        Button appSettings = button("打开系统应用设置", false);
        appSettings.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        form.addView(appSettings, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        scroll.addView(form);
        content.addView(scroll);
    }

    private void chooseProject() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, PICK_PROJECT);
    }

    @Override
    @SuppressLint("WrongConstant")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_PROJECT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            if (flags == 0) throw new SecurityException("系统文件选择器没有授予目录读写权限");
            getContentResolver().takePersistableUriPermission(uri, flags);
            getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit().putString("tree_uri", uri.toString()).apply();
            project.setTreeUri(uri);
            projectLabel.setText(displayTreeName(uri));
            agent.resetConversation();
            refreshProject(true);
        } catch (Exception error) {
            showError(error.getMessage());
        }
    }

    private void restoreProject() {
        SharedPreferences state = getSharedPreferences(STATE_PREFS, MODE_PRIVATE);
        String value = state.getString("tree_uri", "");
        if (value.isEmpty()) return;
        try {
            Uri uri = Uri.parse(value);
            project.setTreeUri(uri);
            projectLabel.setText(displayTreeName(uri));
            refreshProject(false);
        } catch (Exception error) {
            state.edit().remove("tree_uri").apply();
        }
    }

    private String displayTreeName(Uri uri) {
        String id;
        try {
            id = android.provider.DocumentsContract.getTreeDocumentId(uri);
        } catch (Exception ignored) {
            return "已授权工程";
        }
        int colon = id.lastIndexOf(':');
        return colon >= 0 && colon < id.length() - 1 ? id.substring(colon + 1) : id;
    }

    private void refreshProject(boolean notify) {
        if (!project.isReady()) {
            if (notify) showToast("请先选择工程目录");
            return;
        }
        setBusy(true, "正在扫描工程");
        new Thread(() -> {
            try {
                project.refresh();
                runOnUiThread(() -> {
                    setBusy(false, project.snapshot().size() + " 个项目条目");
                    updateFileList("");
                    if (notify) showToast("工程索引已更新");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false, "扫描失败");
                    showError(error.getMessage());
                });
            }
        }, "PocketAgent-Scan").start();
    }

    private void updateFileList(String query) {
        if (fileAdapter == null) return;
        displayedFiles = project.filter(query);
        fileAdapter.clear();
        for (ProjectStore.Entry entry : displayedFiles) fileAdapter.add(entry.label());
        fileAdapter.notifyDataSetChanged();
    }

    private void openEditor(ProjectStore.Entry entry) {
        setBusy(true, "正在读取 " + entry.path);
        new Thread(() -> {
            try {
                String content = project.readFile(entry.path);
                runOnUiThread(() -> {
                    currentFile = entry;
                    editorPath.setText(entry.path);
                    editor.setText(content);
                    editor.setSelection(0);
                    filesBrowser.setVisibility(View.GONE);
                    editorPane.setVisibility(View.VISIBLE);
                    setBusy(false, "就绪");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false, "读取失败");
                    showError(error.getMessage());
                });
            }
        }, "PocketAgent-Read").start();
    }

    private void closeEditor() {
        editorPane.setVisibility(View.GONE);
        filesBrowser.setVisibility(View.VISIBLE);
        currentFile = null;
    }

    private void saveCurrentFile() {
        if (currentFile == null) return;
        String path = currentFile.path;
        String content = editor.getText().toString();
        setBusy(true, "正在保存 " + path);
        new Thread(() -> {
            try {
                project.writeFile(path, content);
                runOnUiThread(() -> {
                    setBusy(false, "已保存 " + path);
                    showToast("保存成功");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false, "保存失败");
                    showError(error.getMessage());
                });
            }
        }, "PocketAgent-Write").start();
    }

    private void sendPrompt() {
        String prompt = promptInput == null ? "" : promptInput.getText().toString().trim();
        if (prompt.isEmpty()) return;
        if (!project.isReady()) {
            showToast("请先选择工程目录");
            return;
        }
        if (project.snapshot().isEmpty()) {
            showToast("工程仍在扫描，请稍候");
            return;
        }
        String apiKey;
        try {
            apiKey = securePrefs.getApiKey();
        } catch (Exception error) {
            showError("无法读取 API Key: " + error.getMessage());
            return;
        }
        if (apiKey.trim().isEmpty()) {
            showSettingsScreen();
            showToast("请先填写 API Key");
            return;
        }
        appendMessage("你", prompt);
        promptInput.setText("");
        setBusy(true, "Agent 正在分析");
        agent.send(prompt, securePrefs.getEndpoint(), securePrefs.getModel(), apiKey, project, this);
    }

    private void appendMessage(String role, String message) {
        if (transcript.length() > 0) transcript.append("\n\n");
        transcript.append(role).append("\n").append(message.trim());
        renderTranscript();
    }

    private void renderTranscript() {
        if (transcriptView == null) return;
        transcriptView.setText(transcript.length() == 0 ? "选择工程后即可开始。" : transcript.toString());
        if (transcriptScroll != null) transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void saveSettings() {
        String endpoint = endpointInput.getText().toString().trim();
        String model = modelInput.getText().toString().trim();
        if (!endpoint.startsWith("https://") || model.isEmpty()) {
            showToast("请填写 HTTPS API 地址和模型");
            return;
        }
        try {
            securePrefs.saveConnection(endpoint, model, apiKeyInput.getText().toString());
            apiKeyInput.setText("");
            apiKeyInput.setHint("已安全保存，留空则保持不变");
            agent.resetConversation();
            showToast("设置已保存");
        } catch (Exception error) {
            showError("保存失败: " + error.getMessage());
        }
    }

    @Override
    public void onStatus(String status) {
        runOnUiThread(() -> setBusy(!"Ready".equals(status), translateStatus(status)));
    }

    @Override
    public void onAssistant(String text) {
        runOnUiThread(() -> appendMessage("Agent", text));
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            setBusy(false, "请求失败");
            appendMessage("错误", message);
            showError(message);
        });
    }

    @Override
    public void requestWriteApproval(String path, String oldContent, String newContent,
                                     CompletableFuture<Boolean> decision) {
        runOnUiThread(() -> {
            TextView diff = text(formatDiff(path, oldContent, newContent), 12, INK, Typeface.NORMAL);
            diff.setTypeface(Typeface.MONOSPACE);
            diff.setTextIsSelectable(true);
            diff.setPadding(dp(12), dp(8), dp(12), dp(8));
            ScrollView scroll = new ScrollView(this);
            scroll.addView(diff);
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("批准文件修改")
                    .setView(scroll)
                    .setNegativeButton("拒绝", (d, which) -> decision.complete(false))
                    .setPositiveButton("写入", (d, which) -> decision.complete(true))
                    .setOnCancelListener(d -> decision.complete(false))
                    .create();
            dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(BRAND));
            dialog.show();
        });
    }

    private String formatDiff(String path, String oldContent, String newContent) {
        String[] oldLines = oldContent.split("\\R", -1);
        String[] newLines = newContent.split("\\R", -1);
        int prefix = 0;
        while (prefix < oldLines.length && prefix < newLines.length && oldLines[prefix].equals(newLines[prefix])) prefix++;
        int suffix = 0;
        while (suffix < oldLines.length - prefix && suffix < newLines.length - prefix
                && oldLines[oldLines.length - 1 - suffix].equals(newLines[newLines.length - 1 - suffix])) suffix++;
        StringBuilder out = new StringBuilder("文件: ").append(path).append("\n\n");
        int contextStart = Math.max(0, prefix - 3);
        for (int i = contextStart; i < prefix; i++) out.append("  ").append(oldLines[i]).append('\n');
        int oldEnd = oldLines.length - suffix;
        int newEnd = newLines.length - suffix;
        for (int i = prefix; i < oldEnd; i++) appendLimited(out, "- ", oldLines[i]);
        for (int i = prefix; i < newEnd; i++) appendLimited(out, "+ ", newLines[i]);
        for (int i = 0; i < Math.min(3, suffix); i++) {
            out.append("  ").append(oldLines[oldLines.length - suffix + i]).append('\n');
        }
        if (out.length() > 24_000) return out.substring(0, 24_000) + "\n... Diff 已截断";
        return out.toString();
    }

    private void appendLimited(StringBuilder out, String prefix, String line) {
        if (out.length() < 24_000) out.append(prefix).append(line).append('\n');
    }

    private String translateStatus(String status) {
        switch (status) {
            case "Thinking": return "Agent 正在分析";
            case "Using project tools": return "Agent 正在检查工程";
            case "Ready": return "就绪";
            default: return status;
        }
    }

    private void setBusy(boolean busy, String status) {
        if (progress != null) progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (sendButton != null) sendButton.setEnabled(!busy);
        if (statusLabel != null) statusLabel.setText(status);
    }

    private TextView label(String value) {
        TextView label = text(value, 13, MUTED, Typeface.BOLD);
        label.setPadding(0, dp(8), 0, dp(6));
        return label;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        params.setMargins(0, 0, 0, dp(8));
        return params;
    }

    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", style));
        return view;
    }

    private EditText edit(String hint, boolean singleLine) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(INK);
        input.setHintTextColor(Color.rgb(130, 141, 135));
        input.setTextSize(14);
        input.setSingleLine(singleLine);
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        input.setBackground(rounded(SURFACE, 6, LINE));
        return input;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setTextColor(primary ? Color.WHITE : INK);
        button.setBackground(rounded(primary ? BRAND : SURFACE, 6, primary ? BRAND : LINE));
        button.setStateListAnimator(null);
        return button;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void showError(String message) {
        new AlertDialog.Builder(this).setTitle("操作失败").setMessage(message)
                .setPositiveButton("确定", null).show();
    }
}
