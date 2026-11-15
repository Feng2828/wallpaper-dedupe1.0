package com.workbuddy.wallpapergedupe.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.workbuddy.wallpapergedupe.R;
import com.workbuddy.wallpapergedupe.core.ThumbnailLoader;
import com.workbuddy.wallpapergedupe.model.DuplicateGroup;
import com.workbuddy.wallpapergedupe.model.ImageEntry;
import com.workbuddy.wallpapergedupe.util.ExportHelper;

import java.util.Locale;

/** 组内横向缩略图列表：图片 + 勾选框 + 名称 / 尺寸 / 相似度。 */
public class ThumbAdapter extends RecyclerView.Adapter<ThumbAdapter.VH> {

    private final DuplicateGroup group;
    private final ThumbnailLoader loader;
    private final GroupAdapter.MarksListener listener;

    public ThumbAdapter(DuplicateGroup group, ThumbnailLoader loader,
                        GroupAdapter.MarksListener listener) {
        this.group = group;
        this.loader = loader;
        this.listener = listener;
    }

    @Override
    public VH onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_thumb, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(VH h, int position) {
        final ImageEntry e = group.items.get(position);

        // 先解绑监听器，避免 RecyclerView 复用时误触发
        h.check.setOnCheckedChangeListener(null);
        h.check.setChecked(e.marked);
        h.check.setOnCheckedChangeListener((button, checked) -> {
            e.marked = checked;
            if (listener != null) {
                listener.onMarksChanged();
            }
        });

        if (e == group.keeper) {
            h.badge.setText("★ 建议保留");
            h.badge.setTextColor(0xFF237A55);
        } else {
            h.badge.setText(String.format(Locale.CHINA, "相似 %.0f%%", e.simToKeeper * 100));
            h.badge.setTextColor(0xFF3F6FB5);
        }
        h.name.setText(e.name);
        h.meta.setText(e.width + "×" + e.height + " · " + ExportHelper.formatSize(e.size));
        loader.load(e.uri, h.image);
    }

    @Override
    public int getItemCount() {
        return group.items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView image;
        CheckBox check;
        TextView badge;
        TextView name;
        TextView meta;

        VH(View v) {
            super(v);
            image = v.findViewById(R.id.ivThumb);
            check = v.findViewById(R.id.cbMark);
            badge = v.findViewById(R.id.tvThumbBadge);
            name = v.findViewById(R.id.tvThumbName);
            meta = v.findViewById(R.id.tvThumbMeta);
        }
    }
}
