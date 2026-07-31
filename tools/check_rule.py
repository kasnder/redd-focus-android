#!/usr/bin/env python3
"""Answers whether a rule covers a screen, without building or installing the app.

    adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
    python3 tools/check_rule.py ui.xml --density 3.0 \\
        --rule 'com.example.app##viewId=com.example.app:id/list##childPath=android.view.ViewGroup[*]##hasThumbnail=true'

It reports which screen markers held, which elements the rule selected, and which of those
the thumbnail check kept. Run it over dumps of several screens of the same app to check
that a rule covers the one it was written for and leaves the others alone, which is the
mistake `requiresViewId` and `requiresSelected` exist to prevent.

Get the density with `adb shell wm density`, divided by 160. It only matters for
`hasThumbnail`, whose width is in dp while a dump is in pixels.

Two divergences from the running service are worth knowing:

- Rules that identify an element only by `desc`, `text` or `className` are not simulated.
  Those are discouraged in contributed rules anyway, because they are translated.
- A dump has no reliable equivalent of `isVisibleToUser`, so a node is treated as visible
  unless the dump says otherwise. The service additionally skips nodes scrolled off screen.

Mirrors BaseDistractionControlService and FilterRuleParser. If a rule behaves differently
on the device, trust the device.
"""
import argparse
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

_REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
_DISTRACTIONLIB_JAVA = _REPO_ROOT / 'distractionlib' / 'src' / 'main' / 'java' / 'net' / 'kollnig' / 'distractionlib'


def _read_java_int_constant(java_file, constant_name, default):
    """Reads a `private/public static final int NAME = value;` out of a Java source file.

    Read straight from source rather than duplicating the number, so the two can't drift
    apart silently. Falls back to `default` if the file has moved or the constant was renamed,
    which only makes this tool stale, not wrong about what the app actually does.
    """
    try:
        text = java_file.read_text()
    except OSError:
        return default
    match = re.search(r'\b%s\s*=\s*(\d+)\s*;' % re.escape(constant_name), text)
    return int(match.group(1)) if match else default


DESCRIPTION_MATCH_MODES = ('exact', 'prefix', 'substring')
DEFAULT_MIN_THUMBNAIL_WIDTH_DP = _read_java_int_constant(
    _DISTRACTIONLIB_JAVA / 'FilterRuleParser.java', 'DEFAULT_MIN_THUMBNAIL_WIDTH_DP', 100)
MIN_ACCEPTED_THUMBNAIL_WIDTH_DP = _read_java_int_constant(
    _DISTRACTIONLIB_JAVA / 'FilterRuleParser.java', 'MIN_ACCEPTED_THUMBNAIL_WIDTH_DP', 48)
MAX_THUMBNAIL_ASPECT_RATIO = _read_java_int_constant(
    _DISTRACTIONLIB_JAVA / 'BaseDistractionControlService.java', 'MAX_THUMBNAIL_ASPECT_RATIO', 6)

IMAGE_SUFFIXES = ('ImageView', 'SurfaceView', 'TextureView')


def parse_rule(line):
    """Reads one rule line into a dict, following FilterRuleParser."""
    parts = line.split('##')
    if len(parts) < 2:
        raise ValueError('a rule needs a package and at least one key: ' + line)

    rule = {'package': parts[0].strip(), 'minThumbnailWidthDp': 0, 'malformedScreenMarker': False,
            'malformedDescriptionMatch': False}
    for part in parts[1:]:
        if '=' not in part:
            continue
        key, _, value = part.partition('=')
        key, value = key.strip(), value.strip()

        if key == 'hasThumbnail':
            rule['minThumbnailWidthDp'] = parse_thumbnail_width(value)
        elif key == 'requiresSelected':
            anchor, sep, path = value.partition('>')
            if sep:
                anchor, path = anchor.strip(), path.strip()
                if anchor and path:
                    rule['selectedViewId'], rule['selectedChildPath'] = anchor, path
                else:
                    rule['malformedScreenMarker'] = True
            elif value:
                rule['selectedViewId'], rule['selectedChildPath'] = value, None
            else:
                rule['malformedScreenMarker'] = True
        elif key == 'descMatch':
            if value.lower() in DESCRIPTION_MATCH_MODES:
                rule[key] = value.lower()
            else:
                rule['malformedDescriptionMatch'] = True
        elif key == 'requiresViewId':
            if value:
                rule['requiredViewId'] = value
            else:
                rule['malformedScreenMarker'] = True
        else:
            rule[key] = value
    return rule


def parse_thumbnail_width(value):
    """Only an explicit false turns the check off; an unreadable width falls back."""
    if value.lower() == 'true':
        return DEFAULT_MIN_THUMBNAIL_WIDTH_DP
    if value.lower() == 'false':
        return 0
    try:
        width = int(value[:-2].strip() if value.endswith('dp') else value)
    except ValueError:
        return DEFAULT_MIN_THUMBNAIL_WIDTH_DP
    return width if width >= MIN_ACCEPTED_THUMBNAIL_WIDTH_DP else DEFAULT_MIN_THUMBNAIL_WIDTH_DP


def bounds(node):
    left, top, right, bottom = map(int, re.findall(r'-?\d+', node.attrib.get('bounds', '0000')))
    return left, top, right, bottom


def visible(node):
    return node.attrib.get('visible-to-user', 'true') != 'false'


def by_view_id(root, view_id):
    found = []

    def walk(node):
        if node.attrib.get('resource-id') == view_id:
            found.append(node)
        for child in node:
            walk(child)

    walk(root)
    return found


