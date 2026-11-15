package com.workbuddy.wallpapergedupe.model;

import java.util.ArrayList;
import java.util.List;

/** 一个相似壁纸分组。keeper 只是「建议保留」，用户可以自由勾选任意一项。 */
public class DuplicateGroup {
    public final List<ImageEntry> items = new ArrayList<>();
    public ImageEntry keeper;
    public float avgScore;
    public int index;
}
