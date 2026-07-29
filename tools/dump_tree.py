#!/usr/bin/env python3
"""Prints a uiautomator dump as an indented tree, to find an element worth a rule.

    adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
    python3 tools/dump_tree.py ui.xml

Each line shows the class, the view ID if any, the content description and text, the
bounds in pixels, and whether the node is clickable or scrollable. View IDs are the
stable part: prefer them as the anchor of a rule, and use descriptions and text only to
recognise a row while reading, never in a contributed rule, because both are translated.

Pass a maximum depth as a second argument to keep the output short while you look for the
list that holds the content.
"""
import sys
import xml.etree.ElementTree as ET

SHORTHAND = [
    ('android.widget.', 'w.'),
    ('android.view.', 'v.'),
    ('androidx.recyclerview.widget.', 'rv.'),
    ('androidx.viewpager.widget.', 'vp.'),
    ('androidx.drawerlayout.widget.', 'dl.'),
]


def short(class_name):
    for prefix, abbreviation in SHORTHAND:
        if class_name.startswith(prefix):
            return abbreviation + class_name[len(prefix):]
    return class_name


def walk(node, depth, max_depth):
    if depth > max_depth:
        return

    attrib = node.attrib
    parts = [short(attrib.get('class', ''))]

    view_id = attrib.get('resource-id', '')
    if view_id:
        parts.append('#' + view_id.split('/')[-1])
    if attrib.get('content-desc'):
        parts.append('desc=%r' % attrib['content-desc'][:60])
    if attrib.get('text'):
        parts.append('text=%r' % attrib['text'][:40])

    parts.append(attrib.get('bounds', ''))
    if attrib.get('clickable') == 'true':
        parts.append('CLICK')
    if attrib.get('scrollable') == 'true':
        parts.append('SCROLL')
    if attrib.get('selected') == 'true':
        parts.append('SELECTED')

    print('  ' * depth + ' '.join(parts))
    for child in node:
        walk(child, depth + 1, max_depth)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2

    max_depth = int(argv[2]) if len(argv) > 2 else 99
    for window in ET.parse(argv[1]).getroot():
        walk(window, 0, max_depth)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
