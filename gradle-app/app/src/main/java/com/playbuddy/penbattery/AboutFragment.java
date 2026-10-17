package com.playbuddy.penbattery;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.textview.MaterialTextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 关于页：版本（读 PackageManager）/ 设备 / 数据来源 / 说明 / 复制诊断。 */
public class AboutFragment extends Fragment {

    private MaterialTextView tvFirst, tvDays;
    private LinearLayout llDevice;            // 设备区整块按当前目标重建

    private ViewGroup boxSource;
    private LinearLayout llSource;
    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c,
                             @Nullable Bundle s) {
        return inf.inflate(R.layout.fragment_about, c, false);
    }

    @Override public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        LinearLayout app = v.findViewById(R.id.cardApp);
        LinearLayout dev = v.findViewById(R.id.cardDevice);
        LinearLayout src = v.findViewById(R.id.cardSource);
        LinearLayout notes = v.findViewById(R.id.cardNotes);

        String[] ver = packageVersion();
        addRow(app, getString(R.string.spec_version), ver[0]);
        addRow(app, getString(R.string.spec_installed), ver[1]);

        boxSource = v.findViewById(R.id.boxSource);
        llDevice = dev;                       // 设备区整块由 renderTarget() 建
        llSource = src;                       // 数据来源整块由 renderSource() 建
        renderTarget();
        renderSource();

        addNote(notes, getString(R.string.note_why), getString(R.string.note_why_body));
        addNote(notes, getString(R.string.note_accuracy), getString(R.string.note_accuracy_body));

        v.findViewById(R.id.btnDiag).setOnClickListener(x -> copyDiag());
        v.findViewById(R.id.btnPickTarget).setOnClickListener(x ->
                ((MainActivity) requireActivity()).pickTarget(this::renderAll));
        refresh();
    }

    /** 换了目标 ⇒ 设备区、数据来源、遮罩、统计数字全都要重算一遍 */
    private void renderAll() {
        renderTarget();
        renderSource();
        refresh();
    }

    /**
     * 「数据来源」讲的是**当前这些数据从哪来**。一条记录都没有 ⇒ 留空：把
     * PN7522W 的服务/特征参数摆给一个什么都没读到的用户看，是误导而不是说明。
     * 有记录（哪怕是先前连着有效笔时留下的）⇒ 照常显示，遮罩只盖在上面，数字仍看得见。
     */
    private void renderSource() {
        if (llSource == null) return;
        llSource.removeAllViews();
        boolean hasData = Samples.count(requireContext()) > 0
                || History.countDays(requireContext()) > 0;
        addRow(llSource, getString(R.string.spec_service),
                hasData ? "0x180F Battery Service" : "—");
        addRow(llSource, getString(R.string.spec_char),
                hasData ? "0x2A19 Read + Notify" : "—");
        tvFirst = addRow(llSource, getString(R.string.spec_first), "—");
        tvDays = addRow(llSource, getString(R.string.spec_total), "—");
        if (State.shouldMask())
            Disabled.apply(boxSource, getString(R.string.disabled_source), null, null);
        else Disabled.clear(boxSource);
    }

    @Override public void onResume() { super.onResume(); refresh(); }

    /**
     * 设备区整块重建。目标设备现在是可换的 ⇒ 只属于 PN7522W 的那行硬件信息
     * （芯片型号是当初查这支笔查出来的，对别的笔不成立）必须跟着出现或消失。
     * 地址按用户 10-16 指示完整显示，不再打码。
     */
    private void renderTarget() {
        if (llDevice == null) return;
        llDevice.removeAllViews();
        String mac = PenTarget.address(requireContext());
        String name = PenTarget.name(requireContext());
        addRow(llDevice, getString(R.string.spec_device),
                name.isEmpty() ? getString(R.string.not_selected) : name);
        addRow(llDevice, getString(R.string.spec_mac),
                mac == null ? getString(R.string.not_selected) : mac);
        if (name.toUpperCase(Locale.ROOT).contains("PN7522")) {
            addRow(llDevice, getString(R.string.spec_chip), "Nordic Semiconductor (89)");
        }
    }

    private void refresh() {
        if (tvFirst == null) return;
        List<String> all = History.allDates(requireContext());
        tvFirst.setText(all.isEmpty() ? getString(R.string.none_yet) : all.get(0));
        tvDays.setText(getString(R.string.count_days, History.countDays(requireContext())));
    }

    /** 版本信息唯一来源 = 已安装的包（不写死常量） */
    private String[] packageVersion() {
        try {
            PackageInfo pi = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(),
                            android.content.pm.PackageManager.PackageInfoFlags.of(0));
            String when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(pi.lastUpdateTime));
            return new String[]{pi.versionName + " (" + pi.versionCode + ")", when};
        } catch (Exception e) {
            return new String[]{getString(R.string.version_unavailable), "—"};
        }
    }

    private MaterialTextView addRow(LinearLayout host, String label, String value) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pv = (int) (14 * getResources().getDisplayMetrics().density);
        row.setPadding(0, pv, 0, pv);

        MaterialTextView l = new MaterialTextView(requireContext());
        l.setText(label);
        l.setTextAppearance(R.style.TextAppearance_Material3_BodyMedium);
        l.setTextColor(BatteryFragment.color(host,
                R.attr.colorOnSurfaceVariant));
        row.addView(l, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        MaterialTextView v = new MaterialTextView(requireContext());
        v.setText(value);
        v.setTextAppearance(R.style.TextAppearance_Material3_BodyMedium);
        row.addView(v);

        if (host.getChildCount() > 0) {
            View div = new View(requireContext());
            div.setBackgroundColor(BatteryFragment.color(host,
                    R.attr.colorOutlineVariant));
            div.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
            host.addView(div);
        }
        host.addView(row);
        return v;
    }

    private void addNote(LinearLayout host, String title, String body) {
        LinearLayout l = new LinearLayout(requireContext());
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(0, 12, 0, 12);

        MaterialTextView t = new MaterialTextView(requireContext());
        t.setText(title);
        t.setTextAppearance(R.style.TextAppearance_Material3_TitleSmall);
        l.addView(t);

        MaterialTextView b = new MaterialTextView(requireContext());
        b.setText(body);
        b.setTextAppearance(R.style.TextAppearance_Material3_BodySmall);
        b.setTextColor(BatteryFragment.color(host,
                R.attr.colorOnSurfaceVariant));
        b.setPadding(0, 6, 0, 0);
        l.addView(b);

        if (host.getChildCount() > 0) {
            View div = new View(requireContext());
            div.setBackgroundColor(BatteryFragment.color(host,
                    R.attr.colorOutlineVariant));
            div.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
            host.addView(div);
        }
        host.addView(l);
    }

    private void copyDiag() {
        String[] ver = packageVersion();
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.diag_header)).append('\n');
        sb.append("version ").append(ver[0]).append("  installed ").append(ver[1]).append('\n');
        sb.append("Android ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("device ").append(Build.MANUFACTURER).append(' ')
           .append(Build.MODEL).append('\n');
        String t = PenTarget.address(requireContext());
        sb.append("pen ").append(PenTarget.name(requireContext())).append(" / ")
          .append(t == null ? getString(R.string.not_selected) : t).append("\n");
        sb.append("connected ").append(State.connected).append('\n');
        sb.append("level ").append(State.pct < 0 ? "--" : State.pct + "%").append('\n');
        sb.append("subscribed ").append(State.levelSub).append('\n');
        sb.append("lastUpdate ").append(State.lastTime).append(' ').append(State.source).append('\n');
        sb.append("status ").append(State.status).append('\n');
        List<String> all = History.allDates(requireContext());
        sb.append("firstRecord ").append(all.isEmpty() ? "none" : all.get(0)).append('\n');
        sb.append("recordedDays ").append(History.countDays(requireContext())).append('\n');

        ClipboardManager cm = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("PenBattery diagnostics", sb.toString()));
            ((MainActivity) requireActivity()).toast(getString(R.string.msg_copied));
        }
    }
}
