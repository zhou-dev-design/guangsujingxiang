# -*- coding: utf-8 -*-
"""生成「公众号小说下载器」图标 app_icon.ico（书本 + 下载箭头）。

用法：python make_icon.py
依赖 Pillow。输出为同目录下的 app_icon.ico，含 16~256 共 7 种尺寸。
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageChops, ImageFilter

S = 1024
TOP = (58, 108, 255)      # 亮蓝
BOTTOM = (109, 60, 242)   # 紫罗兰
PAGE = (255, 255, 255)
INK = (150, 172, 222)
RIBBON = (255, 92, 108)
BADGE = (255, 255, 255)
ARROW = (74, 58, 232)
OUT = Path(__file__).with_name("app_icon.ico")


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def rounded_mask(radius=216, pad=40):
    m = Image.new("L", (S, S), 0)
    ImageDraw.Draw(m).rounded_rectangle([pad, pad, S - pad, S - pad],
                                       radius=radius, fill=255)
    return m


def master():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 背景渐变
    for y in range(S):
        d.line([(0, y), (S, y)], fill=mix(TOP, BOTTOM, y / S) + (255,))

    # 柔光（渐变叠加，不用硬边高光，避免看起来像折角）
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([-320, -460, 600, 280], fill=(255, 255, 255, 62))
    glow = glow.filter(ImageFilter.GaussianBlur(150))
    img = Image.alpha_composite(img, glow)
    d = ImageDraw.Draw(img)

    # 书本投影
    d.polygon([(196, 388), (512, 456), (512, 768), (196, 700)], fill=(18, 24, 78, 90))
    d.polygon([(512, 456), (840, 388), (840, 700), (512, 768)], fill=(18, 24, 78, 90))

    # 打开的书：两页
    left = [(190, 356), (512, 426), (512, 744), (190, 674)]
    right = [(512, 426), (834, 356), (834, 674), (512, 744)]
    d.polygon(left, fill=PAGE)
    d.polygon(right, fill=(243, 247, 255))
    d.line([(190, 356), (512, 426), (834, 356)], fill=(255, 255, 255, 255), width=14)
    d.line([(190, 674), (512, 744), (834, 674)], fill=(206, 220, 250), width=10)
    d.line([(512, 426), (512, 744)], fill=(206, 220, 250), width=12)

    # 页面文字线（透视方向略倾斜）
    for i in range(4):
        y = 462 + i * 58
        d.line([(238, y - 22), (446, y + 18)], fill=INK + (255,), width=15)
        d.line([(578, y + 18), (786, y - 22)], fill=INK + (255,), width=15)

    # 书签缎带
    d.polygon([(480, 404), (544, 404), (544, 812), (512, 776), (480, 812)],
              fill=RIBBON)
    d.polygon([(480, 404), (512, 404), (512, 776), (480, 812)],
              fill=(225, 62, 84))

    # 右下角下载徽标
    d.ellipse([652, 652, 972, 972], fill=(24, 30, 92, 70))
    d.ellipse([640, 640, 960, 960], fill=BADGE)
    ax, ay = 800, 800
    d.line([(ax, 712), (ax, 856)], fill=ARROW, width=30)
    d.line([(728, 790), (ax, 858), (872, 790)], fill=ARROW, width=30, joint="curve")
    d.line([(722, 878), (878, 878)], fill=ARROW, width=28)

    # 圆角裁切（所有绘制都限制在圆角方块内）
    alpha = ImageChops.multiply(img.getchannel("A"), rounded_mask())
    img.putalpha(alpha)
    return img


def main():
    big = master()
    sizes = [16, 24, 32, 48, 64, 128, 256]
    frames = [big.resize((s, s), Image.LANCZOS).convert("RGBA") for s in sizes]
    frames[-1].save(OUT, format="ICO", sizes=[(s, s) for s in sizes])
    # 顺便存一张预览，方便肉眼检查
    big.resize((256, 256), Image.LANCZOS).save(OUT.with_suffix(".png"))
    print("已生成：", OUT)
    print("尺寸：", ", ".join(f"{s}x{s}" for s in sizes))


if __name__ == "__main__":
    main()
