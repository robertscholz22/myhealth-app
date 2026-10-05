#!/usr/bin/env python3
"""P20.3: convert an Android values/strings.xml into a Compose Multiplatform composeResources file.

aapt2 resolves quotes, escapes and whitespace at build time; the Compose resources plugin only
decodes \\n, \\t, \\uXXXX and \\\\. So every string is first resolved with aapt2's rules and then
written so that the Compose plugin yields exactly that text. `--keep` names strings that stay in the
Android file (manifest references); they are left out of the output.

Usage: strings_to_compose.py <android strings.xml> <compose strings.xml> [--keep name,name]
"""
import re
import sys
import xml.dom.minidom as dom


def aapt_text(raw: str) -> str:
    """aapt2's StringBuilder: quotes toggle verbatim mode, escapes, whitespace collapsing."""
    out = []
    quoted = False
    escape = False
    i = 0
    trailing_ws = False  # an unquoted whitespace run is pending
    while i < len(raw):
        c = raw[i]
        if escape:
            escape = False
            if trailing_ws and out:
                out.append(' ')
            trailing_ws = False
            if c == 't':
                out.append('\t')
            elif c == 'n':
                out.append('\n')
            elif c == 'u':
                out.append(chr(int(raw[i + 1:i + 5], 16)))
                i += 4
            else:
                out.append(c)
        elif c == '\\':
            escape = True
        elif c == '"':
            quoted = not quoted
            if quoted and trailing_ws and out:
                out.append(' ')
                trailing_ws = False
        elif not quoted and c in ' \t\n\r':
            trailing_ws = True
        else:
            if trailing_ws and out:
                out.append(' ')
            trailing_ws = False
            out.append(c)
        i += 1
    return ''.join(out)


def compose_text(text: str) -> str:
    """What to write so the Compose plugin's handleSpecialCharacters() returns [text]."""
    s = text.replace('\\', '\\\\').replace('\n', '\\n').replace('\t', '\\t')
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


def main():
    src, dst = sys.argv[1], sys.argv[2]
    keep = set()
    if '--keep' in sys.argv:
        keep = set(sys.argv[sys.argv.index('--keep') + 1].split(','))
    doc = dom.parse(src)
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
    count = 0
    for node in doc.documentElement.childNodes:
        if node.nodeType == node.COMMENT_NODE:
            lines.append('    <!--' + node.data + '-->')
        elif node.nodeType == node.ELEMENT_NODE:
            if node.tagName != 'string':
                sys.exit('unsupported element ' + node.tagName)
            name = node.getAttribute('name')
            if name in keep:
                continue
            raw = ''.join(ch.data if ch.nodeType in (ch.TEXT_NODE, ch.CDATA_SECTION_NODE) else sys.exit('markup in ' + name)
                          for ch in node.childNodes)
            lines.append('    <string name="%s">%s</string>' % (name, compose_text(aapt_text(raw))))
            count += 1
        elif node.nodeType == node.TEXT_NODE and node.data.count('\n') > 1:
            lines.append('')
    lines.append('</resources>')
    with open(dst, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    print('%d strings written to %s' % (count, dst))


if __name__ == '__main__':
    main()
