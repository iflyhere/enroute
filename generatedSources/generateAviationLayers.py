#!/usr/bin/env python3
#
# Extracts the aviation-data style layers from src/qml/items/FlightMap.qml and writes
# them as a MapLibre style fragment, for serving to a companion device that renders the
# map itself.
#
# Why generated rather than written by hand: the layer definitions are the single source
# of truth for what an airspace looks like in this app, and there are twenty of them with
# non-trivial filter expressions. A second hand-maintained copy would drift, and the
# first symptom of drift would be a watch drawing an airspace the phone does not, which
# is the one failure mode that must not happen.
#
# The values that QML computes at run time -- the pilot's altitude filter, the
# night-mode-aware colours and the fill opacities -- cannot be baked in here, so they
# become markers of the form "@name". Companion::MapAssets resolves them, with the
# correct type, when it serves the style.
#
# Run from the repository root:
#     python3 generatedSources/generateAviationLayers.py

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "src" / "qml" / "items" / "FlightMap.qml"
GLOBAL = ROOT / "src" / "qml" / "items" / "Global.qml"
TARGET = ROOT / "generatedSources" / "flightMap" / "aviation-layers.json"

# QML identifiers that stand for a value only the running app knows, and the marker each
# becomes. Anything else that is not valid JSON makes the layer skipped rather than
# silently mistranslated.
RUNTIME = {
    "Global.airspaceBlue": "@airspaceBlue",
    "Global.airspaceRed": "@airspaceRed",
    "Global.airspaceGreen": "@airspaceGreen",
    "Global.airspaceYellow": "@airspaceYellow",
    "Global.airspaceNeutral": "@airspaceNeutral",
    "flightMap.airspaceAltitudeLimitInFeet": "@altitudeLimitFt",
    "flightMap.airspaceBandOpacity": "@bandOpacity",
    "flightMap.airspaceFillOpacity": "@fillOpacity",
    "flightMap.glidingFillOpacity": "@glidingFillOpacity",
    "flightMap.overlayLabelColor": "@overlayLabelColor",
    "flightMap.overlayHaloColor": "@overlayHaloColor",
}

# Layers that exist on the phone but are deliberately not sent on. The labels are the
# reason: a name plate that is readable on a six-inch screen is a grey smear on a watch
# face, and the outlines carry the information either way.
# One of the three stacks in generatedSources/flightMap/fonts, which is what the app
# ships and therefore all a client can ask for.
DEFAULT_FONT = "Roboto Regular"

SKIP = {
    "AirspaceLabels": "label layer, unreadable at watch size",
    "PRCLabels": "label layer, unreadable at watch size",
    "TFCLabels": "traffic labels, and a watch has no traffic source",
    "optionalText": "label layer, unreadable at watch size",
}


# Text sizes are written as a factor times the pilot's chosen font size, so the marker
# has to carry the factor with it.
SCALED = re.compile(r"([0-9]*\.?[0-9]+)\s*\*\s*GlobalSettings\.fontSize")


def qml_to_json(text):
    """Turns a QML value expression into JSON, or raises if it cannot be done exactly."""
    text = SCALED.sub(lambda m: json.dumps("@fontSize:" + m.group(1)), text)
    text = text.replace("GlobalSettings.fontSize", json.dumps("@fontSize:1"))
    for identifier, marker in RUNTIME.items():
        text = text.replace(identifier, json.dumps(marker))
    return json.loads(text)


# The values behind the markers are themselves QML, of the shape
#     readonly property <type> name: GlobalSettings.nightMode ? <night> : <day>
# and they are extracted too rather than copied into C++ by hand. Copying them would put
# the colours in two places, and the whole point of generating this file is that there is
# only one.
CONDITIONAL = re.compile(
    r"readonly\s+property\s+\w+\s+(\w+):\s*GlobalSettings\.nightMode\s*\?\s*"
    r"([^:]+?)\s*:\s*(.+?)\s*$",
    re.M,
)

WANTED_VALUES = {
    "airspaceBlue", "airspaceRed", "airspaceGreen", "airspaceYellow", "airspaceNeutral",
    "airspaceBandOpacity", "airspaceFillOpacity", "glidingFillOpacity",
    "overlayLabelColor", "overlayHaloColor",
}

# The markers use shorter names than the QML properties for the three opacities.
VALUE_ALIAS = {
    "airspaceBandOpacity": "bandOpacity",
    "airspaceFillOpacity": "fillOpacity",
}


