"""Rebuild the UniCarona concept drawings; no font dependency in artwork."""
from pathlib import Path
import sys

OUT = Path(__file__).resolve().parent
VERSION = sys.argv[1] if len(sys.argv) > 1 else 'v1'

def svg(body, title, width=256):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} 256" width="{width}" height="256" role="img" aria-labelledby="title"><title id="title">{title}</title><g fill="#111111">{body}</g></svg>\n'

# Each mark is a filled outline; all circular joins use actual SVG arcs.
if VERSION == 'v1':
    a = '<path d="M32 32H80V136A48 48 0 0 0 176 136V96H224V136A96 96 0 0 1 32 136Z"/><circle cx="200" cy="40" r="24"/>'
    b = '<path d="M168 32H80A56 56 0 0 0 80 144H112V104H80A16 16 0 0 1 80 72H168Z"/><path d="M88 224H176A56 56 0 0 0 176 112H144V152H176A16 16 0 0 1 176 184H88Z"/>'
    c = '<path d="M24 24H64V136A64 64 0 0 0 192 136V24H232V136A104 104 0 0 1 24 136Z"/><path d="M168 40H144A56 56 0 0 0 144 152H168V128H144A32 32 0 0 1 144 64H168Z"/>'
else:
    # A: reduce width to 40, enlarge separation above the right-hand route.
    # B: enlarge the inner counters from 32 to 40 and soften the terminal mass.
    # C: shorter U stems optically balance the lower bowl; widen the C opening.
    a = '<path d="M32 24H72V136A56 56 0 0 0 184 136V96H224V136A96 96 0 0 1 32 136Z"/><circle cx="204" cy="44" r="20"/>'
    b = '<path d="M168 32H84A60 60 0 0 0 84 152H112V112H84A20 20 0 0 1 84 72H168Z"/><path d="M88 224H172A60 60 0 0 0 172 104H144V144H172A20 20 0 0 1 172 184H88Z"/>'
    c = '<path d="M24 32H64V128A64 64 0 0 0 192 128V32H232V128A104 104 0 0 1 24 128Z"/><path d="M168 40H144A56 56 0 0 0 144 152H168V128H144A32 32 0 0 1 144 64H168Z"/>'

# Original geometric lettering, drawn as outlines rather than font glyphs.
letters = {
 'U': (68, 'M0 0H16V54C16 67 22 74 34 74C46 74 52 67 52 54V0H68V54C68 77 56 90 34 90C12 90 0 77 0 54Z'),
 'n': (60, 'M0 24H15V32C21 24 29 22 37 22C52 22 60 32 60 49V88H44V50C44 41 40 36 32 36C22 36 16 43 16 53V88H0Z'),
 'i': (16, 'M0 24H16V88H0ZM0 0H16V15H0Z'),
 'C': (72, 'M72 14L61 25C55 18 49 14 38 14C24 14 16 25 16 44C16 63 24 76 38 76C49 76 56 71 62 64L73 75C63 86 52 91 38 91C14 91 0 72 0 44C0 17 15 -1 38 -1C52 -1 63 4 72 14Z'),
 'a': (60, 'M0 56C0 35 11 22 28 22C36 22 42 25 45 30V24H60V88H45V82C40 88 35 90 27 90C10 90 0 77 0 56ZM16 56C16 69 21 76 30 76C39 76 45 69 45 56C45 43 39 36 30 36C21 36 16 43 16 56Z'),
 'r': (43, 'M0 24H15V34C21 25 28 22 39 23H43V39H37C22 39 16 47 16 61V88H0Z'),
 'o': (62, 'M0 56C0 35 12 22 31 22C50 22 62 35 62 56C62 77 50 90 31 90C12 90 0 77 0 56ZM16 56C16 69 21 76 31 76C41 76 46 69 46 56C46 43 41 36 31 36C21 36 16 43 16 56Z')
}
word = []
x = 0
for index, ch in enumerate('UniCarona'):
    width, d = letters[ch]
    word.append(f'<path transform="translate({x} 0)" fill-rule="evenodd" d="{d}"/>')
    x += width + (14 if ch == 'i' else 11)
word = ''.join(word)
for key, body, title in [('a', a, 'Percurso U'), ('b', b, 'Entre nós'), ('c', c, 'UC — Campus')]:
    (OUT / f'concept-{key}-{VERSION}.svg').write_text(svg(body, f'UniCarona — {title}'), encoding='utf-8')
    lock = f'<g transform="translate(8 16) scale(.875)">{body}</g><g transform="translate(290 82)">{word}</g>'
    (OUT / f'concept-{key}-lockup-{VERSION}.svg').write_text(svg(lock, f'UniCarona — {title}, assinatura horizontal', 950), encoding='utf-8')
print(f'Wrote three symbols and three outlined lockups ({VERSION}).')
