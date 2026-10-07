#!/usr/bin/env python3
"""Prépare SQL espèces depuis extrait OSM. Aucune connexion ni écriture en base."""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


OBJECT_TYPES = {"node": "N", "way": "W", "relation": "R"}
KNOWN_VALUES = {"yes", "only", "no"}


def cash_rows(stream):
    """Identifiants exacts seulement. Absence, intervalle et conflits restent inconnus."""
    rows = {}
    root = None
    for event, element in ET.iterparse(stream, events=("start", "end")):
        if root is None:
            root = element
            if root.tag != "osm":
                raise ValueError("expected OSM XML")
        if event != "end" or element.tag not in OBJECT_TYPES:
            continue
        tags = {}
        conflicting = set()
        for tag in element.findall("tag"):
            key, value = tag.get("k"), tag.get("v")
            if key in tags and tags[key] != value:
                conflicting.add(key)
            tags[key] = value
        raw_id = element.get("id", "")
        cash = str(tags.get("payment:cash", "")).strip().lower()
        if tags.get("amenity") == "fuel" and not conflicting.intersection({"amenity", "payment:cash"}):
            if raw_id.isascii() and raw_id.isdecimal() and 0 < int(raw_id) <= 9223372036854775807 and cash in KNOWN_VALUES:
                key = (OBJECT_TYPES[element.tag], int(raw_id))
                if key in rows and rows[key] != cash:
                    raise ValueError(f"conflicting object {element.tag}/{raw_id}")
                rows[key] = cash
        element.clear()
        root.clear()
    return [(kind, identifier, cash) for (kind, identifier), cash in sorted(rows.items())]


def write_sql(rows, stream):
    if not rows:
        raise ValueError("no explicit fuel cash payment found")
    stream.write("\\set ON_ERROR_STOP on\nBEGIN;\n")
    stream.write("SET LOCAL statement_timeout = '60s';\nSET LOCAL lock_timeout = '5s';\n")
    stream.write("CREATE TEMP TABLE fuel_cash_patch (osm_type char(1), osm_id bigint, cash text, PRIMARY KEY (osm_type, osm_id)) ON COMMIT DROP;\n")
    stream.write("COPY fuel_cash_patch (osm_type, osm_id, cash) FROM STDIN;\n")
    for kind, identifier, cash in rows:
        stream.write(f"{kind}\t{identifier}\t{cash}\n")
    stream.write("\\.\n")
    stream.write("""
DO $patch$
BEGIN
  -- Schéma import supprimé après publication ; compléter seulement s'il existe encore.
  IF to_regclass('osm.places') IS NOT NULL THEN
    UPDATE osm.places p
    SET tags = p.tags || jsonb_build_object('payment:cash', c.cash)
    FROM fuel_cash_patch c
    WHERE p.kind = 'fuel' AND p.osm_type = c.osm_type AND p.osm_id = c.osm_id
      AND NOT (p.tags ? 'payment:cash');
  END IF;
END;
$patch$;

UPDATE signs.place p
SET tags = p.tags || jsonb_build_object('payment:cash', c.cash)
FROM fuel_cash_patch c
WHERE p.kind = 'fuel'
  AND p.id = CASE c.osm_type WHEN 'N' THEN 'node/' WHEN 'W' THEN 'way/' ELSE 'relation/' END || c.osm_id
  AND NOT (p.tags ? 'payment:cash');

SELECT 'signs' AS source, tags->>'payment:cash' AS cash, count(*) AS stations
FROM signs.place WHERE kind = 'fuel' AND tags ? 'payment:cash' GROUP BY cash
ORDER BY source, cash;
COMMIT;
""")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("osm", help="extrait OSM XML, ou - pour entrée standard")
    parser.add_argument("output", help="fichier SQL à créer ; fichier existant refusé")
    args = parser.parse_args()
    if args.osm == "-":
        rows = cash_rows(sys.stdin.buffer)
    else:
        with Path(args.osm).open("rb") as source:
            rows = cash_rows(source)
    if not rows:
        raise ValueError("no explicit fuel cash payment found")
    with Path(args.output).open("x", encoding="utf-8", newline="\n") as target:
        write_sql(rows, target)
    print(f"[fuel] {len(rows)} objets explicites ; SQL préparé, base inchangée.", file=sys.stderr)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"[fuel] {error}", file=sys.stderr)
        sys.exit(1)