def extract_values(*texts):
    values = {}
    for text in texts:
        for match in CONDITIONAL.finditer(text):
            name = match.group(1)
            if name not in WANTED_VALUES:
                continue
            try:
                night = json.loads(match.group(2).strip())
                day = json.loads(match.group(3).strip())
            except json.JSONDecodeError:
                continue
            values[VALUE_ALIAS.get(name, name)] = {"night": night, "day": day}
    return values


def balanced(text, start, opening, closing):
    """Returns the span of a bracketed expression beginning at start."""
    depth = 0
    in_string = False
    i = start
    while i < len(text):
        ch = text[i]
        if in_string:
            if ch == "\\":
                i += 2
                continue
            if ch == '"':
                in_string = False
        elif ch == '"':
            in_string = True
        elif ch == opening:
            depth += 1
        elif ch == closing:
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
        i += 1
    raise ValueError("unbalanced expression")


def extract(text):
    layers = []
    skipped = []

    for match in re.finditer(r"\bLayerParameter\s*\{", text):
        block = balanced(text, match.end() - 1, "{", "}")

        style_id = re.search(r'styleId:\s*"([^"]+)"', block)
        layer_type = re.search(r'type:\s*"([^"]+)"', block)
        source = re.search(r'property\s+string\s+source:\s*"([^"]+)"', block)
        if not (style_id and layer_type and source):
            continue
        if source.group(1) != "aviation-data":
            continue

        name = style_id.group(1)
        if name in SKIP:
            skipped.append((name, SKIP[name]))
            continue

        layer = {"id": name, "type": layer_type.group(1), "source": "aviation-data"}

        filter_at = block.find("property var filter:")
        if filter_at >= 0:
            expression = balanced(block, block.index("[", filter_at), "[", "]")
            try:
                layer["filter"] = qml_to_json(expression)
            except (ValueError, json.JSONDecodeError) as error:
                skipped.append((name, f"filter is not translatable: {error}"))
                continue

        paint_at = block.find("paint:")
        if paint_at >= 0:
            expression = balanced(block, block.index("{", paint_at), "{", "}")
            try:
                layer["paint"] = qml_to_json(expression)
            except (ValueError, json.JSONDecodeError) as error:
                skipped.append((name, f"paint is not translatable: {error}"))
                continue

        layout_at = block.find("layout:")
        if layout_at >= 0:
            expression = balanced(block, block.index("{", layout_at), "{", "}")
            try:
                layer["layout"] = qml_to_json(expression)
            except (ValueError, json.JSONDecodeError) as error:
                skipped.append((name, f"layout is not translatable: {error}"))
                continue

        # The QML sets no text-font, so each renderer falls back to its own default
        # -- and MapLibre's default is "Open Sans Regular, Arial Unicode MS Regular",
        # which this app does not ship. A client then requests a font stack that
        # answers 404, and a symbol layer whose font never arrives stalls the whole
        # style load: no map at all, not merely no labels. Naming a stack that is
        # actually shipped is therefore a correction, not a liberty.
        layout = layer.get("layout", {})
        if "text-field" in layout and "text-font" not in layout:
            layout["text-font"] = [DEFAULT_FONT]
            layer["layout"] = layout

        layers.append(layer)

    return layers, skipped


def used_markers(layers):
    found = set()

    def walk(node):
        if isinstance(node, str) and node.startswith("@"):
            found.add(node)
        elif isinstance(node, list):
            for item in node:
                walk(item)
        elif isinstance(node, dict):
            for item in node.values():
                walk(item)

    walk(layers)
    return found


def main():
    text = SOURCE.read_text(encoding="utf-8")
    layers, skipped = extract(text)

    if not layers:
        print("no layers extracted; the QML shape must have changed", file=sys.stderr)
        return 1

    values = extract_values(text, GLOBAL.read_text(encoding="utf-8"))
    missing = sorted(
        {marker.split(":")[0].lstrip("@") for marker in used_markers(layers)}
        - set(values)
        - {"altitudeLimitFt", "fontSize"}
    )
    if missing:
        print(f"markers with no value extracted: {missing}", file=sys.stderr)
        return 1

    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(
        json.dumps(
            {
                "comment": "Generated by generatedSources/generateAviationLayers.py "
                           "from src/qml/items/FlightMap.qml and Global.qml. "
                           "Do not edit; regenerate.",
                "values": values,
                "layers": layers,
            },
            indent=2,
        ) + "\n",
        encoding="utf-8",
    )

    print(f"wrote {TARGET.relative_to(ROOT)} with {len(layers)} layers "
          f"and {len(values)} extracted values")
    for name, reason in skipped:
        print(f"  skipped {name}: {reason}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
