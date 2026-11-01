import tempfile
import unittest
from pathlib import Path

from nibtexa_eval import latex
from nibtexa_eval.inkml import Sample, load, to_scgink
from nibtexa_eval.run import score

MATHWRITING = """<ink xmlns="http://www.w3.org/2003/InkML">
  <annotation type="normalizedLabel">\\frac{a}{b}</annotation>
  <traceFormat><channel name="X" type="decimal"/><channel name="Y" type="decimal"/><channel name="T" type="decimal"/></traceFormat>
  <trace id="0">1.5 2 0.0,3 4 10.0</trace>
  <trace id="1">5 6 20.0</trace>
</ink>"""

NIBTEXA = """<?xml version="1.0" encoding="UTF-8"?>
<ink xmlns="http://www.w3.org/2003/InkML">
  <traceFormat><channel name="X" type="decimal"/><channel name="Y" type="decimal"/><channel name="T" type="integer"/><channel name="F" type="decimal"/></traceFormat>
  <annotation type="truth">$x^{2}$</annotation>
  <trace id="0">1.00 2.00 0 0.50, 3.00 4.00 8 0.60</trace>
</ink>"""


class LatexTest(unittest.TestCase):
    def test_tokenizes_commands_and_characters(self):
        self.assertEqual(latex.tokenize(r"\frac{a}{b}+\alpha"), ["\\frac", "{", "a", "}", "{", "b", "}", "+", "\\alpha"])

    def test_presentational_differences_normalize_equal(self):
        self.assertEqual(latex.normalize("x^2"), latex.normalize("x^{2}"))
        self.assertEqual(latex.normalize(r"\left( x \right)"), latex.normalize("(x)"))
        self.assertEqual(latex.normalize(r"\dfrac{1}{2}"), latex.normalize(r"\frac{1}{2}"))
        self.assertEqual(latex.normalize("( x + y )^{2}"), latex.normalize("(x+y)^2"))
        self.assertEqual(latex.normalize(r"2\sin ( x )"), latex.normalize("2sin(x)"))
        self.assertEqual(latex.normalize(r"\lim_{x\to0}"), latex.normalize(r"lim_{x\rightarrow 0}"))
        self.assertEqual(latex.normalize(r"\[\hbar \omega_p\]"), latex.normalize(r"\hbar\omega_{p}"))
        self.assertEqual(latex.normalize(r"\overrightarrow{0}"), latex.normalize(r"\vec{0}"))

    def test_content_differences_do_not_normalize_equal(self):
        self.assertNotEqual(latex.normalize("x^{2}"), latex.normalize("x_{2}"))
        self.assertNotEqual(latex.normalize(r"\int_{-1}^{1}"), latex.normalize(r"\int_{1}^{1}"))

    def test_edit_distance(self):
        self.assertEqual(latex.edit_distance(list("abc"), list("abc")), 0)
        self.assertEqual(latex.edit_distance(list("abc"), list("abd")), 1)
        self.assertEqual(latex.edit_distance([], list("ab")), 2)

    def test_seshat_vocabulary(self):
        self.assertTrue(latex.in_seshat_vocabulary(r"\frac{x^{2}}{\sqrt{y}}"))
        self.assertFalse(latex.in_seshat_vocabulary(r"\mathcal{L}"))
        self.assertFalse(latex.in_seshat_vocabulary(r"\omega"))


class InkMlTest(unittest.TestCase):
    def _load(self, text: str) -> Sample:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "sample1.inkml"
            path.write_text(text, encoding="utf-8")
            return load(path)

    def test_reads_mathwriting(self):
        s = self._load(MATHWRITING)
        self.assertEqual(s.truth, r"\frac{a}{b}")
        self.assertEqual(s.source, "mathwriting")
        self.assertEqual(s.strokes, [[(1.5, 2.0, 0.0), (3.0, 4.0, 10.0)], [(5.0, 6.0, 20.0)]])

    def test_reads_nibtexa_corpus_and_strips_math_delimiters(self):
        s = self._load(NIBTEXA)
        self.assertEqual(s.truth, "x^{2}")
        self.assertEqual(s.source, "nibtexa")
        self.assertEqual(s.strokes, [[(1.0, 2.0, 0.0), (3.0, 4.0, 8.0)]])

    def test_scgink(self):
        s = Sample("a", [[(1, 2, 0), (3, 4, 1)], [(5, 6, 2)]], "x", "nibtexa")
        self.assertEqual(to_scgink(s), "SCG_INK\n2\n2\n1.00 2.00\n3.00 4.00\n1\n5.00 6.00\n")


class SeshatOutputTest(unittest.TestCase):
    def test_parses_results_and_tolerates_odd_timing(self):
        from nibtexa_eval.seshat import parse_output

        latex_b64 = "XGZyYWN7YX17Yn0="  # \frac{a}{b}
        out = "noise\nRESULT\ta\t0\t1.25 40000\t" + latex_b64 + "\nRESULT\tb\t124\tCommand terminated\t\n"
        r = parse_output(out)

        self.assertEqual(r["a"], {"latex": r"\frac{a}{b}", "exit": 0, "seconds": 1.25, "max_rss_kb": 40000})
        self.assertEqual(r["b"]["latex"], "")
        self.assertIsNone(r["b"]["seconds"])


class ScoreTest(unittest.TestCase):
    def test_scores_exact_partial_and_failed(self):
        samples = [Sample(i, [], t, "nibtexa") for i, t in [("a", "x^{2}"), ("b", "x+y"), ("c", "z")]]
        outputs = {"a": {"latex": "x^2", "exit": 0}, "b": {"latex": "x-y", "exit": 0}}
        rows, summary = score(samples, outputs)

        self.assertEqual([r["exact"] for r in rows], [True, False, False])
        self.assertEqual([r["failed"] for r in rows], [False, False, True])
        self.assertAlmostEqual(summary["all"]["exact_match"], 1 / 3)
        self.assertAlmostEqual(summary["all"]["within_1_token"], 2 / 3)
        self.assertEqual(summary["all"]["failures"], 1)


if __name__ == "__main__":
    unittest.main()
