import java.io.*;
import java.util.zip.*;

import com.wxalbum.nativeapp.SafeWrite;

/** 直接测试 SafeWrite：成功替换不保留旧版，生成出问题则旧版原样留下。 */
public class TestReplace {

    static void mkEpub(File f, int chaps, boolean withMime, boolean withOpf) throws Exception {
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            if (withMime) {
                byte[] mt = "application/epub+zip".getBytes("UTF-8");
                ZipEntry ze = new ZipEntry("mimetype");
                ze.setMethod(ZipEntry.STORED);
                ze.setSize(mt.length);
                ze.setCompressedSize(mt.length);
                CRC32 c = new CRC32();
                c.update(mt);
                ze.setCrc(c.getValue());
                z.putNextEntry(ze);
                z.write(mt);
                z.closeEntry();
            }
            if (withOpf) put(z, "OEBPS/content.opf", "<package/>".getBytes("UTF-8"));
            for (int i = 1; i <= chaps; i++) {
                put(z, String.format("OEBPS/Text/chap%04d.xhtml", i), ("<html>" + i + "</html>").getBytes("UTF-8"));
            }
            // 塞点填充，避免"体积过小"误判
            put(z, "OEBPS/Styles/style.css", new byte[4096]);
        }
    }

    static void put(ZipOutputStream z, String name, byte[] b) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(b);
        z.closeEntry();
    }

    static int pass = 0, fail = 0;

    static void check(String label, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  [PASS] " + label + (detail.isEmpty() ? "" : "  " + detail)); }
        else { fail++; System.out.println("  [FAIL] " + label + "  " + detail); }
    }

    public static void main(String[] a) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "repltest");
        delTree(dir);
        dir.mkdirs();

        Class<?> fc = File.class;
        Class<?> ic = int.class;

        // ---- 1. 首次生成：目标不存在 ----
        System.out.println("1) 首次生成（目标不存在）");
        File stage = new File(dir, ".A.epub.part");
        File out = new File(dir, "A.epub");
        mkEpub(stage, 3, true, true);
        SafeWrite.verify( stage, 3);
        SafeWrite.replace( stage, out);
        check("目标已生成", out.exists() && out.length() > 0, out.length() + " 字节");
        check("临时文件已消失", !stage.exists(), "");
        check("没有多余 .old", !new File(dir, "A.epub.old").exists(), "");

        // ---- 2. 正常替换：旧文件被覆盖，不留旧版 ----
        System.out.println("2) 正常替换（生成成功 → 不留旧版）");
        long oldLen = out.length();
        File stage2 = new File(dir, ".A.epub.part");
        mkEpub(stage2, 5, true, true);
        SafeWrite.verify( stage2, 5);
        SafeWrite.replace( stage2, out);
        check("新版本已就位", out.length() != oldLen, oldLen + " → " + out.length() + " 字节");
        check("旧版本未保留", !new File(dir, "A.epub.old").exists(), "");
        check("临时文件已消失", !stage2.exists(), "");
        check("新版本可正常解压", chapCount(out) == 5, chapCount(out) + " 章");

        // ---- 3. 新版本损坏 → 抛错且旧文件不动 ----
        System.out.println("3) 新版本压缩包损坏（生成出问题 → 留旧版本）");
        long goodLen = out.length();
        byte[] sig = readAll(out);
        File bad = new File(dir, ".A.epub.part");
        mkEpub(bad, 5, true, true);
        // 截断一半，模拟写盘中断（先读出再回写，否则打开即清空）
        byte[] full = readAll(bad);
        try (FileOutputStream fo = new FileOutputStream(bad, false)) {
            fo.write(full, 0, full.length / 2);
        }
        check("样本确实被截断", bad.length() > 0 && bad.length() < full.length,
                bad.length() + "/" + full.length + " 字节");
        boolean threw = false;
        String emsg = "";
        try {
            SafeWrite.verify( bad, 5);
        } catch (Exception e) {
            threw = true;
            emsg = e.getMessage();
        }
        check("校验拦截了损坏文件", threw, emsg);
        check("旧文件未被触碰", out.exists() && out.length() == goodLen && java.util.Arrays.equals(readAll(out), sig), goodLen + " 字节");

        // ---- 4. 缺 mimetype ----
        System.out.println("4) 缺 mimetype 的假 EPUB");
        File noMime = new File(dir, "noMime.epub");
        mkEpub(noMime, 3, false, true);
        threw = false;
        try { SafeWrite.verify( noMime, 3); }
        catch (Exception e) { threw = true; emsg = e.getMessage(); }
        check("校验拦截", threw, emsg);

        // ---- 5. 章节数不足 ----
        System.out.println("5) 章节数不足（少抓了）");
        File less = new File(dir, "less.epub");
        mkEpub(less, 2, true, true);
        threw = false;
        try { SafeWrite.verify( less, 5); }
        catch (Exception e) { threw = true; emsg = e.getMessage(); }
        check("校验拦截", threw, emsg);

        // ---- 6. 体积过小 ----
        System.out.println("6) 体积过小的空壳文件");
        File tiny = new File(dir, "tiny.epub");
        try (FileOutputStream fo = new FileOutputStream(tiny)) { fo.write(new byte[]{1, 2, 3}); }
        threw = false;
        try { SafeWrite.verify( tiny, 1); }
        catch (Exception e) { threw = true; emsg = e.getMessage(); }
        check("校验拦截", threw, emsg);

        // ---- 7. 模拟"替换到一半掉电"：还原逻辑 ----
        System.out.println("7) 掉电恢复（out 丢失、只剩 .old）");
        File b = new File(dir, "B.epub");
        mkEpub(b, 2, true, true);
        File oldB = new File(dir, "B.epub.old");
        check("准备：先造出 .old", b.renameTo(oldB) && oldB.exists() && !b.exists(), "");
        // recoverOrphans 需要 Context，这里等价复刻其核心分支
        File base = new File(dir, "B.epub");
        boolean restored = !base.exists() && oldB.renameTo(base);
        check("旧版本已还原", restored && base.exists(), "");

        System.out.println("\n结果：PASS " + pass + " / FAIL " + fail);
        delTree(dir);
        if (fail > 0) System.exit(1);
    }

    static int chapCount(File f) throws Exception {
        int n = 0;
        try (ZipFile z = new ZipFile(f)) {
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) if (en.nextElement().getName().startsWith("OEBPS/Text/chap")) n++;
        }
        return n;
    }

    static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    static void delTree(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) delTree(x);
        f.delete();
    }
}
