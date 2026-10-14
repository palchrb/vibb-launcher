#!/usr/bin/env python3
"""Turn the launcher's vector drawables into inline SVG strings for the design 34 mockups (glyphs.js)."""
import json, re, sys
R = sys.argv[1]  # launcher/app/src/main/res/drawable
OUT = sys.argv[2]
names = ['app_glyph_alarm', 'app_glyph_calendar_month', 'app_glyph_chat', 'app_glyph_mail', 'app_glyph_map',
         'app_glyph_photo_camera', 'app_glyph_photo_library', 'app_glyph_play_circle', 'app_glyph_public',
         'app_glyph_music_note', 'app_glyph_menu_book', 'app_glyph_star', 'app_glyph_school', 'app_glyph_calculate',
         'app_glyph_partly_cloudy_day', 'app_glyph_sports_esports',
         'ic_kid_phone_book', 'ic_kid_settings', 'ic_kid_phone', 'ic_kid_message', 'ic_lock_unlock',
         'ic_pin_backspace', 'ic_kid_back', 'ic_kid_check', 'ic_kid_missed_call']
out = {}
for n in names:
    x = open(f'{R}/{n}.xml').read()
    vw = re.search(r'viewportWidth="([\d.]+)"', x).group(1)
    vh = re.search(r'viewportHeight="([\d.]+)"', x).group(1)
    ty = re.search(r'translateY="([\d.]+)"', x)
    paths = []
    for p in re.finditer(r'<path(.*?)/>', x, re.S):
        a = dict(re.findall(r'android:(\w+)="([^"]*)"', p.group(1)))
        d = a.get('pathData', '')
        if 'strokeColor' in a:
            paths.append(f'<path d="{d}" fill="none" stroke="currentColor" stroke-width="{a.get("strokeWidth", "2")}" '
                         f'stroke-linecap="{a.get("strokeLineCap", "round")}" stroke-linejoin="{a.get("strokeLineJoin", "round")}"/>')
        else:
            paths.append(f'<path d="{d}" fill="currentColor"/>')
    body = ''.join(paths)
    if ty:
        body = f'<g transform="translate(0 {ty.group(1)})">{body}</g>'
    out[re.sub(r'^(app_glyph_|ic_)', '', n)] = f'<svg viewBox="0 0 {vw} {vh}" xmlns="http://www.w3.org/2000/svg">{body}</svg>'
with open(OUT, 'w') as f:
    f.write('// Generated from launcher/app/src/main/res/drawable by the design 34 mockup script. The app_glyph_* paths are\n'
            '// Material Symbols (Apache-2.0, assets/licenses/Apache-2.0.txt); the ic_* are the launcher\'s own.\n')
    f.write('const GLYPHS = ' + json.dumps(out, ensure_ascii=False, indent=0) + ';\n')
print(len(out))
