#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""yr's weather symbols for the lock's weather (design 75 §4.1): the darkmode set as Android VectorDrawables.

    python3 -I scripts/yr-symbols.py                  # download, check, convert, write everything below
    python3 -I scripts/yr-symbols.py --cache DIR      # the same, keeping the download in DIR (read from it next time)
    python3 -I scripts/yr-symbols.py --selftest       # convert a built-in SVG with every handled feature (no network)

Source: nrkno/yr-weather-symbols at COMMIT (package 14.0.0), through raw.githubusercontent.com:
symbols/darkmode/<id>.svg (one per id of src/index.ts's `weatherSymbolKeys`, MET's symbol_code -> yr's id),
src/index.ts, locales/en.json and locales/nb.json (the names by number), LICENSE (CC BY 4.0, "Yr weather symbols
(c) 2015 by Yr/NRK"). Every file's SHA-256 goes into a manifest ("<sha256>  <path>" lines, sorted by path) whose own
SHA-256 is pinned below (MANIFEST_SHA256): a changed upstream fails the run before anything is written.

The conversion (each change reported per file on stdout):
- the 100 x 100 viewBox -> <vector> with viewport 100 x 100 at 24 dp;
- <path>: d, fill (a colour or a gradient), fill-rule evenodd -> fillType evenOdd, opacity and fill-opacity ->
  fillAlpha; clip-rule is read only on a clip path's own path (anywhere else SVG ignores it);
- <linearGradient>/<radialGradient> in userSpaceOnUse with their stops (stop-opacity folded into the colour's alpha)
  -> aapt:attr gradients; a gradientTransform of translate, uniform scale or matrix(a 0 0 a e f) is applied to the
  coordinates, anything else (skew, rotation, non-uniform scale) fails;
- a clip path that is the whole viewBox (M0 0h100v100H0z) is dropped, any other one path -> <clip-path> at the start
  of a <group>; <g> with translate/scale/matrix -> a <group>;
- **changed**: a group with a `filter` (upstream's soft drop shadows) is dropped; a `mix-blend-mode:multiply` path
  whose d repeats an earlier solid path's exactly is baked into that path - each of its stops becomes
  fill x stop, laid at the stop's alpha times the layer's opacity over the fill - so yr's cloud shading stays; any
  other blend layer is dropped;
- anything else (mask, circle, text, use, an unknown attribute that would change the picture) fails the run.

Writes (run from anywhere; paths from the repository root):
  launcher/app/src/main/res/drawable/weather_<id>.xml           (one per symbol, saying what it was converted from)
  launcher/app/src/main/java/com/kidslauncher/mdm/weather/WeatherSymbols.kt   (symbol_code -> drawable and name)
  launcher/app/src/main/res/values/weather_symbols.xml          (weather_symbol_<nn>, English, from locales/en.json)
  launcher/app/src/main/res/values-nb/weather_symbols.xml       (the same in Norwegian, from locales/nb.json)
  launcher/app/src/test/resources/weather_symbol_keys.json      (the mapping as read, for the launcher's own test)
  launcher/app/src/main/assets/licenses/CC-BY-4.0.txt           (upstream's LICENSE)

Python 3, nothing else.
"""
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET

COMMIT = "34039b509167ba1701f1729df5cc95488813dd5f"
BASE = f"https://raw.githubusercontent.com/nrkno/yr-weather-symbols/{COMMIT}"
# SHA-256 of the manifest of every downloaded file (see the module doc).
MANIFEST_SHA256 = "26156cb640764c81b56fdbe684790710e664d3520ed38a703c2841082ff09a2c"

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LAUNCHER = os.path.join(ROOT, "launcher", "app", "src")
DRAWABLE = os.path.join(LAUNCHER, "main", "res", "drawable")
KOTLIN = os.path.join(LAUNCHER, "main", "java", "com", "kidslauncher", "mdm", "weather", "WeatherSymbols.kt")
VALUES = os.path.join(LAUNCHER, "main", "res", "values", "weather_symbols.xml")
VALUES_NB = os.path.join(LAUNCHER, "main", "res", "values-nb", "weather_symbols.xml")
KEYS_JSON = os.path.join(LAUNCHER, "test", "resources", "weather_symbol_keys.json")
LICENSE_OUT = os.path.join(LAUNCHER, "main", "assets", "licenses", "CC-BY-4.0.txt")

SVG = "{http://www.w3.org/2000/svg}"
WHOLE_VIEWBOX = "M0 0h100v100H0z"
CODE_RE = re.compile(r"^\s*([a-z_]+): '([0-9]{2}[dnm]?)',\s*$", re.M)


class ConvertError(Exception):
    pass


# ---- numbers, colours, transforms ------------------------------------------------------------------------------

def num(text, what):
    try:
        value = float(text)
    except (TypeError, ValueError):
        raise ConvertError(f"{what}: not a plain number ({text!r})")
    return value


def fmt(value):
    """A coordinate or offset: at most 4 decimals, no trailing zeros, never "-0"."""
    text = f"{value:.4f}".rstrip("0").rstrip(".")
    return "0" if text in ("-0", "") else text


def offset(text):
    if text is None:
        return 0.0
    text = text.strip()
    value = num(text[:-1], "stop offset") / 100 if text.endswith("%") else num(text, "stop offset")
    return min(1.0, max(0.0, value))


def colour(text, what):
    """'#rgb' or '#rrggbb' -> (r, g, b) in 0..255."""
    text = (text or "").strip()
    if re.fullmatch(r"#[0-9a-fA-F]{3}", text):
        return tuple(int(c * 2, 16) for c in text[1:])
    if re.fullmatch(r"#[0-9a-fA-F]{6}", text):
        return tuple(int(text[i:i + 2], 16) for i in (1, 3, 5))
    raise ConvertError(f"{what}: a colour this script doesn't read ({text!r})")


def argb(rgb, alpha):
    a = max(0, min(255, round(alpha * 255)))
    return "#%02X%02X%02X%02X" % (a, *(max(0, min(255, round(c))) for c in rgb))


def transform(text):
    """A transform list -> the matrix (a, b, c, d, e, f): x' = a x + c y + e, y' = b x + d y + f."""
    m = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    rest = (text or "").strip()
    while rest:
        found = re.match(r"([a-zA-Z]+)\s*\(([^)]*)\)[\s,]*", rest)
        if not found:
            raise ConvertError(f"a transform this script doesn't read ({text!r})")
        name, args = found.group(1), [num(v, "transform") for v in re.split(r"[\s,]+", found.group(2).strip()) if v]
        rest = rest[found.end():]
        if name == "translate" and len(args) in (1, 2):
            step = (1, 0, 0, 1, args[0], args[1] if len(args) == 2 else 0)
        elif name == "scale" and len(args) in (1, 2):
            step = (args[0], 0, 0, args[1] if len(args) == 2 else args[0], 0, 0)
        elif name == "matrix" and len(args) == 6:
            step = tuple(args)
        else:
            raise ConvertError(f"a transform this script doesn't take: {name}({found.group(2)})")
        a, b, c, d, e, f = m
        a2, b2, c2, d2, e2, f2 = step
        m = (a * a2 + c * b2, b * a2 + d * b2, a * c2 + c * d2, b * c2 + d * d2, a * e2 + c * f2 + e, b * e2 + d * f2 + f)
    return m


def uniform(m, what):
    """The matrix as (scale, dx, dy), or a failure for skew, rotation or non-uniform scale."""
    a, b, c, d, e, f = m
    if abs(b) > 1e-9 or abs(c) > 1e-9 or abs(a - d) > 1e-9:
        raise ConvertError(f"{what}: only translate, uniform scale or matrix(a 0 0 a e f) can be applied")
    return a, e, f


def style_of(element):
    """`style=""` -> {property: value}; only mix-blend-mode, opacity and fill are taken."""
    out = {}
    for part in (element.get("style") or "").split(";"):
        if not part.strip():
            continue
        if ":" not in part:
            raise ConvertError(f"a style this script doesn't read ({part!r})")
        key, value = (s.strip() for s in part.split(":", 1))
        if key not in ("mix-blend-mode", "opacity", "fill"):
            raise ConvertError(f"a style property this script doesn't take ({key})")
        out[key] = value
    return out


def local(tag):
    if not tag.startswith(SVG):
        raise ConvertError(f"an element outside SVG's namespace ({tag})")
    return tag[len(SVG):]


def check_attrs(element, allowed):
    for key in element.attrib:
        name = key.split("}")[-1] if key.startswith("{") else key
        if name not in allowed:
            raise ConvertError(f"<{local(element.tag)}> has an attribute this script doesn't take ({name})")


# ---- gradients -------------------------------------------------------------------------------------------------

class Gradient:
    """A gradient in path coordinates: kind, geometry and stops [(offset, (r, g, b), alpha)]."""

    def __init__(self, kind, geometry, stops):
        self.kind, self.geometry, self.stops = kind, geometry, stops

    def xml(self, indent):
        pad = " " * indent
        if self.kind == "linear":
            x1, y1, x2, y2 = self.geometry
            head = (f'<gradient android:type="linear" android:startX="{fmt(x1)}" android:startY="{fmt(y1)}" '
                    f'android:endX="{fmt(x2)}" android:endY="{fmt(y2)}">')
        else:
            cx, cy, r = self.geometry
            head = (f'<gradient android:type="radial" android:centerX="{fmt(cx)}" android:centerY="{fmt(cy)}" '
                    f'android:gradientRadius="{fmt(r)}">')
        lines = [f'{pad}<aapt:attr name="android:fillColor">', f"{pad}    {head}"]
        for at, rgb, alpha in self.stops:
            lines.append(f'{pad}        <item android:offset="{fmt(at)}" android:color="{argb(rgb, alpha)}"/>')
        lines += [f"{pad}    </gradient>", f"{pad}</aapt:attr>"]
        return lines


def read_gradient(element):
    kind = local(element.tag)
    common = {"id", "gradientUnits", "gradientTransform", "spreadMethod"}
    if kind == "linearGradient":
        check_attrs(element, common | {"x1", "y1", "x2", "y2"})
    else:
        check_attrs(element, common | {"cx", "cy", "r", "fx", "fy"})
    if element.get("gradientUnits") != "userSpaceOnUse":
        raise ConvertError(f"gradient {element.get('id')}: only gradientUnits=\"userSpaceOnUse\"")
    if element.get("spreadMethod", "pad") != "pad":
        raise ConvertError(f"gradient {element.get('id')}: only spreadMethod pad")
    scale, dx, dy = uniform(transform(element.get("gradientTransform")), f"gradient {element.get('id')}")
    at = lambda x, y: (scale * x + dx, scale * y + dy)  # noqa: E731
    if kind == "linearGradient":
        get = lambda k: num(element.get(k, "0"), f"gradient {element.get('id')} {k}")  # noqa: E731
        x1, y1 = at(get("x1"), get("y1"))
        x2, y2 = at(num(element.get("x2", "1"), "x2"), get("y2"))
        gradient = Gradient("linear", (x1, y1, x2, y2), [])
    else:
        cx, cy = num(element.get("cx", "0.5"), "cx"), num(element.get("cy", "0.5"), "cy")
        if num(element.get("fx", str(cx)), "fx") != cx or num(element.get("fy", str(cy)), "fy") != cy:
            raise ConvertError(f"gradient {element.get('id')}: a focal point off the centre")
        x, y = at(cx, cy)
        gradient = Gradient("radial", (x, y, scale * num(element.get("r", "0.5"), "r")), [])
    last = 0.0
    for stop in element:
        if local(stop.tag) != "stop":
            raise ConvertError(f"gradient {element.get('id')}: a child that isn't a stop")
        check_attrs(stop, {"offset", "stop-color", "stop-opacity"})
        last = max(last, offset(stop.get("offset")))  # SVG: an offset below the one before is the one before
        gradient.stops.append((last, colour(stop.get("stop-color", "#000"), "stop-color"),
                               num(stop.get("stop-opacity", "1"), "stop-opacity")))
    if not gradient.stops:
        raise ConvertError(f"gradient {element.get('id')}: no stops")
    return gradient


# ---- the conversion --------------------------------------------------------------------------------------------

class Path:
    def __init__(self, d, fill, alpha, even_odd):
        self.d, self.fill, self.alpha, self.even_odd = d, fill, alpha, even_odd

    def solid(self):
        return isinstance(self.fill, tuple) and self.alpha == 1.0

    def xml(self, indent):
        pad = " " * indent
        attrs = [f'android:pathData="{self.d}"']
        if isinstance(self.fill, tuple):
            attrs.append(f'android:fillColor="{argb(self.fill, 1.0)}"')
        if self.alpha != 1.0:
            attrs.append(f'android:fillAlpha="{fmt(self.alpha)}"')
        if self.even_odd:
            attrs.append('android:fillType="evenOdd"')
        if isinstance(self.fill, Gradient):
            return [f"{pad}<path {' '.join(attrs)}>"] + self.fill.xml(indent + 4) + [f"{pad}</path>"]
        return [f"{pad}<path {' '.join(attrs)}/>"]


class Group:
    def __init__(self, scale=(1.0, 1.0), translate=(0.0, 0.0), clip=None):
        self.scale, self.translate, self.clip, self.children = scale, translate, clip, []

    def xml(self, indent):
        pad = " " * indent
        attrs = []
        if self.scale != (1.0, 1.0):
            attrs += [f'android:scaleX="{fmt(self.scale[0])}"', f'android:scaleY="{fmt(self.scale[1])}"']
        if self.translate != (0.0, 0.0):
            attrs += [f'android:translateX="{fmt(self.translate[0])}"',
                      f'android:translateY="{fmt(self.translate[1])}"']
        lines = [f"{pad}<group{''.join(' ' + a for a in attrs)}>"]
        if self.clip is not None:
            d, even_odd = self.clip
            fill_type = ' android:fillType="evenOdd"' if even_odd else ""
            lines.append(f'{pad}    <clip-path android:pathData="{d}"{fill_type}/>')
        for child in self.children:
            lines += child.xml(indent + 4)
        return lines + [f"{pad}</group>"]


class Converter:
    """One SVG -> one VectorDrawable; `changes` says what was dropped or baked."""

    def __init__(self, name, text):
        self.name = name
        self.changes = {"shadow groups dropped": 0, "blend layers baked": 0, "blend layers dropped": 0,
                        "whole-viewBox clips dropped": 0}
        try:
            self.root = ET.fromstring(text)
        except ET.ParseError as err:
            raise ConvertError(f"{name}: not XML ({err})")
        self.gradients, self.clips, self.filters = {}, {}, set()

    def fail(self, why):
        raise ConvertError(f"{self.name}: {why}")

    def convert(self):
        root = self.root
        try:
            if local(root.tag) != "svg":
                self.fail("not an <svg>")
            check_attrs(root, {"width", "height", "fill", "viewBox"})
            if re.split(r"[\s,]+", (root.get("viewBox") or "").strip()) != ["0", "0", "100", "100"]:
                self.fail(f"a viewBox other than 0 0 100 100 ({root.get('viewBox')!r})")
            for defs in root.findall(SVG + "defs"):
                check_attrs(defs, set())
                for item in defs:
                    self.read_def(item)
            out = Group()
            self.walk(root, out, {"fill": root.get("fill", "#000"), "fill-rule": "nonzero", "fill-opacity": 1.0})
        except ConvertError as err:
            if str(err).startswith(self.name + ":"):
                raise
            raise ConvertError(f"{self.name}: {err}")
        return out.children

    def read_def(self, item):
        kind = local(item.tag)
        if kind in ("linearGradient", "radialGradient"):
            self.gradients[item.get("id")] = read_gradient(item)
        elif kind == "clipPath":
            check_attrs(item, {"id", "clipPathUnits"})
            if item.get("clipPathUnits", "userSpaceOnUse") != "userSpaceOnUse":
                self.fail("a clip path in objectBoundingBox units")
            paths = list(item)
            if len(paths) != 1 or local(paths[0].tag) != "path":
                self.fail(f"clip path {item.get('id')}: not exactly one path")
            check_attrs(paths[0], {"d", "fill", "clip-rule", "fill-rule"})
            self.clips[item.get("id")] = (paths[0].get("d", "").strip(), paths[0].get("clip-rule") == "evenodd")
        elif kind == "filter":
            # Only ever met on the groups that are dropped (a filter anywhere else fails there).
            self.filters.add(item.get("id"))
        else:
            self.fail(f"<{kind}> in <defs>")

    def ref(self, value, table, what):
        found = re.fullmatch(r"url\(#([^)]+)\)", (value or "").strip())
        if not found or found.group(1) not in table:
            self.fail(f"{what} {value!r} names nothing this script read")
        return table[found.group(1)]

    def walk(self, parent, out, inherited):
        for child in parent:
            kind = local(child.tag)
            if kind == "defs":
                continue
            if kind == "g":
                self.group(child, out, inherited)
            elif kind == "path":
                self.path(child, out, inherited)
            else:
                self.fail(f"<{kind}> isn't converted")

    def inherit(self, element, inherited):
        style = style_of(element)
        here = dict(inherited)
        if element.get("fill") is not None or "fill" in style:
            here["fill"] = style.get("fill", element.get("fill"))
        if element.get("fill-rule") is not None:
            here["fill-rule"] = element.get("fill-rule")
        if element.get("fill-opacity") is not None:
            here["fill-opacity"] = inherited["fill-opacity"] * num(element.get("fill-opacity"), "fill-opacity")
        return here, style

    def group(self, element, out, inherited):
        check_attrs(element, {"clip-path", "filter", "opacity", "style", "transform", "fill", "fill-rule",
                              "fill-opacity"})
        here, style = self.inherit(element, inherited)
        if element.get("filter") is not None:
            self.ref(element.get("filter"), {k: k for k in self.filters}, "filter")
            self.changes["shadow groups dropped"] += 1
            return
        if "mix-blend-mode" in style:
            self.changes["blend layers dropped"] += 1
            return
        opacity = num(style.get("opacity", element.get("opacity", "1")), "opacity")
        if opacity != 1.0:
            self.fail("a group with its own opacity (a VectorDrawable group has none)")
        target = out
        if element.get("transform") is not None:
            a, b, c, d, e, f = transform(element.get("transform"))
            if abs(b) > 1e-9 or abs(c) > 1e-9:
                self.fail("a group transform with rotation or skew")
            target = Group(scale=(a, d), translate=(e, f))
            out.children.append(target)
        if element.get("clip-path") is not None:
            d, even_odd = self.ref(element.get("clip-path"), self.clips, "clip-path")
            if d == WHOLE_VIEWBOX:
                self.changes["whole-viewBox clips dropped"] += 1
            else:
                clipped = Group(clip=(d, even_odd))
                target.children.append(clipped)
                target = clipped
        self.walk(element, target, here)

    def path(self, element, out, inherited):
        check_attrs(element, {"d", "fill", "fill-rule", "clip-rule", "opacity", "fill-opacity", "style"})
        if len(element):
            self.fail("a path with children")
        here, style = self.inherit(element, inherited)
        d = (element.get("d") or "").strip()
        if not d:
            self.fail("a path without d")
        alpha = num(style.get("opacity", element.get("opacity", "1")), "opacity") * here["fill-opacity"]
        fill_text = (here["fill"] or "").strip()
        if fill_text == "none":
            self.fail("a path without a fill (strokes aren't converted)")
        fill = self.ref(fill_text, self.gradients, "fill") if fill_text.startswith("url(") else \
            colour(fill_text, "fill")
        even_odd = here["fill-rule"] == "evenodd"
        blend = style.get("mix-blend-mode")
        if blend is None:
            out.children.append(Path(d, fill, alpha, even_odd))
            return
        base = next((p for p in reversed(out.children) if isinstance(p, Path) and p.d == d), None)
        if blend == "multiply" and base is not None and base.solid() and base.even_odd == even_odd:
            self.bake(base, fill, alpha)
            self.changes["blend layers baked"] += 1
        else:
            self.changes["blend layers dropped"] += 1

    @staticmethod
    def bake(base, layer, opacity):
        """`layer` multiplied over the solid `base` at `opacity`, as one fill: per stop F x (1 - k + s k), k = the
        stop's alpha times the layer's opacity, F the base colour and s the stop's (both in 0..1)."""
        mix = lambda s, k: tuple(f * (1 - k + (c / 255) * k) for f, c in zip(base.fill, s))  # noqa: E731
        if isinstance(layer, tuple):
            base.fill = mix(layer, opacity)
        else:
            base.fill = Gradient(layer.kind, layer.geometry,
                                 [(at, mix(rgb, a * opacity), 1.0) for at, rgb, a in layer.stops])


def drawable_xml(children, header):
    gradient = any(isinstance(n, Path) and isinstance(n.fill, Gradient) for n in walk_nodes(children))
    lines = ['<?xml version="1.0" encoding="utf-8"?>', header,
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"']
    if gradient:
        lines.append('    xmlns:aapt="http://schemas.android.com/aapt"')
    lines += ['    android:width="24dp"', '    android:height="24dp"', '    android:viewportWidth="100"',
              '    android:viewportHeight="100">']
    for child in children:
        lines += child.xml(4)
    return "\n".join(lines + ["</vector>", ""])


def walk_nodes(children):
    for child in children:
        yield child
        if isinstance(child, Group):
            yield from walk_nodes(child.children)


# ---- download and outputs --------------------------------------------------------------------------------------

def fetch(path, cache):
    local_path = os.path.join(cache, path)
    if not os.path.exists(local_path):
        os.makedirs(os.path.dirname(local_path), exist_ok=True)
        with urllib.request.urlopen(f"{BASE}/{path}", timeout=60) as response:
            data = response.read()
        with open(local_path + ".part", "wb") as out:
            out.write(data)
        os.replace(local_path + ".part", local_path)
    with open(local_path, "rb") as f:
        return f.read()


def read_keys(index_ts):
    keys = CODE_RE.findall(index_ts)
    if not keys or len({code for code, _ in keys}) != len(keys):
        raise ConvertError("src/index.ts: no weatherSymbolKeys, or a code twice")
    return keys


def android_text(text):
    text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    text = text.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    return "\\" + text if text[:1] in ("@", "?") else text


def capital(text):
    return text[:1].upper() + text[1:]


def names_xml(names, source):
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             f"<!-- yr's names for its weather symbols (design 75 §4.2), from nrkno/yr-weather-symbols {source} at "
             f"{COMMIT}, CC BY 4.0 (assets/licenses/CC-BY-4.0.txt); the first letter made a capital. Generated by "
             "scripts/yr-symbols.py - don't edit. -->",
             "<resources>"]
    for number in sorted(names):
        lines.append(f'    <string name="weather_symbol_{number}">{android_text(capital(names[number]))}</string>')
    return "\n".join(lines + ["</resources>", ""])


def kotlin(keys):
    drawables = "\n".join(f'        "{code}" -> R.drawable.weather_{yr}' for code, yr in keys)
    names = "\n".join(f'        "{code}" -> R.string.weather_symbol_{yr[:2]}' for code, yr in keys)
    codes = "\n".join(f'        "{code}",' for code, _ in keys)
    return f"""// Generated by scripts/yr-symbols.py from nrkno/yr-weather-symbols src/index.ts at {COMMIT} - don't edit.
// SPDX-License-Identifier: GPL-3.0-or-later
package com.kidslauncher.mdm.weather

import com.kidslauncher.mdm.R

/**
 * MET's `symbol_code` -> yr's darkmode symbol (`res/drawable/weather_<id>.xml`, (c) Yr/NRK, CC BY 4.0, converted) and
 * its name (`weather_symbol_<nn>`, design 75 §4.2). A code yr doesn't know has neither (the phone leaves the symbol out).
 */
object WeatherSymbols {{
    /** Every code, in upstream's order. */
    val CODES: List<String> = listOf(
{codes}
    )

    /** The symbol's drawable, or null for a code yr doesn't know. */
    fun drawable(code: String): Int? = when (code) {{
{drawables}
        else -> null
    }}

    /** The symbol's spoken name, or null for a code yr doesn't know. */
    fun name(code: String): Int? = when (code) {{
{names}
        else -> null
    }}
}}
"""


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as out:
        out.write(text)


def run(cache):
    files = {}
    for path in ("src/index.ts", "locales/en.json", "locales/nb.json", "LICENSE"):
        files[path] = fetch(path, cache)
    keys = read_keys(files["src/index.ts"].decode("utf-8"))
    for _, yr in keys:
        path = f"symbols/darkmode/{yr}.svg"
        files[path] = fetch(path, cache)
    manifest = "".join(f"{hashlib.sha256(files[p]).hexdigest()}  {p}\n" for p in sorted(files))
    got = hashlib.sha256(manifest.encode()).hexdigest()
    if got != MANIFEST_SHA256:
        sys.stderr.write(manifest)
        raise ConvertError(f"upstream changed: the manifest's SHA-256 is {got}, pinned {MANIFEST_SHA256}")

    en = json.loads(files["locales/en.json"])
    nb = json.loads(files["locales/nb.json"])
    numbers = {yr[:2] for _, yr in keys}
    if set(en) != set(nb) or not numbers <= set(en):
        raise ConvertError("locales: en and nb name different numbers, or a symbol has no name")

    totals = {}
    drawables = {}
    for code, yr in keys:
        if yr in drawables:
            continue
        converter = Converter(f"{yr}.svg", files[f"symbols/darkmode/{yr}.svg"].decode("utf-8"))
        children = converter.convert()
        header = (f"<!-- yr weather symbol {yr}: nrkno/yr-weather-symbols symbols/darkmode/{yr}.svg at {COMMIT}.\n"
                  "     (c) Yr/NRK, CC BY 4.0 (assets/licenses/CC-BY-4.0.txt), converted to a VectorDrawable; shadows "
                  "removed, blend layers merged.\n     Generated by scripts/yr-symbols.py - don't edit. -->")
        drawables[yr] = drawable_xml(children, header.replace("(c)", "©"))
        said = ", ".join(f"{n} {what}" for what, n in converter.changes.items() if n)
        print(f"{yr}: {said or 'converted as it is'}")
        for what, n in converter.changes.items():
            totals[what] = totals.get(what, 0) + n

    # Everything converted: now write (a failure above writes nothing).
    for old in os.listdir(DRAWABLE):
        if re.fullmatch(r"weather_[0-9]{2}[dnm]?\.xml", old) and old[8:-4] not in drawables:
            os.remove(os.path.join(DRAWABLE, old))
    for yr, text in drawables.items():
        write(os.path.join(DRAWABLE, f"weather_{yr}.xml"), text)
    write(KOTLIN, kotlin(keys))
    write(VALUES, names_xml({n: en[n] for n in numbers}, "locales/en.json"))
    write(VALUES_NB, names_xml({n: nb[n] for n in numbers}, "locales/nb.json"))
    write(KEYS_JSON, json.dumps({"source": "nrkno/yr-weather-symbols src/index.ts weatherSymbolKeys",
                                 "commit": COMMIT, "keys": dict(keys)}, indent=2, ensure_ascii=False) + "\n")
    write(LICENSE_OUT, files["LICENSE"].decode("utf-8"))
    print(f"{len(drawables)} symbols, {len(keys)} codes, {len(numbers)} names: "
          + ", ".join(f"{n} {what}" for what, n in totals.items()))


# ---- the selftest ----------------------------------------------------------------------------------------------

SELFTEST_SVG = """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" fill="none" viewBox="0 0 100 100">
  <g clip-path="url(#whole)">
    <path fill="#fff" fill-rule="evenodd" clip-rule="evenodd" d="M10 10h20v20H10z"/>
    <path fill="#808080" d="M40 10h20v20H40z"/>
    <path fill="url(#shade)" d="M40 10h20v20H40z" opacity=".5" style="mix-blend-mode:multiply"/>
    <path fill="url(#sun)" opacity=".6" d="M70 10h20v20H70z"/>
    <path fill="url(#lin)" fill-opacity=".5" d="M10 40h20v20H10z"/>
    <path fill="#00f" d="M1 1h2v2H1z" style="mix-blend-mode:multiply"/>
    <g filter="url(#shadow)" opacity=".3" style="mix-blend-mode:multiply"><path fill="#000" d="M0 0h1v1H0z"/></g>
    <g transform="translate(5 6) scale(2)" fill="#123456"><path d="M1 2h3v3H1z"/></g>
    <g clip-path="url(#corner)"><path fill="#ABCDEF" d="M0 90h100v10H0z"/></g>
  </g>
  <defs>
    <clipPath id="whole"><path fill="#fff" d="M0 0h100v100H0z"/></clipPath>
    <clipPath id="corner"><path fill="#fff" clip-rule="evenodd" d="M0 90h50v10H0z"/></clipPath>
    <linearGradient id="shade" x1="50" x2="50" y1="10" y2="30" gradientUnits="userSpaceOnUse">
      <stop stop-color="#fff" stop-opacity="0"/>
      <stop offset="1" stop-color="#000"/>
    </linearGradient>
    <linearGradient id="lin" x1="0" x2="1" y1="0" y2="0" gradientTransform="matrix(20 0 0 20 10 40)" gradientUnits="userSpaceOnUse">
      <stop stop-color="#FF0000"/>
      <stop offset="50%" stop-color="#0F0" stop-opacity=".5"/>
    </linearGradient>
    <radialGradient id="sun" cx="0" cy="0" r="1" gradientTransform="translate(80 20) scale(10)" gradientUnits="userSpaceOnUse">
      <stop offset=".5" stop-color="#FFD348"/>
      <stop offset=".25" stop-color="#F09900"/>
    </radialGradient>
    <filter id="shadow" width="1" height="1" x="0" y="0" color-interpolation-filters="sRGB" filterUnits="userSpaceOnUse">
      <feFlood flood-opacity="0" result="a"/>
    </filter>
  </defs>
</svg>"""

SELFTEST_EXPECT = """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="100"
    android:viewportHeight="100">
    <path android:pathData="M10 10h20v20H10z" android:fillColor="#FFFFFFFF" android:fillType="evenOdd"/>
    <path android:pathData="M40 10h20v20H40z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="50" android:startY="10" android:endX="50" android:endY="30">
                <item android:offset="0" android:color="#FF808080"/>
                <item android:offset="1" android:color="#FF404040"/>
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="M70 10h20v20H70z" android:fillAlpha="0.6">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="80" android:centerY="20" android:gradientRadius="10">
                <item android:offset="0.5" android:color="#FFFFD348"/>
                <item android:offset="0.5" android:color="#FFF09900"/>
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="M10 40h20v20H10z" android:fillAlpha="0.5">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="10" android:startY="40" android:endX="30" android:endY="40">
                <item android:offset="0" android:color="#FFFF0000"/>
                <item android:offset="0.5" android:color="#8000FF00"/>
            </gradient>
        </aapt:attr>
    </path>
    <group android:scaleX="2" android:scaleY="2" android:translateX="5" android:translateY="6">
        <path android:pathData="M1 2h3v3H1z" android:fillColor="#FF123456"/>
    </group>
    <group>
        <clip-path android:pathData="M0 90h50v10H0z" android:fillType="evenOdd"/>
        <path android:pathData="M0 90h100v10H0z" android:fillColor="#FFABCDEF"/>
    </group>
</vector>
"""

SELFTEST_FAILS = {
    "circle": '<circle cx="5" cy="5" r="2" fill="#fff"/>',
    "mask": '<g mask="url(#m)"><path fill="#fff" d="M0 0h1v1H0z"/></g>',
    "text": '<text x="1" y="1">a</text>',
    "use": '<use href="#p"/>',
    "a stroke": '<path stroke="#fff" d="M0 0h1v1H0z"/>',
    "a group's opacity": '<g opacity=".5"><path fill="#fff" d="M0 0h1v1H0z"/></g>',
    "a skewed gradient": '<path fill="url(#g)" d="M0 0h1v1H0z"/><defs><linearGradient id="g" '
                         'gradientTransform="matrix(1 0.5 0 1 0 0)" gradientUnits="userSpaceOnUse">'
                         '<stop stop-color="#fff"/></linearGradient></defs>',
    "a non-uniform scale": '<path fill="url(#g)" d="M0 0h1v1H0z"/><defs><radialGradient id="g" '
                           'gradientTransform="scale(2 3)" gradientUnits="userSpaceOnUse">'
                           '<stop stop-color="#fff"/></radialGradient></defs>',
    "objectBoundingBox": '<path fill="url(#g)" d="M0 0h1v1H0z"/><defs><linearGradient id="g">'
                         '<stop stop-color="#fff"/></linearGradient></defs>',
    "an unknown style": '<path fill="#fff" style="stroke-width:2" d="M0 0h1v1H0z"/>',
}


def selftest():
    converter = Converter("selftest.svg", SELFTEST_SVG)
    got = drawable_xml(converter.convert(), "<!-- selftest -->")
    expected = '<?xml version="1.0" encoding="utf-8"?>\n<!-- selftest -->\n' + SELFTEST_EXPECT
    if got != expected:
        sys.stderr.write(got)
        raise SystemExit("selftest: the conversion differs from the expected drawable (above)")
    ET.fromstring(got.split("\n", 1)[1])
    want = {"shadow groups dropped": 1, "blend layers baked": 1, "blend layers dropped": 1,
            "whole-viewBox clips dropped": 1}
    if converter.changes != want:
        raise SystemExit(f"selftest: changes {converter.changes}, expected {want}")
    for what, body in SELFTEST_FAILS.items():
        svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">{body}</svg>'
        try:
            Converter(what, svg).convert()
        except ConvertError:
            continue
        raise SystemExit(f"selftest: {what} was converted instead of failing")
    keys = read_keys("export const weatherSymbolKeys = {\n  clearsky_day: '01d',\n  cloudy: '04',\n};\n")
    if keys != [("clearsky_day", "01d"), ("cloudy", "04")]:
        raise SystemExit(f"selftest: read_keys gave {keys}")
    if android_text("it's <b> & @x") != "it\\'s &lt;b&gt; &amp; @x" or android_text("@x") != "\\@x":
        raise SystemExit("selftest: Android string escaping")
    print("yr-symbols selftest: ok")


def main(argv):
    if argv == ["--selftest"]:
        selftest()
        return 0
    if len(argv) == 2 and argv[0] == "--cache":
        os.makedirs(argv[1], exist_ok=True)
        run(argv[1])
        return 0
    if argv:
        sys.stderr.write(__doc__)
        return 2
    cache = tempfile.mkdtemp(prefix="yr-symbols-")
    try:
        run(cache)
    finally:
        shutil.rmtree(cache, ignore_errors=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except ConvertError as err:
        sys.exit(f"yr-symbols: {err}")
