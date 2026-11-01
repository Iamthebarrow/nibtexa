"""LaTeX tokenization and normalization for scoring recognizer output.

Comparison is token-based after normalization, so purely presentational
differences (spacing, optional braces, \\left/\\right) do not count as errors.
The rules are deliberately small and listed here so results are reproducible.
"""

from __future__ import annotations

import re

_TOKEN = re.compile(r"\\[a-zA-Z]+|\\.|[^\s]")

# Tokens that carry no handwritten content.
_DROP = {
    "\\left", "\\right", "\\displaystyle", "\\,", "\\;", "\\:", "\\!", "\\ ", "\\limits",
    # Math-mode delimiters some recognizers wrap their output in.
    "\\[", "\\]", "\\(", "\\)", "$",
}

_SYNONYMS = {
    "\\dfrac": "\\frac",
    "\\tfrac": "\\frac",
    "\\lt": "<",
    "\\gt": ">",
    "\\le": "\\leq",
    "\\ge": "\\geq",
    "\\ne": "\\neq",
    "\\to": "\\rightarrow",
    "\\dots": "\\ldots",
    "\\cdots": "\\ldots",
    "\\lbrace": "\\{",
    "\\rbrace": "\\}",
    "\\tg": "\\tan",
    "\\overrightarrow": "\\vec",
}


def tokenize(latex: str) -> list[str]:
    return _TOKEN.findall(latex)


# Upright operator names are written as plain letters in handwriting; "sin" and
# "\sin" are the same handwritten content, so both sides are spelled out.
_OPERATOR_NAMES = {"\\sin", "\\cos", "\\tan", "\\log", "\\ln", "\\exp", "\\lim", "\\max", "\\min", "\\det"}


def normalize(latex: str) -> list[str]:
    tokens: list[str] = []
    for t in tokenize(latex):
        if t in _DROP:
            continue
        t = _SYNONYMS.get(t, t)
        tokens.extend(list(t[1:]) if t in _OPERATOR_NAMES else [t])
    return _brace_scripts(tokens)


def _brace_scripts(tokens: list[str]) -> list[str]:
    """Writes every super/subscript argument in braces: x^2 -> x^{2}."""
    out: list[str] = []
    i = 0
    while i < len(tokens):
        token = tokens[i]
        out.append(token)
        if token in ("^", "_") and i + 1 < len(tokens) and tokens[i + 1] != "{":
            out.extend(["{", tokens[i + 1], "}"])
            i += 2
            continue
        i += 1
    return out


def edit_distance(a: list[str], b: list[str]) -> int:
    previous = list(range(len(b) + 1))
    for i, ta in enumerate(a, 1):
        current = [i]
        for j, tb in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (ta != tb)))
        previous = current
    return previous[-1]


# Seshat's 101 CROHME-2014 symbol classes, as LaTeX tokens.
SESHAT_VOCABULARY = set(
    "| - ! + 0 1 2 3 4 5 6 7 8 9 , . = ( ) [ ] < >".split()
    + list("abcdefghijklmnopqrstuvwxyz")
    + list("ABCEFGHILMNPRSTVXY")
    + r"\alpha \beta \cos \Delta \div \ldots \exists \forall \gamma \geq \in \infty \int \lambda \leq \lim \log \mu \neq \phi \pi \pm \prime \rightarrow \sigma \sin \sqrt \sum \tan \theta \times \{ \}".split()
)
# Structural tokens every grammar-based recognizer produces.
_STRUCTURE = {"{", "}", "^", "_", "\\frac"}


def in_seshat_vocabulary(latex: str) -> bool:
    return all(t in SESHAT_VOCABULARY or t in _STRUCTURE for t in normalize(latex))
