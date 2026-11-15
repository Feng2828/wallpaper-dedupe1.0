package com.workbuddy.wallpapergedupe.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.workbuddy.wallpapergedupe.R;
import com.workbuddy.wallpapergedupe.core.ThumbnailLoader;
import com.workbuddy.wallpapergedupe.model.DuplicateGroup;
import com.workbuddy.wallpapergedupe.model.ImageEntry;
import com.workbuddy.wallpapergedupe.util.ExportHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 外层纵向列表：每组一个卡片，卡片内嵌套一个横向缩略图列表。 */
public class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.VH> {

    public interface MarksListener {
        void onMarksChanged();
    }

    private final Context ctx;
    private final ThumbnailLoader loader;
    private final MarksListener listener;
    private final List<DuplicateGroup> groups = new ArrayList<>();
    private final RecyclerView.RecycledViewPool sharedPool = new RecyclerView.RecycledViewPool();

    public GroupAdapter(Context ctx, ThumbnailLoader loader, MarksListener listener) {
        this.ctx = ctx;
        this.loader = loader;
        this.listener = listener;
    }

    public void setGroups(List<DuplicateGroup> data) {
        groups.clear();
        if (data != null) {
            groups.addAll(data);
        }
        notifyDataSetChanged();
    }

    public List<DuplicateGroup> getGroups() {
        return groups;
    }

    @Override
    public VH onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_group, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(VH h, int position) {
        final DuplicateGroup g = groups.get(position);
        h.title.setText("第 " + g.index + " 组 · " + g.items.size() + " 张相似壁纸");
        h.score.setText(String.format(Locale.CHINA, "平均 %.0f%%", g.avgScore * 100));
        if (g.keeper != null) {
            h.hint.setText("建议保留：" + g.keeper.name
                    + "（" + g.keeper.width + "×" + g.keeper.height + "，"
                    + ExportHelper.formatSize(g.keeper.size) + "） · 勾选框仅记录清单，不删文件");
        }

        h.thumbs.setLayoutManager(new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false));
        h.thumbs.setRecycledViewPool(sharedPool);
        h.thumbs.setNestedScrollingEnabled(false);
        final ThumbAdapter ta = new ThumbAdapter(g, loader, listener);
        h.thumbs.setAdapter(ta);
        h.thumbs.scrollToPosition(0);

        h.keepBest.setOnClickListener(v -> {
            for (ImageEntry e : g.items) {
                e.marked = (e != g.keeper);
            }
            ta.notifyDataSetChanged();
            if (listener != null) {
                listener.onMarksChanged();
            }
        });
        h.clear.setOnClickListener(v -> {
            for (ImageEntry e : g.items) {
                e.marked = false;
            }
            ta.notifyDataSetChanged();
            if (listener != null) {
                listener.onMarksChanged();
            }
        });
    }

    @Override
    public int getItemCount() {
        return groups.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView title;
        TextView score;
        TextView hint;
        Button keepBest;
        Button clear;
        RecyclerView thumbs;

        VH(View v) {
            super(v);
            title = v.findViewById(R.id.tvGroupTitle);
            score = v.findViewById(R.id.tvGroupScore);
            hint = v.findViewById(R.id.tvGroupHint);
            keepBest = v.findViewById(R.id.btnKeepBest);
            clear = v.findViewById(R.id.btnGroupClear);
            thumbs = v.findViewById(R.id.rvThumbs);
        }
    }
}
