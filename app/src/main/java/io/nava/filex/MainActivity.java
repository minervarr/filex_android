package io.nava.filex;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import io.nava.archive_engine.ArchiveEngine;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity implements FileAdapter.Listener {

    private static final String TAG       = "MainActivity";
    private static final String AUTHORITY = "io.nava.filex.provider";
    private static final String ROOT      = Environment.getExternalStorageDirectory().getAbsolutePath();

    private enum SortMode { NAME, SIZE, DATE, TYPE }

    // ── Persistent state ──────────────────────────────────────────────────────
    private boolean   pickerMode = false;
    private SortMode  sortMode   = SortMode.NAME;
    private boolean   showHidden = false;
    private FileNode  clipNode   = null;
    private boolean   clipIsCut  = false;
    private boolean   hasLoaded  = false;

    // ── Views ─────────────────────────────────────────────────────────────────
    private Toolbar      toolbar;
    private TextView     tvPath;
    private RecyclerView recyclerView;
    private ProgressBar  progressBar;
    private MenuItem     pasteItem;

    // ── Data ──────────────────────────────────────────────────────────────────
    private FileAdapter      adapter;
    private final List<FileNode> currentList = new ArrayList<>();
    private final List<FileNode> allEntries  = new ArrayList<>();
    private final ArrayDeque<String> backStack = new ArrayDeque<>();
    private String          currentPath = ROOT;
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Selection / ActionMode ────────────────────────────────────────────────
    private ActionMode actionMode = null;

    // ── Auto-refresh ──────────────────────────────────────────────────────────
    private FileObserver fileObserver = null;
    private final Runnable reloadRunnable = () -> {
        if (!isLoading.get() && actionMode == null) {
            loadDirectory(currentPath);
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        toolbar      = findViewById(R.id.toolbar);
        tvPath       = findViewById(R.id.tvPath);
        recyclerView  = findViewById(R.id.recyclerView);
        progressBar   = findViewById(R.id.progressBar);

        pickerMode = Intent.ACTION_GET_CONTENT.equals(getIntent().getAction());

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(pickerMode ? "Pick a file" : "FileX");
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
        }

        ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(0, 0, 0, bottom);
            return insets;
        });

        adapter = new FileAdapter(currentList, this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        recyclerView.setAdapter(adapter);

        tvPath.setOnLongClickListener(v -> { copyPathToClipboard(currentPath); return true; });

        DebugLogger.init(this);
        DebugLogger.i(TAG, "onCreate");

        if (!Environment.isExternalStorageManager()) {
            requestAllFilesAccess();
        } else {
            loadDirectory(ROOT);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Environment.isExternalStorageManager() && !hasLoaded && !isLoading.get()) {
            loadDirectory(currentPath);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (fileObserver != null) fileObserver.stopWatching();
        DebugLogger.close();
    }

    @Override
    public void onBackPressed() {
        if (actionMode != null) {
            actionMode.finish();
            return;
        }
        if (!backStack.isEmpty()) {
            currentPath = backStack.pollLast();
            hasLoaded = false;
            loadDirectory(currentPath);
            updateUpButton();
        } else {
            super.onBackPressed();
        }
    }

    // ── Toolbar ───────────────────────────────────────────────────────────────

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar_menu, menu);
        pasteItem = menu.findItem(R.id.action_paste);
        updatePasteItem();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home)           { onBackPressed(); return true; }
        if (id == R.id.action_new_folder)      { showCreateFolderDialog(); return true; }
        if (id == R.id.action_paste)           { doPaste(); return true; }
        if (id == R.id.action_sort)            { showSortDialog(); return true; }
        if (id == R.id.action_toggle_hidden)   {
            showHidden = !showHidden;
            item.setTitle(showHidden ? "Hide hidden files" : "Show hidden files");
            applyFilterAndSort();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ── FileAdapter.Listener ──────────────────────────────────────────────────

    @Override
    public void onTap(FileNode node) {
        if (node.isDirectory) {
            backStack.addLast(currentPath);
            currentPath = node.absolutePath;
            hasLoaded = false;
            loadDirectory(currentPath);
            updateUpButton();
        } else if (pickerMode) {
            Intent result = new Intent();
            result.setData(Uri.fromFile(new File(node.absolutePath)));
            setResult(RESULT_OK, result);
            finish();
        } else {
            openFile(node);
        }
    }

    @Override
    public void onLongPress(FileNode node, View anchor) {
        if (actionMode != null) {
            // Already in selection mode: treat as a toggle (same as tap)
            onSelectionToggle(node);
            return;
        }
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, R.id.ctx_properties, 0, "Properties");
        menu.getMenu().add(0, R.id.ctx_copy_path,  1, "Copy path");
        menu.getMenu().add(0, R.id.ctx_rename,     2, "Rename");
        menu.getMenu().add(0, R.id.ctx_copy,       3, "Copy");
        menu.getMenu().add(0, R.id.ctx_move,       4, "Move");
        menu.getMenu().add(0, R.id.ctx_delete,     5, "Delete");
        menu.getMenu().add(0, R.id.ctx_compress,   6, "Compress");
        menu.getMenu().add(0, R.id.ctx_select_all, 7, "Select");
        if (isArchive(node.name))
            menu.getMenu().add(0, R.id.ctx_extract, 8, "Extract here");

        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.ctx_properties)          showProperties(node);
            else if (id == R.id.ctx_copy_path)      copyPathToClipboard(node.absolutePath);
            else if (id == R.id.ctx_rename)          showRenameDialog(node);
            else if (id == R.id.ctx_copy)            setClipboard(node, false);
            else if (id == R.id.ctx_move)            setClipboard(node, true);
            else if (id == R.id.ctx_delete)          confirmDelete(node);
            else if (id == R.id.ctx_compress)        showCompressDialog(node);
            else if (id == R.id.ctx_extract)         doExtract(node);
            else if (id == R.id.ctx_select_all) {
                actionMode = startSupportActionMode(selectionCallback);
                adapter.setSelectionMode(true);
                adapter.toggleSelection(node.absolutePath);
                updateActionModeTitle();
            }
            return true;
        });
        menu.show();
    }

    @Override
    public void onSelectionToggle(FileNode node) {
        adapter.toggleSelection(node.absolutePath);
        if (adapter.getSelectedCount() == 0 && actionMode != null) {
            actionMode.finish();
        } else {
            updateActionModeTitle();
        }
    }

    // ── Selection / ActionMode ────────────────────────────────────────────────

    private final ActionMode.Callback selectionCallback = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            menu.add(0, R.id.ctx_delete,     0, "Delete")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            menu.add(0, R.id.ctx_select_all, 1, "Select all")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            int id = item.getItemId();
            if (id == R.id.ctx_delete) {
                confirmDeleteSelected();
                return true;
            }
            if (id == R.id.ctx_select_all) {
                adapter.selectAll(currentList);
                updateActionModeTitle();
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            actionMode = null;
            adapter.setSelectionMode(false);
        }
    };

    private void updateActionModeTitle() {
        if (actionMode != null) {
            int count = adapter.getSelectedCount();
            actionMode.setTitle(count + " selected");
        }
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private void loadDirectory(String path) {
        DebugLogger.d(TAG, "loadDirectory path=" + path);
        tvPath.setText(path);
        progressBar.setVisibility(View.VISIBLE);
        isLoading.set(true);

        new Thread(() -> {
            FileNode[] nodes = FileManager.listDirectory(path);
            String err = (nodes == null) ? FileManager.getLastError() : "";

            post(() -> {
                isLoading.set(false);
                hasLoaded = true;
                progressBar.setVisibility(View.GONE);
                if (nodes == null) {
                    DebugLogger.e(TAG, "listDirectory failed: " + err);
                    showError("Cannot read directory", err);
                    return;
                }
                allEntries.clear();
                allEntries.addAll(Arrays.asList(nodes));
                applyFilterAndSort();
                startWatching(path);
                DebugLogger.d(TAG, "loaded " + nodes.length + " entries");
            });
        }).start();
    }

    private void applyFilterAndSort() {
        currentList.clear();
        for (FileNode n : allEntries) {
            if (!showHidden && n.name.startsWith(".")) continue;
            currentList.add(n);
        }

        Comparator<FileNode> dirFirst = Comparator.comparingInt(n -> n.isDirectory ? 0 : 1);
        Comparator<FileNode> cmp;
        switch (sortMode) {
            case SIZE:
                cmp = dirFirst.thenComparingLong(n -> n.size);
                break;
            case DATE:
                cmp = dirFirst.thenComparing(Comparator.comparingLong((FileNode n) -> n.modifiedMs).reversed());
                break;
            case TYPE:
                cmp = dirFirst.thenComparing(n -> extension(n.name));
                break;
            default:
                cmp = dirFirst.thenComparing(n -> n.name.toLowerCase(Locale.ROOT));
                break;
        }
        currentList.sort(cmp);
        adapter.notifyDataSetChanged();
    }

    private void showSortDialog() {
        String[] labels = {"By name", "By size", "By date", "By type"};
        SortMode[] modes = {SortMode.NAME, SortMode.SIZE, SortMode.DATE, SortMode.TYPE};
        int current = Arrays.asList(modes).indexOf(sortMode);
        new AlertDialog.Builder(this)
            .setTitle("Sort")
            .setSingleChoiceItems(labels, current, (d, which) -> {
                sortMode = modes[which];
                applyFilterAndSort();
                d.dismiss();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Auto-refresh ──────────────────────────────────────────────────────────

    private void startWatching(String path) {
        if (fileObserver != null) fileObserver.stopWatching();
        int mask = FileObserver.CREATE | FileObserver.DELETE
                 | FileObserver.MOVED_FROM | FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE;
        fileObserver = new FileObserver(new File(path), mask) {
            @Override
            public void onEvent(int event, @Nullable String name) {
                mainHandler.removeCallbacks(reloadRunnable);
                mainHandler.postDelayed(reloadRunnable, 500);
            }
        };
        fileObserver.startWatching();
    }

    // ── Create folder ─────────────────────────────────────────────────────────

    private void showCreateFolderDialog() {
        EditText input = new EditText(this);
        input.setHint("Folder name");
        new AlertDialog.Builder(this)
            .setTitle("New folder")
            .setView(input)
            .setPositiveButton("Create", (d, w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) return;
                String newPath = currentPath + "/" + name;
                new Thread(() -> {
                    boolean ok = FileManager.createDirectory(newPath);
                    post(() -> {
                        if (ok) loadDirectory(currentPath);
                        else    showError("Create folder failed", "Could not create: " + newPath);
                    });
                }).start();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Copy / Move ───────────────────────────────────────────────────────────

    private void setClipboard(FileNode node, boolean cut) {
        clipNode  = node;
        clipIsCut = cut;
        updatePasteItem();
        toast((cut ? "Move: " : "Copy: ") + node.name + "\nNavigate to destination, then Paste");
    }

    private void updatePasteItem() {
        if (pasteItem != null) pasteItem.setVisible(clipNode != null);
    }

    private void doPaste() {
        if (clipNode == null) return;
        FileNode src = clipNode;
        boolean  cut = clipIsCut;
        clipNode  = null;
        clipIsCut = false;
        updatePasteItem();

        String dst = currentPath + "/" + src.name;
        if (new File(dst).exists()) {
            new AlertDialog.Builder(this)
                .setTitle("Conflict")
                .setMessage("\"" + src.name + "\" already exists here. Overwrite?")
                .setPositiveButton("Overwrite", (d, w) -> executePaste(src, dst, cut))
                .setNegativeButton("Cancel", null)
                .show();
            return;
        }
        executePaste(src, dst, cut);
    }

    private void executePaste(FileNode src, String dst, boolean cut) {
        progressBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            boolean ok;
            String  err;
            if (cut) {
                ok = FileManager.renameEntry(src.absolutePath, dst);
                err = ok ? "" : FileManager.getLastError();
                if (!ok) {
                    ok  = FileManager.copyEntry(src.absolutePath, dst);
                    err = ok ? "" : "copy failed";
                    if (ok) {
                        boolean deleted = FileManager.deleteEntry(src.absolutePath);
                        if (!deleted) err = FileManager.getLastError();
                        ok = deleted;
                    }
                }
            } else {
                ok  = FileManager.copyEntry(src.absolutePath, dst);
                err = ok ? "" : "copy failed";
            }
            final boolean finalOk  = ok;
            final String  finalErr = err;
            post(() -> {
                progressBar.setVisibility(View.GONE);
                if (finalOk) { toast("Done"); loadDirectory(currentPath); }
                else           showError("Paste failed", finalErr);
            });
        }).start();
    }

    // ── Properties ────────────────────────────────────────────────────────────

    private void showProperties(FileNode node) {
        new Thread(() -> {
            FileNode props   = FileManager.getProperties(node.absolutePath);
            String   propErr = (props == null) ? FileManager.getLastError() : "";
            long     dirSize = (node.isDirectory && props != null)
                    ? FileManager.directorySize(node.absolutePath) : -1L;

            post(() -> {
                if (props == null) { showError("Properties failed", propErr); return; }

                String sizeStr = node.isDirectory
                        ? (dirSize >= 0 ? Formatter.formatFileSize(this, dirSize) + " (total)" : "unavailable")
                        : props.formattedSize();

                String modified = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(new Date(props.modifiedMs));

                String msg =
                    "Name:        " + props.name         + "\n" +
                    "Path:        " + props.absolutePath + "\n" +
                    "Type:        " + (props.isDirectory ? "Directory" : "File")
                                    + (props.isSymlink ? " (symlink)" : "") + "\n" +
                    "MIME:        " + (node.isDirectory ? "directory" : getMimeType(node.name)) + "\n" +
                    "Size:        " + sizeStr             + "\n" +
                    "Modified:    " + modified            + "\n" +
                    "Permissions: " + props.permissionString();

                new AlertDialog.Builder(this)
                    .setTitle("Properties")
                    .setMessage(msg)
                    .setPositiveButton("Copy path", (d, w) -> copyPathToClipboard(props.absolutePath))
                    .setNegativeButton("Close", null)
                    .show();
            });
        }).start();
    }

    // ── Rename ────────────────────────────────────────────────────────────────

    private void showRenameDialog(FileNode node) {
        EditText input = new EditText(this);
        input.setText(node.name);
        input.selectAll();
        new AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Rename", (d, w) -> {
                String newName = input.getText().toString().trim();
                if (newName.isEmpty() || newName.equals(node.name)) return;
                String parent  = new File(node.absolutePath).getParent();
                String newPath = (parent != null ? parent : currentPath) + "/" + newName;
                new Thread(() -> {
                    boolean ok  = FileManager.renameEntry(node.absolutePath, newPath);
                    String  err = ok ? "" : FileManager.getLastError();
                    post(() -> {
                        if (ok) loadDirectory(currentPath);
                        else    showError("Rename failed", err);
                    });
                }).start();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Delete (single) ───────────────────────────────────────────────────────

    private void confirmDelete(FileNode node) {
        new AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage("Delete \"" + node.name + "\"?\nThis cannot be undone.")
            .setPositiveButton("Delete", (d, w) -> {
                progressBar.setVisibility(View.VISIBLE);
                new Thread(() -> {
                    boolean ok  = FileManager.deleteEntry(node.absolutePath);
                    String  err = ok ? "" : FileManager.getLastError();
                    post(() -> {
                        progressBar.setVisibility(View.GONE);
                        if (ok) loadDirectory(currentPath);
                        else    showError("Delete failed", err);
                    });
                }).start();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Delete (multi-select) ─────────────────────────────────────────────────

    private void confirmDeleteSelected() {
        Set<String> paths = adapter.getSelectedPaths();
        if (paths.isEmpty()) return;

        List<FileNode> toDelete = new ArrayList<>();
        for (FileNode n : currentList) {
            if (paths.contains(n.absolutePath)) toDelete.add(n);
        }
        if (toDelete.isEmpty()) return;

        // Compute total size on background thread, then show dialog
        new Thread(() -> {
            long totalBytes = 0;
            for (FileNode n : toDelete) {
                if (n.isDirectory) {
                    long s = FileManager.directorySize(n.absolutePath);
                    if (s > 0) totalBytes += s;
                } else {
                    totalBytes += n.size;
                }
            }
            final long finalBytes = totalBytes;
            post(() -> {
                int count = toDelete.size();
                String sizeStr = Formatter.formatFileSize(this, finalBytes);
                String msg = "Delete " + count + " item" + (count == 1 ? "" : "s")
                           + " (" + sizeStr + ")?\nThis cannot be undone.";
                new AlertDialog.Builder(this)
                    .setTitle("Delete")
                    .setMessage(msg)
                    .setPositiveButton("Delete", (d, w) -> doDeleteSelected(toDelete))
                    .setNegativeButton("Cancel", null)
                    .show();
            });
        }).start();
    }

    private void doDeleteSelected(List<FileNode> nodes) {
        progressBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            List<String> errors = new ArrayList<>();
            for (FileNode n : nodes) {
                boolean ok = FileManager.deleteEntry(n.absolutePath);
                if (!ok) errors.add(n.name + ": " + FileManager.getLastError());
            }
            post(() -> {
                progressBar.setVisibility(View.GONE);
                if (actionMode != null) actionMode.finish();
                loadDirectory(currentPath);
                if (!errors.isEmpty()) {
                    showError("Some deletions failed", String.join("\n", errors));
                }
            });
        }).start();
    }

    // ── Compress ──────────────────────────────────────────────────────────────

    private void showCompressDialog(FileNode node) {
        String[] formats = {"tar.gz", "zip"};
        new AlertDialog.Builder(this)
            .setTitle("Compress as")
            .setItems(formats, (d, which) -> {
                String fmt    = formats[which];
                String ext    = fmt.equals("zip") ? ".zip" : ".tar.gz";
                String parent = new File(node.absolutePath).getParent();
                if (parent == null) parent = currentPath;
                String dest   = parent + "/" + node.name + ext;
                final String finalDest = dest;
                progressBar.setVisibility(View.VISIBLE);
                new Thread(() -> {
                    boolean ok  = ArchiveEngine.compress(new String[]{node.absolutePath}, finalDest, fmt);
                    String  err = ok ? "" : ArchiveEngine.getLastError();
                    post(() -> {
                        progressBar.setVisibility(View.GONE);
                        if (ok) { toast("Compressed → " + finalDest); loadDirectory(currentPath); }
                        else      showError("Compress failed", err);
                    });
                }).start();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Extract ───────────────────────────────────────────────────────────────

    private void doExtract(FileNode node) {
        String parent = new File(node.absolutePath).getParent();
        if (parent == null) parent = currentPath;
        final String destDir = parent;
        progressBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            boolean ok  = ArchiveEngine.extract(node.absolutePath, destDir);
            String  err = ok ? "" : ArchiveEngine.getLastError();
            post(() -> {
                progressBar.setVisibility(View.GONE);
                if (ok) { toast("Extracted to " + destDir); loadDirectory(currentPath); }
                else      showError("Extract failed", err);
            });
        }).start();
    }

    // ── Open file ─────────────────────────────────────────────────────────────

    private void openFile(FileNode node) {
        File   file = new File(node.absolutePath);
        String ext  = extension(node.name);

        if ("apk".equals(ext)) { installApk(file); return; }

        String mime = getMimeType(node.name);
        Uri    uri  = FileProvider.getUriForFile(this, AUTHORITY, file);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            startActivity(intent);
        } else {
            toast("No app can open " + (mime.equals("application/octet-stream") ? ext : mime) + " files");
        }
    }

    private void installApk(File apk) {
        Uri    uri    = FileProvider.getUriForFile(this, AUTHORITY, apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            toast("Enable 'Install unknown apps' for FileX, then tap the APK again");
        } else {
            startActivity(intent);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void requestAllFilesAccess() {
        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName())));
        toast("Grant 'All files access' then return to the app");
    }

    private void updateUpButton() {
        if (getSupportActionBar() != null)
            getSupportActionBar().setDisplayHomeAsUpEnabled(!backStack.isEmpty());
    }

    private void post(Runnable r) {
        mainHandler.post(() -> {
            if (!isFinishing() && !isDestroyed()) r.run();
        });
    }

    private static boolean isArchive(String name) {
        String l = name.toLowerCase(Locale.US);
        return l.endsWith(".zip")  || l.endsWith(".tar")    || l.endsWith(".tar.gz")
            || l.endsWith(".tgz") || l.endsWith(".tar.bz2") || l.endsWith(".tar.xz");
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
    }

    private static String getMimeType(String name) {
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(name));
        return mime != null ? mime : "application/octet-stream";
    }

    private void copyPathToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("path", text));
        toast("Copied: " + text);
    }

    private void showError(String title, String detail) {
        DebugLogger.e(TAG, title + " | " + detail);
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(detail == null || detail.isEmpty() ? "Unknown error" : detail)
            .setPositiveButton("OK", null)
            .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
