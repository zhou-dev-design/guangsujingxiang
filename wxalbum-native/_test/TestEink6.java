import com.wxalbum.nativeapp.Ui;

/** 6 英寸 300dpi 墨水屏识别逻辑的纯 JVM 测试。 */
public class TestEink6 {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  PASS " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) {
        System.out.println("== 物理尺寸计算 ==");
        double d = Ui.diagInches(1072, 1448, 300, 300);
        System.out.println("  1072x1448 @300dpi -> " + String.format("%.3f", d) + " 英寸");
        check("目标设备约为 6.0 英寸", Math.abs(d - 6.0) < 0.1);
        check("xdpi 为 0 时不崩溃", Ui.diagInches(1072, 1448, 0, 300) == 0);

        System.out.println("== 设备判定 ==");
        check("用户的 1072x1448 / 300dpi / 6 英寸 -> 命中",
                Ui.isEink6Screen(1072, 1448, 300, 300, 300));
        check("略宽的同尺寸面板 1072x1448 / 320dpi -> 命中",
                Ui.isEink6Screen(1072, 1448, 320, 320, 320));
        check("普通高密手机 1080x2340 / 440dpi -> 不误判",
                !Ui.isEink6Screen(1080, 2340, 440, 440, 440));
        check("普通低密手机 720x1280 / 320dpi -> 尺寸不足，不误判",
                !Ui.isEink6Screen(720, 1280, 320, 320, 320));
        check("10 英寸平板 1200x1920 / 300dpi -> 过大，不误判",
                !Ui.isEink6Screen(1200, 1920, 300, 300, 300));
        check("低 dpi 大屏 1024x768 / 160dpi -> dpi 不足，不误判",
                !Ui.isEink6Screen(1024, 768, 160, 160, 160));

        System.out.println("\n结果: " + pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}
