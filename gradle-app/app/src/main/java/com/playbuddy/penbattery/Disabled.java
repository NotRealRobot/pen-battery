package com.playbuddy.penbattery;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.textview.MaterialTextView;

/**
 * 给"当前设备用不了"的面板盖一层遮罩：半透明底 + 斜纹 + 提示文字，并吃掉触摸。
 *
 * ★ 为什么做成运行期加一个覆盖子 View，而不是每张卡各写一套布局：
 *   三个页面要盖 six 张卡，遮罩样式必须一处定义、处处一致（也方便你说"再淡一点"）。
 * ★ 为什么不 setForeground：MaterialCardView 自己会把 ripple 放在 foreground 上，盖掉它涟漪就没了
 *   —— 而面板涟漪是用户明确要求保留的。
 * ★ 只盖住、只挡交互，**不删任何数据**：切回支持的笔后记录和曲线照常继续。
 */
public final class Disabled {

    private static final Object MARKER = new Object();

    private Disabled() { }

    /**
     * 盖上遮罩（可重复调用刷新文案）。
     *
     * @param link {@code null} ⇒ 只有一行说明文字，面板整体不可点（触摸被吞掉，防止底下
     *             已停用的组件复活）；非 null ⇒ 文字下方多一行「带下划线的主色词」，
     *             且**只有点在那几个字上**才触发动作（用户要求：不要把整块面板当按钮）。
     */
    public static void apply(ViewGroup card, String label, @Nullable String link,
                             @Nullable Runnable onTap) {
        MaterialTextView v = find(card);
        if (v == null) {
            v = new MaterialTextView(card.getContext());
            v.setTag(MARKER);
            v.setTextAppearance(R.style.TextAppearance_Material3_BodyMedium);
            v.setGravity(Gravity.CENTER);
            v.setLineSpacing(dp(3), 1f);
            v.setPadding(dp(18), dp(18), dp(18), dp(18));
            v.setTextColor(BatteryFragment.color(card, R.attr.colorOnSurface));
            // MaterialCardView 是 FrameLayout；作为最后一个子 View 画在最上层
            card.addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (link == null || onTap == null) {
            v.setText(label);
            v.setClickable(false);
            v.setOnTouchListener((h, e) -> true);      // 只吞触摸，不响应：底下的停用组件不该复活
        } else {
            Links.bind(v, label, link, Links.STACKED, onTap);
        }
        // 颜色一律从主题取，再叠透明度 ⇒ 不新增配色令牌、自动跟随日夜与换色
        int base = BatteryFragment.color(card, R.attr.colorSurfaceContainerLow);
        int edge = BatteryFragment.color(card, R.attr.colorOutline);
        v.setBackground(new Hatch(
                ColorUtils.setAlphaComponent(base, 0xB3),      // 70% 不透明。阶梯 0xDE(87%)→0xB3(70%)，用户定"刚刚好"
                ColorUtils.setAlphaComponent(edge, 0x40),      // 25% 斜纹。阶梯 0x59(35%)→0x40(25%)
                dp(13), dp(3), dp(12)));
    }

    /** 揭开 */
    public static void clear(ViewGroup card) {
        MaterialTextView v = find(card);
        if (v != null) card.removeView(v);
    }

    @Nullable
    private static MaterialTextView find(ViewGroup card) {
        for (int i = 0; i < card.getChildCount(); i++) {
            View c = card.getChildAt(i);
            if (c.getTag() == MARKER && c instanceof MaterialTextView) return (MaterialTextView) c;
        }
        return null;
    }

    private static int dp(float v) {
        return (int) (v * Resources.getSystem().getDisplayMetrics().density + 0.5f);
    }

    /** 圆角矩形内铺一层半透明底 + 45° 斜纹 */
    private static final class Hatch extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int gap;
        private final float radius;

        Hatch(int fillColor, int lineColor, int gapPx, int strokePx, float radiusPx) {
            fill.setColor(fillColor);
            line.setColor(lineColor);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(strokePx);
            gap = gapPx;
            radius = radiusPx;
        }

        @Override public void draw(@NonNull Canvas c) {
            Rect b = getBounds();
            int w = b.width(), h = b.height();
            if (w <= 0 || h <= 0) return;

            Path clip = new Path();
            clip.addRoundRect(new RectF(b), radius, radius, Path.Direction.CW);
            int save = c.save();
            c.clipPath(clip);
            c.drawRect(b, fill);
            // 45° 平行斜纹：从左上往右下扫，多画一个 h 的量保证右下角不空
            for (int x = -h; x < w; x += gap) c.drawLine(x, h, x + h, 0, line);
            c.restore();
        }

        @Override public void setAlpha(int a) { fill.setAlpha(a); }
        @Override public void setColorFilter(@Nullable ColorFilter f) { line.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
