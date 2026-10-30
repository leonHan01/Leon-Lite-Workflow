package com.leon.workflow.util;

import com.github.promeg.pinyinhelper.Pinyin;

public class PinyinUtil {
    public static String getPinyin(String s) {
        return Pinyin.toPinyin(s, "").toLowerCase();
    }
}
