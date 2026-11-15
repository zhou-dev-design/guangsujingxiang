package com.wxalbum.nativeapp;

import java.io.File;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 安全写盘：新版本先写临时文件 → 自检 → 原子替换。
 *
 * 规则（与桌面端约定一致）：
 *  - 生成/校验出问题：原文件分毫不动，等于自动保留旧版本；
 *  - 替换成功：旧版本不保留，不留 _history 之类的历史副本。
 *
 * 不依赖 Android，便于在普通 JVM 上直接测试。
 */
public final class SafeWrite {

    private SafeWrite() {
    }

    /** 打包结果自检：体积、mimetype、content.opf、章节数，任一不过就当"生成出问题"。 */
    public static void verify(File f, int expect) throws Exception {
        if (!f.exists() || f.length() < 512) {
            throw new Exception("新版本文件异常（" + (f.exists() ? f.length() + " 字节" : "没写出来")
                    + "），旧版本已保留");
        }
        int chap = 0;
        try (ZipFile z = new ZipFile(f)) {
            if (z.getEntry("mimetype") == null) {
                throw new Exception("新版本不是有效 EPUB（缺 mimetype），旧版本已保留");
            }
            if (z.getEntry("OEBPS/content.opf") == null) {
                throw new Exception("新版本不完整（缺 content.opf），旧版本已保留");
            }
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                if (en.nextElement().getName().startsWith("OEBPS/Text/chap")) chap++;
            }
        } catch (ZipException ze) {
            throw new Exception("新版本压缩包损坏（无法解压），旧版本已保留");
        }
        if (chap == 0) throw new Exception("新版本不含任何章节，旧版本已保留");
        if (chap < expect) {
            throw new Exception("新版本章节数不足（" + chap + "/" + expect + "），旧版本已保留");
        }
    }

    /** 临时文件已校验，替换目标文件；替换失败回滚，旧版本原样留下。成功则不保留旧版。 */
    public static void replace(File stage, File out) throws Exception {
        if (!out.exists()) {
            if (!stage.renameTo(out)) throw new Exception("写入失败：" + out.getName());
            return;
        }
        File bak = new File(out.getAbsoluteFile().getParentFile(), out.getName() + ".old");
        if (bak.exists() && !bak.delete()) throw new Exception("旧文件残留无法清理：" + bak.getName());
        if (!out.renameTo(bak)) {
            throw new Exception("原文件被占用，无法替换（旧版本已保留）：" + out.getName());
        }
        if (!stage.renameTo(out)) {
            boolean back = bak.renameTo(out);
            throw new Exception(back ? "替换失败，已回滚到旧版本"
                    : "替换失败且回滚失败，旧版本存于 " + bak.getName());
        }
        bak.delete(); // 新版本已就位，旧版本不再保留
    }

    /** 收拾上次被中断的替换：有 .old 但原文件没了就还原，残留临时文件直接删。返回还原本数。 */
    public static int recover(File dir) {
        int fixed = 0;
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs == null) return 0;
        for (File f : fs) {
            String n = f.getName();
            if (n.endsWith(".epub.old")) {
                File base = new File(dir, n.substring(0, n.length() - 4));
                if (!base.exists()) {
                    if (f.renameTo(base)) fixed++;
                } else {
                    f.delete();
                }
            } else if (n.endsWith(".part")) {
                f.delete();
            }
        }
        return fixed;
    }
}
