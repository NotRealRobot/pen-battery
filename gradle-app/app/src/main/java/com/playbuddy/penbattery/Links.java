package com.playbuddy.penbattery;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * "只有被特殊标注的词才能点"的统一实现。
 *
 * 用户要求：可点击的东西必须在视觉上标出来（下划线 + 主色），不要把整块面板
 * 做成一个看不见的大按钮。于是两件事：
 *   1) {@link #mark} 把链接词拼到文本尾部并加样式；
 *   2) {@link #bind} 只在抬手落在链接词上时触发动作 —— 面板其余区域的触摸仍被吞掉
 *      （遮罩不该让已经停用的组件复活），但也什么都不做。
 * 自己判定而不用 LinkMovementMethod：后者在非链接区是否吞事件，不同 ROM 行为不一致。
 */
public final class Links {

    /** 面板内一行状态：链接跟在文字后 */
    public static final String INLINE = "  ·  ";
    /** 遮罩中央：链接另起一行 */
    public static final String STACKED = "\n";

    private Links() { }

    /** "文字 · 带下划线的主色可点词"；label 可为空（只有链接词） */
    public static CharSequence mark(@NonNull View themed, @Nullable String label,
                                    @NonNull String link, @NonNull String sep) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (label != null && !label.isEmpty()) sb.append(label).append(sep);
        int s = sb.length();
        sb.append(link);
        final int color = BatteryFragment.color(themed, R.attr.colorPrimary);
        sb.setSpan(new ClickableSpan() {
            @Override public void onClick(@NonNull View w) { /* 由 bind() 精确派发 */ }
            @Override public void updateDrawState(@NonNull android.text.TextPaint ds) {
                ds.setColor(color);
                ds.setUnderlineText(true);            // ← 特殊标注
            }
        }, s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /** 纯文字，并解除可点行为 */
    public static void plain(@NonNull TextView tv, @NonNull String text) {
        tv.setText(text);
        tv.setClickable(false);
        tv.setOnTouchListener(null);
    }

    /** 绑定文本 + 精确点击。{@code onTap} 为 null 时只吞触摸、不响应 */
    public static void bind(@NonNull TextView tv, @Nullable String text, @NonNull String link,
                            @NonNull String sep, @Nullable Runnable onTap) {
        tv.setText(mark(tv, text, link, sep));
        tv.setClickable(false);
        tv.setOnTouchListener((host, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_UP && onTap != null) hit(tv, ev, onTap);
            return true;
        });
    }

    private static void hit(TextView tv, MotionEvent ev, Runnable onTap) {
        android.text.Layout l = tv.getLayout();
        CharSequence cs = tv.getText();
        if (l == null || !(cs instanceof Spanned)) return;
        int x = (int) ev.getX() - tv.getTotalPaddingLeft() + tv.getScrollX();
        int y = (int) ev.getY() - tv.getTotalPaddingTop() + tv.getScrollY();
        if (x < 0 || y < 0) return;
        int line = l.getLineForVertical(y);
        if (line < 0 || line >= l.getLineCount()) return;
        if (y < l.getLineTop(line) || y > l.getLineBottom(line)) return;
        // 水平边界要用 getLineLeft/Right —— 它们是排版后的绝对位置。
        // 之前用 getLineWidth（该行文字宽度）判，居中文本的行首不在视图左边，
        // 于是点在中部的链接词被自己挡掉了（用户实测"点了没反应"）。
        if (x < l.getLineLeft(line) || x > l.getLineRight(line)) return;
        int off = l.getOffsetForHorizontal(line, x);
        if (off < 0 || off >= ((Spanned) cs).length()) return;
        if (((Spanned) cs).getSpans(off, off, ClickableSpan.class).length > 0) onTap.run();
    }
}
