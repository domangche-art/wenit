"""design-assets/ 의 원본 이미지를 앱 리소스로 가공한다. 원본은 읽기만 한다.

사용: py tools/process_design_assets.py <미리보기 출력 폴더(프로젝트 밖)>
필요: Pillow, numpy, scipy

- 앱 아이콘: 원본의 바깥 여백과 둥근 모서리를 잘라 안쪽 그림만 쓴다.
  적응형 아이콘 전경(그림)·배경(하늘색 단색), 구형 ic_launcher / ic_launcher_round 를 해상도별로 만든다.
- 캐릭터: 바깥 가장자리에서부터 이어진 밝은 영역만 투명하게 지운다(갈색 외곽선 안쪽은 지우지 않는다).
  외곽선 바깥 2px 띠는 흰색과 섞인 색을 풀어(color-to-alpha) 흰 테두리가 남지 않게 한다.
- 배지(13단계): 원형 동전만 잘라 바깥을 투명하게 하고 512px WebP(badge_common.webp)로 만든다. 자세한 방법은 make_badge.

두 번째 인자로 하나만 만들 수 있다(icon / character / badge). 생략하면 모두 만든다.
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


def badge_source() -> str:
    """파일 이름이 badge_test.png 또는 badge_test.png.png 일 수 있다."""
    for name in ("badge_test.png", "badge_test.png.png"):
        p = os.path.join(ASSETS, name)
        if os.path.exists(p):
            return p
    sys.exit("design-assets/badge_test.png 없음")


def fit_circle(points: np.ndarray) -> tuple:
    """최소제곱 원 맞춤(Kasa). points: (N, 2) x, y"""
    x, y = points[:, 0], points[:, 1]
    a = np.c_[2 * x, 2 * y, np.ones(len(x))]
    b = x ** 2 + y ** 2
    cx, cy, c = np.linalg.lstsq(a, b, rcond=None)[0]
    return cx, cy, float(np.sqrt(c + cx ** 2 + cy ** 2))


def make_badge(preview_dir: str) -> dict:
    """
    배지 시험 그림: 원형 동전만 남기고 바깥(크림색 배경, 그림자, 오른쪽 아래 반짝이)은 투명하게 한다.

    1. 채도가 높은 픽셀(금색·주황, 채도 > 0.4)의 가장 큰 덩어리 = 동전. 배경(채도 약 0.11)과 그림자(약 0.27)는 빠진다.
    2. 360개 각도에서 동전 바깥 끝을 찾아 원을 맞춘다(각도마다 1~2px 다른 가장자리를 평균).
    3. 원까지의 거리로 알파를 만든다(1px 폭 선형 경사) → 계단 없이 부드러운 가장자리.
       바깥 갈색 테두리 선은 남기고, 그 바깥 배경이 섞이지 않게 맞춘 반지름보다 0.75px 안쪽을 경계로 쓴다.
    4. 원에 맞춰 정사각형으로 잘라 premultiplied 상태로 512px 로 줄인다.
    """
    src = Image.open(badge_source()).convert("RGBA")
    a = np.asarray(src.convert("RGB"), dtype=np.float64)
    mx, mn = a.max(2), a.min(2)
    sat = (mx - mn) / np.maximum(mx, 1)
    lab, n = ndimage.label(sat > 0.4)
    sizes = ndimage.sum(np.ones_like(sat), lab, range(1, n + 1))
    coin = ndimage.binary_fill_holes(lab == (int(np.argmax(sizes)) + 1))
    ys, xs = np.nonzero(coin)
    cx0, cy0 = xs.mean(), ys.mean()
    r0 = float(np.sqrt(coin.sum() / np.pi))

    edge = []
    for t in np.deg2rad(np.arange(0, 360, 1.0)):
        last = None
        for d in np.arange(r0 - 20, r0 + 20, 0.5):
            x, y = int(round(cx0 + d * np.cos(t))), int(round(cy0 + d * np.sin(t)))
            if coin[y, x]:
                last = (cx0 + d * np.cos(t), cy0 + d * np.sin(t))
        if last:
            edge.append(last)
    cx, cy, r = fit_circle(np.array(edge))
    resid = np.hypot(np.array(edge)[:, 0] - cx, np.array(edge)[:, 1] - cy) - r

    edge_r = r - 0.75
    h, w = coin.shape
    yy, xx = np.mgrid[0:h, 0:w]
    dist = np.hypot(xx + 0.5 - cx, yy + 0.5 - cy)
    alpha = np.clip(edge_r - dist + 0.5, 0, 1)
    rgba = np.asarray(src, dtype=np.float64).copy()
    rgba[..., 3] = alpha * 255
    im = Image.fromarray(rgba.round().astype(np.uint8), "RGBA")
    half = int(np.ceil(edge_r)) + 2
    box = (int(round(cx)) - half, int(round(cy)) - half, int(round(cx)) + half, int(round(cy)) + half)
    im = premultiplied_resize(im.crop(box), (BADGE_SIZE, BADGE_SIZE))

    out_dir = os.path.join(RES, "drawable-nodpi")
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, "badge_common.webp")
    im.save(path, "WEBP", lossless=True, quality=100, method=6)

    for name, color in (("dark", (18, 18, 18, 255)), ("light", (242, 242, 242, 255)), ("cream", (245, 237, 218, 255))):
        bg = Image.new("RGBA", (BADGE_SIZE + 64, BADGE_SIZE + 64), color)
        bg.alpha_composite(im, (32, 32))
        bg.save(os.path.join(preview_dir, f"badge_on_{name}.png"))
        edge_crop = bg.crop((32 + BADGE_SIZE - 120, 32 + BADGE_SIZE // 2 - 60, 32 + BADGE_SIZE + 8, 32 + BADGE_SIZE // 2 + 60))
        edge_crop.resize((edge_crop.size[0] * 4, edge_crop.size[1] * 4), Image.NEAREST).save(
            os.path.join(preview_dir, f"badge_edge_zoom_on_{name}.png"))
    return {
        "source": os.path.basename(badge_source()), "source_size": src.size,
        "circle": (round(cx, 1), round(cy, 1), round(r, 1)), "edge_residual_px": (round(resid.min(), 2), round(resid.max(), 2)),
        "size": im.size, "bytes": os.path.getsize(path), "path": path
    }


BADGE_SIZE = 512


def main():
    if len(sys.argv) not in (2, 3):
        sys.exit("usage: process_design_assets.py <preview_dir outside the project> [icon|character|badge|all]")
    preview_dir = os.path.abspath(sys.argv[1])
    if preview_dir.startswith(ROOT):
        sys.exit("미리보기는 프로젝트 밖에 둔다")
    os.makedirs(preview_dir, exist_ok=True)
    which = sys.argv[2] if len(sys.argv) == 3 else "all"
    if which in ("icon", "all"):
        print("icon", make_icon(preview_dir))
    if which in ("character", "all"):
        print("character", make_character(preview_dir))
    if which in ("badge", "all"):
        print("badge", make_badge(preview_dir))


if __name__ == "__main__":
    main()
