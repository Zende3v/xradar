import importlib.util
import io
from pathlib import Path
import unittest


script = Path(__file__).resolve().parents[1] / "bin" / "eona-fuel-cash-backfill.py"
spec = importlib.util.spec_from_file_location("fuel_cash_backfill", script)
backfill = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backfill)


class FuelCashBackfillTests(unittest.TestCase):
    def test_exact_ids_only_and_unknown_never_refusal(self):
        rows = backfill.cash_rows(io.BytesIO(b"""<osm>
          <node id="1"><tag k="amenity" v="fuel"/><tag k="payment:cash" v="yes"/></node>
          <way id="1"><tag k="amenity" v="fuel"/><tag k="payment:cash" v="no"/></way>
          <relation id="1"><tag k="amenity" v="fuel"/><tag k="payment:cash" v="only"/></relation>
          <node id="2"><tag k="amenity" v="fuel"/></node>
          <node id="3"><tag k="amenity" v="fuel"/><tag k="payment:cash" v="interval=Mo-Fr"/></node>
          <node id="4"><tag k="shop" v="supermarket"/><tag k="payment:cash" v="yes"/></node>
          <node id="-5"><tag k="amenity" v="fuel"/><tag k="payment:cash" v="yes"/></node>
          <node id="9223372036854775808"><tag k="amenity" v="fuel"/><tag k="payment:cash" v="yes"/></node>
          <node id="6"><tag k="amenity" v="fuel"/><tag k="payment:cash" v="yes"/><tag k="payment:cash" v="no"/></node>
        </osm>"""))
        self.assertEqual(rows, [("N", 1, "yes"), ("R", 1, "only"), ("W", 1, "no")])

    def test_conflicting_object_and_wrong_document_rejected(self):
        for xml in [b"<not_osm/>", b"""<osm>
          <node id="1"><tag k="amenity" v="fuel"/><tag k="payment:cash" v="yes"/></node>
          <node id="1"><tag k="amenity" v="fuel"/><tag k="payment:cash" v="no"/></node>
        </osm>"""]:
            with self.assertRaises(ValueError):
                backfill.cash_rows(io.BytesIO(xml))

    def test_empty_extract_generates_no_transaction(self):
        target = io.StringIO()
        with self.assertRaises(ValueError):
            backfill.write_sql([], target)
        self.assertEqual(target.getvalue(), "")


if __name__ == "__main__":
    unittest.main()
