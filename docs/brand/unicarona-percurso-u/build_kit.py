"""Generate the approved UniCarona identity and export through logo-design tools."""
from pathlib import Path
import copy
import importlib.util
import json
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
SKILL = Path('.local/brand-kit')
GREEN, MINT, INK, WHITE = '#087B5B', '#08DCA4', '#111111', '#FFFFFF'
for folder in ['masters', 'digital/svg', 'digital/png', 'web', 'presentation', 'qa']:
    (ROOT / folder).mkdir(parents=True, exist_ok=True)

ns = {'s': 'http://www.w3.org/2000/svg'}
ET.register_namespace('', ns['s'])
old = ET.parse(ROOT.parent / '2026-10-23-concepts/concept-a-lockup-v2.svg').getroot()
word_group = old.find('s:g', ns).findall('s:g', ns)[1]
word = ''.join(ET.tostring(el, encoding='unicode').replace(' xmlns="http://www.w3.org/2000/svg"', '') for el in word_group)

NORMAL = 'M32 24H72V136A56 56 0 0 0 184 136V96H224V136A96 96 0 0 1 32 136Z'
REVERSE = 'M33 24H71V136A57 57 0 0 0 185 136V96H223V136A95 95 0 0 1 33 136Z'
SMALL = 'M32 32H80V128A48 48 0 0 0 176 128V96H224V128A96 96 0 0 1 32 128Z'

def symbol(color, reverse=False, small=False):
    d = SMALL if small else REVERSE if reverse else NORMAL
    r, cy, cx = (24, 40, 200) if small else (19 if reverse else 20, 44, 204)
    return f'<g fill="{color}"><path d="{d}"/><circle cx="{cx}" cy="{cy}" r="{r}"/></g>'

def svg(body, width, height, label):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" width="{width}" height="{height}" role="img" aria-label="{label}"><title>{label}</title>{body}</svg>\n'

def layout(kind, mark_color, type_color, reverse=False):
    mark = symbol(mark_color, reverse)
    lettering = f'<g fill="{type_color}">{word}</g>'
    if kind == 'symbol':
        return svg(mark, 256, 256, 'UniCarona — símbolo Percurso U')
    if kind == 'horizontal':
        body = f'<g transform="translate(8 16) scale(.875)">{mark}</g><g transform="translate(290 82)">{lettering}</g>'
        return svg(body, 918, 256, 'UniCarona — assinatura horizontal')
    if kind == 'stacked':
        body = f'<g transform="translate(192 8)">{mark}</g><g transform="translate(24 300)">{lettering}</g>'
        return svg(body, 640, 432, 'UniCarona — assinatura vertical')
    return svg(f'<g transform="translate(24 20)">{lettering}</g>', 640, 128, 'UniCarona — nome em curvas')

for kind in ['symbol', 'horizontal', 'stacked', 'wordmark']:
    for name, mc, tc, rev in [('color', GREEN, INK, False), ('black', '#000000', '#000000', False), ('white', WHITE, WHITE, True), ('green', GREEN, GREEN, False), ('dark', MINT, WHITE, True)]:
        data = layout(kind, mc, tc, rev)
        (ROOT / f'digital/svg/unicarona-{kind}-{name}.svg').write_text(data, encoding='utf-8')
        if name == 'color':
            (ROOT / f'masters/unicarona-{kind}.svg').write_text(data, encoding='utf-8')

for name, col in [('green', GREEN), ('white', WHITE)]:
    (ROOT / f'masters/unicarona-symbol-small-{name}.svg').write_text(svg(symbol(col, small=True), 256, 256, 'UniCarona — símbolo para redução'), encoding='utf-8')

# Use the skill's exporter, with sharp/libvips as the local SVG renderer.
# This avoids viewport-dependent browser screenshots for production PNGs.
sys.path.insert(0, str(SKILL / 'scripts'))
import export_variants
def render_sharp(src, dst, width, height):
    js = "require('sharp')(process.argv[1]).resize(Number(process.argv[3]),Number(process.argv[4]),{fit:'fill'}).png().toFile(process.argv[2]).catch(e=>{console.error(e);process.exit(1)});"
    subprocess.run(['node', '-e', js, str(src), str(dst), str(width), str(height)], cwd=ROOT.parents[2], check=True, capture_output=True)
    return 'sharp'
export_variants.render_png.render = render_sharp
sys.argv = ['export_variants.py', str(ROOT/'masters/unicarona-symbol.svg'), '--out-dir', str(ROOT/'web'), '--name', 'unicarona', '--title', 'UniCarona', '--mono', GREEN, '--icon-bg', GREEN, '--icon-fg', WHITE, '--icon-scale', '.66', '--only', 'app-icon', '--web-icons', '--favicon-source', str(ROOT/'masters/unicarona-symbol-small-white.svg')]
export_variants.main()

