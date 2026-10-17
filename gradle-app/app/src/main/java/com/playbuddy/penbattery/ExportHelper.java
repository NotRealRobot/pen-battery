package com.playbuddy.penbattery;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.OutputStream;
import java.time.LocalDate;

/** 导出：系统文件选择器（SAF），应用不申请任何存储权限。 */
public final class ExportHelper {

    private ExportHelper() { }

    public static Intent buildIntent() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "pen-battery-history-" + LocalDate.now() + ".csv");
        return i;
    }

    /** 把 CSV 写进用户选中的位置；返回给用户看的提示文本 */
    public static String write(Context c, Uri uri) {
        try (OutputStream os = c.getContentResolver().openOutputStream(uri)) {
            if (os == null) return c.getString(R.string.export_fail_write);
            os.write(History.csv(c).getBytes("UTF-8"));
            os.flush();
            return c.getString(R.string.export_ok, uri.getLastPathSegment());
        } catch (Exception e) {
            return c.getString(R.string.export_fail_err, e.getMessage());
        }
    }
}
