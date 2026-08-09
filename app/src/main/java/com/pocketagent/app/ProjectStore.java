package com.pocketagent.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ProjectStore {
    static final int MAX_FILES = 2500;
    static final int MAX_READ_BYTES = 1024 * 1024;
    private static final Set<String> SKIP_DIRS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            ".git", ".gradle", ".idea", "build", "node_modules", "dist", "target")));
    private static final Set<String> TEXT_EXTENSIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "c", "h", "cpp", "hpp", "cc", "java", "kt", "kts", "xml", "gradle", "properties",
            "json", "md", "txt", "py", "js", "ts", "tsx", "jsx", "html", "css", "scss", "yml",
            "yaml", "toml", "ini", "sh", "ps1", "bat", "sql", "rs", "go", "dart", "lua", "asm", "s")));

    static final class Entry {
        final String path;
        final Uri uri;
        final boolean directory;
        final long size;

        Entry(String path, Uri uri, boolean directory, long size) {
            this.path = path;
            this.uri = uri;
            this.directory = directory;
            this.size = size;
        }

        String label() {
            return (directory ? "[DIR]  " : "       ") + path;
        }
    }

    private final ContentResolver resolver;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private Uri treeUri;

    ProjectStore(Context context) {
        resolver = context.getContentResolver();
    }

    synchronized void setTreeUri(Uri uri) {
        treeUri = uri;
        entries.clear();
    }

    synchronized Uri getTreeUri() {
        return treeUri;
    }

    synchronized boolean isReady() {
        return treeUri != null;
    }

    synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries.values());
    }

    void refresh() throws Exception {
        Uri root;
        synchronized (this) {
            root = treeUri;
        }
        if (root == null) throw new IllegalStateException("Please select a project folder first");
        String rootId = DocumentsContract.getTreeDocumentId(root);
        Map<String, Entry> found = new LinkedHashMap<>();
        scanChildren(root, rootId, "", found);
        List<Entry> sorted = new ArrayList<>(found.values());
        sorted.sort(Comparator.comparing((Entry e) -> !e.directory).thenComparing(e -> e.path.toLowerCase(Locale.ROOT)));
        synchronized (this) {
            entries.clear();
            for (Entry entry : sorted) entries.put(entry.path, entry);
        }
    }

    private void scanChildren(Uri root, String parentId, String parentPath, Map<String, Entry> found) throws Exception {
        if (found.size() >= MAX_FILES) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(root, parentId);
        String[] columns = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext() && found.size() < MAX_FILES) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long size = cursor.isNull(3) ? 0 : cursor.getLong(3);
                boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                String path = parentPath.isEmpty() ? name : parentPath + "/" + name;
                if (directory && SKIP_DIRS.contains(name)) continue;
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(root, id);
                found.put(path, new Entry(path, uri, directory, size));
                if (directory) scanChildren(root, id, path, found);
            }
        }
    }

    String readFile(String path) throws Exception {
        Entry entry = find(path);
        if (entry.directory) throw new IllegalArgumentException("Path is a directory: " + path);
        if (entry.size > MAX_READ_BYTES) throw new IllegalArgumentException("File exceeds 1 MB limit: " + path);
        try (InputStream input = resolver.openInputStream(entry.uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalStateException("Cannot open " + path);
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_READ_BYTES) throw new IllegalArgumentException("File exceeds 1 MB limit: " + path);
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    synchronized String readIfExists(String path) throws Exception {
        Entry entry = entries.get(normalize(path));
        return entry == null ? "" : readFile(entry.path);
    }

    void writeFile(String path, String content) throws Exception {
        String normalized = normalize(path);
        Entry existing;
        synchronized (this) {
            existing = entries.get(normalized);
        }
        Uri target;
        if (existing != null) {
            if (existing.directory) throw new IllegalArgumentException("Cannot overwrite directory: " + normalized);
            target = existing.uri;
        } else {
            int slash = normalized.lastIndexOf('/');
            String parentPath = slash < 0 ? "" : normalized.substring(0, slash);
            String name = slash < 0 ? normalized : normalized.substring(slash + 1);
            Uri parentUri;
            synchronized (this) {
                Entry parent = parentPath.isEmpty() ? null : entries.get(parentPath);
                if (!parentPath.isEmpty() && (parent == null || !parent.directory)) {
                    throw new IllegalArgumentException("Parent directory does not exist: " + parentPath);
                }
                parentUri = parentPath.isEmpty()
                        ? DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                        : parent.uri;
            }
            target = DocumentsContract.createDocument(resolver, parentUri, "text/plain", name);
            if (target == null) throw new IllegalStateException("Could not create " + normalized);
        }
        try (OutputStream output = resolver.openOutputStream(target, "wt")) {
            if (output == null) throw new IllegalStateException("Cannot write " + normalized);
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
        refresh();
    }

    JSONArray listFiles(String path) throws Exception {
        String prefix = normalize(path);
        if (!prefix.isEmpty()) prefix += "/";
        JSONArray result = new JSONArray();
        synchronized (this) {
            for (Entry entry : entries.values()) {
                if (!entry.path.startsWith(prefix)) continue;
                String rest = entry.path.substring(prefix.length());
                if (rest.contains("/")) continue;
                result.put(new JSONObject()
                        .put("path", entry.path)
                        .put("type", entry.directory ? "directory" : "file")
                        .put("size", entry.size));
            }
        }
        return result;
    }

    JSONArray search(String query, String path) throws Exception {
        String needle = query.toLowerCase(Locale.ROOT);
        String prefix = normalize(path);
        JSONArray matches = new JSONArray();
        List<Entry> current = snapshot();
        for (Entry entry : current) {
            if (matches.length() >= 100 || entry.directory || entry.size > 512 * 1024) continue;
            if (!prefix.isEmpty() && !entry.path.startsWith(prefix + "/") && !entry.path.equals(prefix)) continue;
            if (!isTextFile(entry.path)) continue;
            String content;
            try {
                content = readFile(entry.path);
            } catch (Exception ignored) {
                continue;
            }
            String[] lines = content.split("\\R", -1);
            for (int i = 0; i < lines.length && matches.length() < 100; i++) {
                if (lines[i].toLowerCase(Locale.ROOT).contains(needle)) {
                    matches.put(new JSONObject()
                            .put("path", entry.path)
                            .put("line", i + 1)
                            .put("text", lines[i].trim()));
                }
            }
        }
        return matches;
    }

    List<Entry> filter(String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Entry> result = snapshot();
        if (!needle.isEmpty()) result.removeIf(entry -> !entry.path.toLowerCase(Locale.ROOT).contains(needle));
        return result;
    }

    private synchronized Entry find(String path) {
        Entry entry = entries.get(normalize(path));
        if (entry == null) throw new IllegalArgumentException("Path not found: " + path);
        return entry;
    }

    private static boolean isTextFile(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 || TEXT_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static String normalize(String path) {
        if (path == null) return "";
        String value = path.replace('\\', '/').trim();
        while (value.startsWith("/")) value = value.substring(1);
        if (value.equals(".")) return "";
        if (value.contains("../") || value.equals("..")) throw new IllegalArgumentException("Parent traversal is not allowed");
        return value;
    }
}