# Favicon sits on a green tile so it works on both light and dark browser chrome.
favicon = svg(f'<rect width="256" height="256" rx="48" fill="{GREEN}"/><g transform="translate(19.2 19.2) scale(.85)">{symbol(WHITE, small=True)}</g>', 256, 256, 'UniCarona')
(ROOT/'web/favicon.svg').write_text(favicon, encoding='utf-8')
for size in [16,32,48]:
    render_sharp(ROOT/'web/favicon.svg', ROOT/f'web/favicon-{size}.png', size, size)
export_variants.render_png.write_ico([str(ROOT/f'web/favicon-{s}.png') for s in [16,32,48]], str(ROOT/'web/favicon.ico'))

manifest_path = ROOT/'web/site.webmanifest'
manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
manifest.update(name='UniCarona', short_name='UniCarona', lang='pt-BR', description='Caronas universitárias avulsas e mensais.')
for icon in manifest['icons']:
    icon['src'] = icon['src'].lstrip('/')
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
(ROOT/'web/head-snippet.html').write_text('''<!-- Copie web/ para /assets/brand/ antes de usar este trecho. -->
<link rel="icon" href="/assets/brand/favicon.ico" sizes="16x16 32x32 48x48">
<link rel="icon" href="/assets/brand/favicon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="/assets/brand/apple-touch-icon.png">
<link rel="manifest" href="/assets/brand/site.webmanifest">
<meta name="theme-color" content="#087B5B">
''', encoding='utf-8')

spec = {
 'brand':'UniCarona', 'date':'23/10/2026', 'deck_title':'Identidade final · Percurso U', 'label':'Direção A · escolhida', 'final':True, 'greyscale':False,
 'tagline':'Sua turma. Seu trajeto.', 'brief':'Caronas universitárias avulsas e mensais: estudantes que combinam o caminho de ida e volta à UNICEPLAC.',
 'adjectives':['Próxima','Clara','Organizada','Contemporânea'], 'criteria':'Uma silhueta simples, reconhecível no celular e independente de símbolos institucionais.',
 'industry':'consumer-app', 'mockups':['app-icon','website','social','favicon-tab','sticker','tshirt'], 'nav':['Buscar','Mensal','Entrar'], 'brand_color':GREEN,
 'concepts':[{'name':'Percurso U', 'symbol':'../digital/svg/unicarona-symbol-color.svg', 'lockup':'../digital/svg/unicarona-horizontal-color.svg', 'stacked':'../digital/svg/unicarona-stacked-color.svg', 'symbol_on_tile':'../digital/svg/unicarona-symbol-white.svg', 'stacked_on_tile':'../digital/svg/unicarona-stacked-white.svg', 'symbol_on_dark':'../digital/svg/unicarona-symbol-dark.svg', 'lockup_on_dark':'../digital/svg/unicarona-horizontal-dark.svg', 'idea':'Um ponto de encontro no U do seu percurso.', 'rationale':['O U preserva uma referência da identidade anterior.','O ponto separado sugere encontro sem desenhar um pin.','Curvas abertas e espessuras firmes resistem à redução.','Verde no claro; menta e branco no escuro.']}],
 'recommendation':'A direção escolhida está pronta em SVG e PNG, com versões de cor, monocromáticas e para redução.',
 'next_steps':['Usar o guia de aplicação para escolher cada arquivo.','Aplicações são simulações visuais; não indicam lançamento de aplicativo ou perfis sociais.','O kit inclui os arquivos para futura integração no site.']
}
(ROOT/'presentation/spec.json').write_text(json.dumps(spec, ensure_ascii=False, indent=2), encoding='utf-8')

palette={'green':GREEN,'mint':MINT,'ink':INK,'white':WHITE}
def luminance(h):
    vals=[int(h[i:i+2],16)/255 for i in [1,3,5]]
    vals=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in vals]
    return sum(v*w for v,w in zip(vals,[.2126,.7152,.0722]))
ratios={}
for key, fg, bg in [('verde/branco',GREEN,WHITE),('menta/preto',MINT,INK),('branco/preto',WHITE,INK),('menta/branco',MINT,WHITE)]:
    a,b=sorted([luminance(fg),luminance(bg)])
    ratios[key]=round((b+.05)/(a+.05),2)
(ROOT/'qa/contrast.json').write_text(json.dumps(ratios,ensure_ascii=False,indent=2),encoding='utf-8')
print('Kit sources, exports and contrast:',ratios)
