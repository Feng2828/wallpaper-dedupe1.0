package com.workbuddy.wallpapergedupe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.workbuddy.wallpapergedupe.core.FeatureExtractor;
import com.workbuddy.wallpapergedupe.core.ImageScanner;
import com.workbuddy.wallpapergedupe.core.SimilarityEngine;
import com.workbuddy.wallpapergedupe.core.ThumbnailLoader;
import com.workbuddy.wallpapergedupe.model.DuplicateGroup;
import com.workbuddy.wallpapergedupe.model.ImageEntry;
import com.workbuddy.wallpapergedupe.ui.GroupAdapter;
import com.workbuddy.wallpapergedupe.util.ExportHelper;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 壁纸查重主界面。
 *
 * 安全边界：本 Activity 与整个工程只包含 ContentResolver 的「读」操作与
 * ACTION_CREATE_DOCUMENT 的「写一份文本清单」操作，没有任何删除图片的代码路径。
 */
public class MainActivity extends Activity implements GroupAdapter.MarksListener {

    private static final int REQ_TREE = 1001;
    private static final int REQ_CREATE_DOC = 1002;
    private static final int REQ_PERMISSION = 1003;

    private static final String PREF = "wd_pref";
    private static final String KEY_TREES = "tree_uris";
    private static final int SOURCE_SAF = 0;
    private static final int SOURCE_MEDIA = 1;

    private Context app;
    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private volatile boolean running = false;

    private final List<Uri> trees = new ArrayList<>();
    private int source = SOURCE_SAF;
    private List<ImageEntry> cachedItems = null;  // 已提取的特征，供「仅重新分组」复用
    private List<DuplicateGroup> groups = new ArrayList<>();
    private String pendingReport = "";
    private Runnable pendingPermissionAction = null;

    private ThumbnailLoader loader;
    private GroupAdapter adapter;

    private TextView tvFolders;
    private TextView tvThreshold;
    private SeekBar sbThreshold;
    private ProgressBar pbProgress;
    private TextView tvStatus;
    private TextView tvMarked;
    private Button btnScan;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        app = getApplicationContext();
        prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        loader = new ThumbnailLoader(app);

        tvFolders = findViewById(R.id.tvFolders);
        tvThreshold = findViewById(R.id.tvThreshold);
        sbThreshold = findViewById(R.id.sbThreshold);
        pbProgress = findViewById(R.id.pbProgress);
        tvStatus = findViewById(R.id.tvStatus);
        tvMarked = findViewById(R.id.tvMarked);
        btnScan = findViewById(R.id.btnScan);
        RecyclerView rvGroups = findViewById(R.id.rvGroups);

        rvGroups.setLayoutManager(new LinearLayoutManager(this));
        adapter = new GroupAdapter(this, loader, this);
        rvGroups.setAdapter(adapter);

        loadTrees();
        updateFolderText();
        tvThreshold.setText((60 + sbThreshold.getProgress()) + "%");
        updateMarkedCount();

        sbThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvThreshold.setText((60 + progress) + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                toast("阈值改为 " + (60 + seekBar.getProgress()) + "%，点「仅重新分组」生效");
            }
        });

        findViewById(R.id.btnAddTree).setOnClickListener(v -> openTreePicker());
        findViewById(R.id.btnClearTrees).setOnClickListener(v -> clearTrees());
        findViewById(R.id.btnMediaScan).setOnClickListener(v -> requestMediaPermission(() -> {
            source = SOURCE_MEDIA;
            updateFolderText();
            toast("来源已切换为「全部图片」，点「开始查重」开始");
        }));

        btnScan.setOnClickListener(v -> startScan(false));
        findViewById(R.id.btnRegroup).setOnClickListener(v -> startScan(true));
        findViewById(R.id.btnClearMarks).setOnClickListener(v -> {
            for (DuplicateGroup g : groups) {
                for (ImageEntry e : g.items) {
                    e.marked = false;
                }
            }
            adapter.notifyDataSetChanged();
            updateMarkedCount();
            toast("已清空所有勾选");
        });
        findViewById(R.id.btnExport).setOnClickListener(v -> exportList());
    }

    // ------------------------------------------------------------------ 目录授权

    private void openTreePicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        // 让部分厂商的 DocumentsUI 显示内部存储根节点
        i.putExtra("android.content.extra.SHOW_ADVANCED", true);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            toast("无法打开目录选择器：" + e.getMessage());
        }
    }

    private void loadTrees() {
        trees.clear();
        Set<String> saved = prefs.getStringSet(KEY_TREES, new HashSet<String>());
        List<UriPermission> granted = getContentResolver().getPersistedUriPermissions();
        for (String s : saved) {
            Uri u = Uri.parse(s);
            boolean valid = false;
            for (UriPermission p : granted) {
                if (p.getUri().equals(u) && p.isReadPermission()) {
                    valid = true;
                    break;
                }
            }
            if (valid && !trees.contains(u)) {
                trees.add(u);
            }
        }
    }

    private void saveTrees() {
        Set<String> set = new HashSet<>();
        for (Uri u : trees) {
            set.add(u.toString());
        }
        prefs.edit().putStringSet(KEY_TREES, set).apply();
    }

    private void clearTrees() {
        for (Uri u : trees) {
            try {
                getContentResolver().releasePersistableUriPermission(
                        u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
        }
        trees.clear();
        saveTrees();
        source = SOURCE_SAF;
        updateFolderText();
        toast("已清除所有目录授权");
    }

    private void updateFolderText() {
        if (source == SOURCE_MEDIA) {
            tvFolders.setText("当前来源：MediaStore 全部图片（需已授予读取图片权限）\n"
                    + "点「添加目录」可切回 SAF 目录方式");
            return;
        }
        if (trees.isEmpty()) {
            tvFolders.setText("当前来源：SAF 目录 · 尚未选择目录\n"
                    + "建议选择 Pictures / DCIM / Download 下的壁纸文件夹，可添加多个");
            return;
        }
        StringBuilder sb = new StringBuilder("当前来源：SAF 目录 · 已授权 ")
                .append(trees.size()).append(" 个\n");
        for (Uri u : trees) {
            String seg = u.getLastPathSegment();
            sb.append("· ").append(seg == null ? u.toString() : Uri.decode(seg)).append('\n');
        }
        tvFolders.setText(sb.toString().trim());
    }

    // ------------------------------------------------------------------ 权限（仅 MediaStore 入口需要）

    private void requestMediaPermission(Runnable after) {
        String perm;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perm = Manifest.permission.READ_MEDIA_IMAGES;
        } else {
            perm = Manifest.permission.READ_EXTERNAL_STORAGE;
        }
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            after.run();
            return;
        }
        pendingPermissionAction = after;
        requestPermissions(new String[]{perm}, REQ_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMISSION) {
            return;
        }
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED
                && pendingPermissionAction != null) {
            pendingPermissionAction.run();
        } else {
            toast("未获得图片读取权限，仍可使用「添加目录」的 SAF 方式（无需任何权限）");
        }
        pendingPermissionAction = null;
    }

    // ------------------------------------------------------------------ 扫描 / 分组

    private float currentThreshold() {
        return (60 + sbThreshold.getProgress()) / 100.0f;
    }

    private void startScan(final boolean reuseFeatures) {
        if (running) {
            cancel.set(true);
            setStatus("正在停止…");
            return;
        }
        if (reuseFeatures && (cachedItems == null || cachedItems.isEmpty())) {
            toast("请先执行一次「开始查重」，之后才能仅重新分组");
            return;
        }
        if (!reuseFeatures && source == SOURCE_SAF && trees.isEmpty()) {
            toast("请先点「添加目录」选择壁纸文件夹");
            return;
        }

        running = true;
        cancel.set(false);
        btnScan.setText("停止");
        setProgress(0);
        final float threshold = currentThreshold();
        final int src = source;
        final List<Uri> snapshot = new ArrayList<>(trees);
        final int max = ImageScanner.DEFAULT_MAX;

        worker.execute(() -> {
            try {
                List<ImageEntry> items;
                if (reuseFeatures) {
                    items = cachedItems;
                    ui(() -> setStatus("复用已提取的特征，按新阈值重新分组…"));
                } else {
                    ImageScanner.Progress sp = (msg, done, pairs) -> ui(() -> setStatus(msg));
                    items = (src == SOURCE_MEDIA)
                            ? ImageScanner.scanMediaStore(app, max, sp)
                            : ImageScanner.scanTrees(app, snapshot, max, sp);
                    if (cancel.get()) {
                        ui(() -> setStatus("已停止"));
                        return;
                    }
                    if (items.isEmpty()) {
                        ui(() -> {
                            setStatus("没有找到任何图片");
                            toast("没有找到图片：请确认目录选择正确，或换用「全部图片」来源");
                        });
                        return;
                    }

                    final int total = items.size();
                    List<ImageEntry> valid = new ArrayList<>();
                    for (int i = 0; i < total; i++) {
                        if (cancel.get()) {
                            ui(() -> setStatus("已停止"));
                            return;
                        }
                        ImageEntry e = items.get(i);
                        if (FeatureExtractor.fill(app, e)) {
                            valid.add(e);
                        }
                        final int d = i + 1;
                        if (d % 5 == 0 || d == total) {
                            final int pct = d * 55 / total;
                            ui(() -> {
                                setStatus("提取图像特征 " + d + " / " + total);
                                setProgress(pct);
                            });
                        }
                    }
                    items = valid;
                    if (items.size() < 2) {
                        ui(() -> {
                            setStatus("有效图片不足 2 张，无法比对");
                            toast("有效图片不足 2 张（过小或无法解码的图片已被跳过）");
                        });
                        return;
                    }
                    cachedItems = items;
                }

                final List<ImageEntry> data = items;
                List<DuplicateGroup> result = SimilarityEngine.group(data, threshold,
                        (msg, done, pairs) -> {
                            final int pct = (int) (55L + 45L * done / Math.max(1, pairs));
                            ui(() -> {
                                setStatus(msg);
                                setProgress(pct);
                            });
                        }, cancel);

                if (cancel.get()) {
                    ui(() -> setStatus("已停止，可调整阈值后再试"));
                    return;
                }
                ui(() -> {
                    groups = result;
                    adapter.setGroups(result);
                    setProgress(100);
                    setStatus("完成：" + data.size() + " 张图片，阈值 "
                            + (int) (threshold * 100) + "%，发现 " + result.size() + " 组相似壁纸");
                    updateMarkedCount();
                    if (result.isEmpty()) {
                        toast("未发现相似图片，可把阈值调低一些再试");
                    }
                });
            } catch (Throwable t) {
                ui(() -> {
                    setStatus("出错：" + t.getMessage());
                    toast("扫描出错：" + t.getMessage());
                });
            } finally {
                ui(() -> {
                    running = false;
                    btnScan.setText("开始查重");
                });
            }
        });
    }

    // ------------------------------------------------------------------ 清单导出

    private void exportList() {
        pendingReport = ExportHelper.report(groups, currentThreshold());
        int count = ExportHelper.countMarked(groups);
        String full = ExportHelper.fullyMarkedGroupNumbers(groups);

        StringBuilder msg = new StringBuilder();
        msg.append("将生成一份纯文本清单，记录 ").append(count).append(" 个已勾选项目。\n")
                .append("本应用不会删除任何文件，清单仅供人工核对。\n");
        if (count == 0) {
            msg.append("\n当前没有任何勾选，导出的清单会是空清单。\n");
        }
        if (full.length() > 0) {
            msg.append("\n⚠ 第 ").append(full)
                    .append(" 组的全部图片都被勾选，删除后将不保留任何副本，请务必复核！\n");
        }
        msg.append("\n保存方式：先写一份到应用私有目录，再让你选择保存位置（下载目录等）。");

        new AlertDialog.Builder(this)
                .setTitle("生成待删除清单")
                .setMessage(msg.toString())
                .setPositiveButton("继续导出", (d, w) -> {
                    try {
                        File f = ExportHelper.writeLocalCopy(this, pendingReport);
                        setStatus("本机副本：" + f.getAbsolutePath());
                    } catch (IOException e) {
                        toast("本机副本写入失败：" + e.getMessage());
                    }
                    launchCreateDocument();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void launchCreateDocument() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE,
                "wallpaper_pending_delete_" + ExportHelper.timestamp() + ".txt");
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_CREATE_DOC);
        } catch (Exception e) {
            toast("未找到文件保存界面；清单已保存在应用私有目录，可直接复制全文");
            showReport(pendingReport);
        }
    }

    private void showReport(String text) {
        String preview = text.length() > 1000 ? text.substring(0, 1000) + "\n…（以下省略）" : text;
        new AlertDialog.Builder(this)
                .setTitle("待删除清单 · 预览")
                .setMessage(preview)
                .setPositiveButton("复制全文", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("待删除清单", text));
                        toast("清单全文已复制到剪贴板");
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQ_TREE) {
            Uri u = data.getData();
            if (u == null) {
                return;
            }
            try {
                getContentResolver().takePersistableUriPermission(
                        u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                toast("目录授权持久化失败：" + e.getMessage() + "（本次仍可临时读取）");
            }
            if (!trees.contains(u)) {
                trees.add(u);
            }
            source = SOURCE_SAF;
            saveTrees();
            updateFolderText();
            toast("已添加目录授权，点「开始查重」开始");
        } else if (requestCode == REQ_CREATE_DOC) {
            Uri u = data.getData();
            if (u == null) {
                return;
            }
            try (OutputStream os = getContentResolver().openOutputStream(u)) {
                if (os == null) {
                    throw new IOException("无法打开输出流");
                }
                ExportHelper.write(os, pendingReport);
                toast("清单已保存到所选位置");
            } catch (Exception e) {
                toast("写入失败：" + e.getMessage() + "（本机副本仍可用）");
            }
            showReport(pendingReport);
        }
    }

    // ------------------------------------------------------------------ 界面辅助

    @Override
    public void onMarksChanged() {
        updateMarkedCount();
    }

    private void updateMarkedCount() {
        int n = ExportHelper.countMarked(groups);
        long bytes = ExportHelper.markedBytes(groups);
        tvMarked.setText("已勾选 " + n + " 项 · 约 " + ExportHelper.formatSize(bytes));
    }

    private void setStatus(String s) {
        tvStatus.setText(s);
    }

    private void setProgress(int p) {
        pbProgress.setProgress(Math.max(0, Math.min(100, p)));
    }

    private void ui(Runnable r) {
        mainHandler.post(r);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        cancel.set(true);
        worker.shutdownNow();
        loader.shutdown();
        super.onDestroy();
    }
}
