package com.zhihu.dongcha.document;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** ~500 字符重叠分块（重叠 100 字符），段首尽量在换行处对齐 */
@Component
public class TextChunker {

    public static final int SIZE = 500;
    public static final int OVERLAP = 100;

    /** 便捷重载：按默认 SIZE/OVERLAP 分块；当前摄取管线走下面的三参重载。*/
    public List<String> chunk(String text) {
        return chunk(text, SIZE, OVERLAP);
    }

    /** 核心重叠分块：归一换行后按 size 切、块尾尽量对齐换行，下一块起点回退 overlap 保证无空洞。*/
    public List<String> chunk(String text, int size, int overlap) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String t = text.replaceAll("\r\n", "\n").strip();
        if (t.isEmpty()) return out;
        int stride = size - overlap;
        int pos = 0;
        while (pos < t.length()) {
            int end = Math.min(pos + size, t.length());
            if (end < t.length()) {
                // 向后找换行对齐，找不到再向前找
                int nl = t.indexOf('\n', pos + size - 120);
                if (nl > 0 && nl < end && nl > pos + 100) end = nl;
                else {
                    int prev = t.lastIndexOf('\n', end);
                    if (prev > pos + 100) end = prev;
                }
            }
            String piece = t.substring(pos, end).strip();
            if (!piece.isEmpty()) out.add(piece);
            if (end >= t.length()) break;
            // 从块尾回退 overlap 前进，保证覆盖无空洞
            pos = Math.max(pos + 1, end - overlap);
        }
        return out;
    }
}
