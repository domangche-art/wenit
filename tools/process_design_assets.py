"""design-assets/ 의 원본 이미지를 앱 리소스로 가공한다. 원본은 읽기만 한다.

사용: py tools/process_design_assets.py <미리보기 출력 폴더(프로젝트 밖)>
필요: Pillow, numpy, scipy

- 앱 아이콘: 원본의 바깥 여백과 둥근 모서리를 잘라 안쪽 그림만 쓴다.
  적응형 아이콘 전경(그림)·배경(하늘색 단색), 구형 ic_launcher / ic_launcher_round 를 해상도별로 만든다.
- 캐릭터: 바깥 가장자리에서부터 이어진 밝은 영역만 투명하게 지운다(갈색 외곽선 안쪽은 지우지 않는다).
  외곽선 바깥 2px 띠는 흰색과 섞인 색을 풀어(color-to-alpha) 흰 테두리가 남지 않게 한다.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "design-assets")
RES = os.path.join(ROOT, "app", "src", "main", "res")

ICON_SRC = os.path.join(ASSETS, "app_icon_source.png.png")
CHAR_SRC = os.path.join(ASSETS, "character_source.png.png")

# 원본 아이콘(2048px)의 안쪽 둥근 사각형: (98, 97)-(1949, 1948), 모서리 반지름 약 380px (가장자리 그림자로 측정).
# 모서리가 남지 않으려면 r * (1 - 1/sqrt(2)) = 약 111px 이상 더 안쪽을 잘라야 한다. 여유를 두어 120px.
ICON_CROP = (98 + 120, 97 + 120, 1949 - 120, 1948 - 120)

# 적응형 아이콘: 108dp 캔버스 중 그림이 차지할 크기(dp). 보이는 영역은 가운데 72dp, 안전 영역은 지름 66dp 원.
ADAPTIVE_ART_DP = 80
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

CHAR_LONG_SIDE = 512


def premultiplied_resize(im: Image.Image, size) -> Image.Image:
    """투명 가장자리에 색이 번지지 않게 premultiplied 상태로 줄인다."""
    return im.convert("RGBa").resize(size, Image.LANCZOS).convert("RGBA")


def sample_background(im: Image.Image) -> tuple:
    """안쪽 그림 위쪽 가운데의 하늘색 배경 평균."""
    a = np.asarray(im.convert("RGB"), dtype=np.float64)
    patch = a[260:330, 900:1150]
    return tuple(int(round(v)) for v in patch.reshape(-1, 3).mean(axis=0))


def make_icon(preview_dir: str) -> dict:
    src = Image.open(ICON_SRC).convert("RGBA")
    art = src.crop(ICON_CROP)
    bg = sample_background(src)
    hex_bg = "#{:02X}{:02X}{:02X}".format(*bg)

    for name, scale in DENSITIES.items():
        # 적응형 전경: 108dp 투명 캔버스 가운데에 그림 ADAPTIVE_ART_DP
        canvas = round(108 * scale)
        art_px = round(ADAPTIVE_ART_DP * scale)
        fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        off = (canvas - art_px) // 2
        fg.paste(premultiplied_resize(art, (art_px, art_px)), (off, off))
        out_dir = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(out_dir, exist_ok=True)
        fg.save(os.path.join(out_dir, "ic_launcher_foreground.png"), optimize=True)

        # 구형 아이콘(48dp). 런처가 모양을 자르지 않으므로 여기서 한 번만 둥글게 자른다.
        size = round(48 * scale)
        inner = round(44 * scale)  # 가장자리 2dp 여백(구형 아이콘 권장)
        small = premultiplied_resize(art, (inner, inner))
        for kind in ("square", "round"):
            mask = Image.new("L", (inner * 4, inner * 4), 0)
            d = ImageDraw.Draw(mask)
            if kind == "square":
                d.rounded_rectangle((0, 0, inner * 4 - 1, inner * 4 - 1), radius=round(inner * 4 * 0.18), fill=255)
            else:
                d.ellipse((0, 0, inner * 4 - 1, inner * 4 - 1), fill=255)
            mask = mask.resize((inner, inner), Image.LANCZOS)
            shaped = Image.new("RGBA", (inner, inner), (0, 0, 0, 0))
            shaped.paste(small, (0, 0), mask)
            legacy = Image.new("RGBA", (size, size), (0, 0, 0, 0))
            legacy.paste(shaped, ((size - inner) // 2, (size - inner) // 2))
            fname = "ic_launcher.png" if kind == "square" else "ic_launcher_round.png"
            legacy.save(os.path.join(out_dir, fname), optimize=True)

    # 안전 영역 미리보기(프로젝트 밖): 108dp 를 432px 로, 보이는 72dp 사각형과 지름 66dp 안전 원을 그린다.
    s = 4
    full = Image.new("RGBA", (108 * s, 108 * s), bg + (255,))
    art_px = ADAPTIVE_ART_DP * s
    off = (108 * s - art_px) // 2
    full.alpha_composite(premultiplied_resize(art, (art_px, art_px)), (off, off))
    guide = full.copy()
    d = ImageDraw.Draw(guide)
    c = 54 * s
    d.rectangle((c - 36 * s, c - 36 * s, c + 36 * s, c + 36 * s), outline=(255, 0, 0, 255), width=2)
    d.ellipse((c - 33 * s, c - 33 * s, c + 33 * s, c + 33 * s), outline=(0, 160, 0, 255), width=3)
    guide.save(os.path.join(preview_dir, "icon_safe_zone.png"))
    for shape in ("circle", "squircle", "square"):
        m = Image.new("L", (72 * s, 72 * s), 0)
        dm = ImageDraw.Draw(m)
        if shape == "circle":
            dm.ellipse((0, 0, 72 * s - 1, 72 * s - 1), fill=255)
        elif shape == "squircle":
            dm.rounded_rectangle((0, 0, 72 * s - 1, 72 * s - 1), radius=26 * s, fill=255)
        else:
            dm.rounded_rectangle((0, 0, 72 * s - 1, 72 * s - 1), radius=8 * s, fill=255)
        view = full.crop((c - 36 * s, c - 36 * s, c + 36 * s, c + 36 * s))
        out = Image.new("RGBA", view.size, (255, 255, 255, 0))
        out.paste(view, (0, 0), m)
        out.save(os.path.join(preview_dir, f"icon_mask_{shape}.png"))
    return {"background": hex_bg, "crop": ICON_CROP}


def make_character(preview_dir: str) -> dict:
    rgb = np.asarray(Image.open(CHAR_SRC).convert("RGB"), dtype=np.float64)
    h, w, _ = rgb.shape
    mn = rgb.min(axis=2)

    # 밝은 영역(흰 배경, 연한 그림자, 종이 질감). 몸 안쪽의 밝은 부분도 여기에 들지만, 바깥 가장자리와
    # 이어진 덩어리만 지우므로 갈색 외곽선 안쪽은 남는다.
    light = (mn >= 200) & (rgb[..., 0] >= 235)
    labels, _ = ndimage.label(light)
    border = np.unique(np.concatenate([labels[0], labels[-1], labels[:, 0], labels[:, -1]]))
    border = border[border != 0]
    outside = np.isin(labels, border)

    # 바깥에 떨어진 작은 얼룩은 지운다: 캐릭터는 가장 큰 덩어리 하나다.
    inside_labels, n = ndimage.label(~outside)
    if n > 1:
        sizes = ndimage.sum(np.ones_like(inside_labels), inside_labels, index=range(1, n + 1))
        keep = 1 + int(np.argmax(sizes))
        outside |= (inside_labels != keep) & (inside_labels != 0)

    # 외곽선 바깥쪽 2px 띠: 흰색과 섞인 색을 풀어 반투명으로(color-to-alpha). 외곽선 자체는 불투명하게 둔다.
    dist = ndimage.distance_transform_edt(~outside)
    band = (~outside) & (dist <= 2.0)
    a_white = ((255.0 - rgb) / 255.0).max(axis=2)
    alpha = np.ones((h, w))
    alpha[outside] = 0.0
    alpha[band] = np.clip(a_white[band] / 0.9, 0.0, 1.0)
    out = rgb.copy()
    safe = np.maximum(alpha, 1e-6)[..., None]
    unmixed = 255.0 - (255.0 - rgb) / safe
    out[band] = np.clip(unmixed[band], 0, 255)

    rgba = np.dstack([out, alpha * 255.0]).round().astype(np.uint8)
    im = Image.fromarray(rgba, "RGBA")
    bbox = im.getchannel("A").point(lambda v: 255 if v > 8 else 0).getbbox()
    pad = 12
    bbox = (max(0, bbox[0] - pad), max(0, bbox[1] - pad), min(w, bbox[2] + pad), min(h, bbox[3] + pad))
    im = im.crop(bbox)
    scale = CHAR_LONG_SIDE / max(im.size)
    size = (round(im.size[0] * scale), round(im.size[1] * scale))
    im = premultiplied_resize(im, size)

    out_dir = os.path.join(RES, "drawable-nodpi")
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, "wenit_character.webp")
    im.save(path, "WEBP", lossless=True, quality=100, method=6)

    # 미리보기(프로젝트 밖): 어두운·밝은·중간 배경, 그리고 발과 머리 가장자리 3배 확대.
    for name, color in (("dark", (18, 18, 18)), ("light", (242, 242, 242)), ("blue", (90, 179, 223))):
        bg = Image.new("RGBA", (im.size[0] + 64, im.size[1] + 64), color + (255,))
        bg.alpha_composite(im, (32, 32))
        bg.save(os.path.join(preview_dir, f"character_on_{name}.png"))
        zw, zh = im.size
        for part, box in (("feet", (0, int(zh * 0.72), zw, zh)), ("top", (int(zw * 0.2), 0, int(zw * 0.8), int(zh * 0.3)))):
            crop = bg.crop((box[0] + 32, box[1] + 32, box[2] + 32, box[3] + 32))
            crop.resize((crop.size[0] * 3, crop.size[1] * 3), Image.NEAREST).save(
                os.path.join(preview_dir, f"character_zoom_{part}_on_{name}.png"))
    removed = float(outside.mean())
    return {"size": im.size, "bbox": bbox, "removed_ratio": round(removed, 3), "path": path}


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: process_design_assets.py <preview_dir outside the project>")
    preview_dir = os.path.abspath(sys.argv[1])
    if preview_dir.startswith(ROOT):
        sys.exit("미리보기는 프로젝트 밖에 둔다")
    os.makedirs(preview_dir, exist_ok=True)
    print("icon", make_icon(preview_dir))
    print("character", make_character(preview_dir))


if __name__ == "__main__":
    main()