def match_paths(root, path):
    """Walks a `>`-separated path of class[index] segments, where * takes every child."""
    current = [root]
    for segment in path.split('>'):
        if '[' in segment:
            class_name = segment[:segment.index('[')]
            index_text = segment[segment.index('[') + 1:segment.index(']')]
            wildcard = index_text == '*'
            if wildcard:
                index = 0
            else:
                try:
                    index = int(index_text)
                except ValueError:
                    return []
        else:
            class_name, wildcard, index = segment, False, 0

        following = []
        for node in current:
            same_class = [c for c in node if c.attrib.get('class') == class_name]
            if wildcard:
                following.extend(same_class)
            elif len(same_class) > index:
                following.append(same_class[index])
        current = following
        if not current:
            return []
    return current


def has_thumbnail(node, min_width_px):
    """Recognises a media card by the width of the image it is built around."""
    if min_width_px <= 0:
        return True

    class_name = node.attrib.get('class', '')
    if class_name.endswith(IMAGE_SUFFIXES):
        left, top, right, bottom = bounds(node)
        width, height = right - left, bottom - top
        if width >= min_width_px and height * MAX_THUMBNAIL_ASPECT_RATIO >= width:
            return True
    return any(has_thumbnail(child, min_width_px) for child in node)


def matches_screen(rule, root, report):
    """Checks the rule's screen markers, as BaseDistractionControlService.matchesScreen does."""
    required = rule.get('requiredViewId')
    if required:
        present = any(visible(n) for n in by_view_id(root, required))
        report('  requiresViewId %s -> %s' % (required, 'present' if present else 'ABSENT'))
        if not present:
            return False

    anchor_id = rule.get('selectedViewId')
    if not anchor_id:
        return True

    child_path = rule.get('selectedChildPath')
    selected = False
    for anchor in by_view_id(root, anchor_id):
        if not visible(anchor):
            continue
        targets = match_paths(anchor, child_path) if child_path else [anchor]
        selected |= any(t.attrib.get('selected') == 'true' for t in targets)
    report('  requiresSelected %s%s -> %s' % (
        anchor_id, '>' + child_path if child_path else '',
        'selected' if selected else 'NOT SELECTED'))
    return selected


def select_targets(rule, root):
    """Picks the elements the rule points at, following the order in applyRule."""
    view_id = rule.get('viewId')
    child_path = rule.get('childPath')

    if view_id and child_path:
        targets = []
        for anchor in by_view_id(root, view_id):
            if visible(anchor):
                targets.extend(t for t in match_paths(anchor, child_path) if visible(t))
        return targets, 'viewId + childPath'

    if rule.get('path'):
        return match_paths(root, rule['path']), 'path'

    if view_id:
        return [n for n in by_view_id(root, view_id) if visible(n)], 'viewId'

    return None, None


def label(node):
    attrib = node.attrib
    text = attrib.get('content-desc') or attrib.get('text') or ''
    return '%s %s %s' % (attrib.get('class', ''), attrib.get('bounds', ''), repr(text)[:60])


def check(dump_path, rule, density, window_index):
    windows = list(ET.parse(dump_path).getroot())
    root = windows[window_index]

    print('%s:' % dump_path)
    if rule['malformedDescriptionMatch']:
        # Mirrors FilterRuleParser: an unreadable comparison mode would silently fall back to
        # exact matching, which is a different rule from the one that was written.
        print('  DROPPED: malformed descMatch value, rule never loads')
        return 0
    if rule['malformedScreenMarker']:
        # Mirrors FilterRuleParser: a screen marker that cannot be read is a broken guardrail,
        # so the app drops the whole rule at load time rather than applying it without it.
        print('  DROPPED: malformed requiresViewId/requiresSelected value, rule never loads')
        return 0
    if not matches_screen(rule, root, print):
        print('  NOT APPLIED: screen markers did not hold')
        return 0

    targets, how = select_targets(rule, root)
    if targets is None:
        print('  SKIPPED: this tool only simulates viewId, path and childPath rules')
        return 0

    min_width_px = round(rule['minThumbnailWidthDp'] * density)
    print('  matched %d element(s) by %s' % (len(targets), how))

    covered = 0
    for target in targets:
        kept = has_thumbnail(target, min_width_px)
        covered += kept
        print('    %-6s %s' % ('COVER' if kept else 'keep', label(target)))

    if min_width_px:
        print('  hasThumbnail >= %ddp (%dpx) left %d of %d covered'
              % (rule['minThumbnailWidthDp'], min_width_px, covered, len(targets)))
    return covered


def main(argv=None):
    parser = argparse.ArgumentParser(
        description='Check a rule against uiautomator dumps.',
        epilog='See docs/CUSTOM_RULES.md for the rule format.')
    parser.add_argument('dumps', nargs='+', help='uiautomator XML dumps, one per screen')
    parser.add_argument('--rule', required=True, help='a rule line, as written in the app')
    parser.add_argument('--density', type=float, default=1.0,
                        help='screen density, from `adb shell wm density` / 160. '
                             'Only affects hasThumbnail.')
    parser.add_argument('--window', type=int, default=0,
                        help='index of the window in the dump (default 0)')
    args = parser.parse_args(argv)

    rule = parse_rule(args.rule)
    if rule['minThumbnailWidthDp'] and args.density == 1.0:
        print('warning: hasThumbnail is in dp but no --density was given, '
              'so widths are read as pixels\n', file=sys.stderr)

    total = 0
    for dump in args.dumps:
        total += check(dump, rule, args.density, args.window)
        print()

    print('%d element(s) would be covered in total' % total)
    return 0


if __name__ == '__main__':
    sys.exit(main())
