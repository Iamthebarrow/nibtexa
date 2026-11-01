# ADR-0008: Maths engine for graphs

- **Status:** Accepted
- **Date:** 2026-10-30

## Context

Graphs on the page (Phase 25) need written equations turned into values. The equation exists as LaTeX, from the
recognizer or the correction editor. Something has to read that LaTeX, work out the function, and sample it into a
curve, offline and on the device.

## Decision

1. **A small parser of our own, in a new pure Kotlin module, `:core:maths`, with no dependencies.** General expression
   libraries read their own syntax, not LaTeX, so a LaTeX reader would be needed anyway. A computer algebra system is
   far more than graphing needs and would bring native code or licence questions. The reader and sampler together are
   under a thousand lines and are tested on the JVM.
2. **Nothing in the source is run.** LaTeX is only read into a tree of arithmetic. There is no macro expansion and no
   `eval`. A command it doesn't know is reported, never skipped, so `\int x` can't quietly draw `x`.
3. **It says why it can't graph something** (`MathsProblem`), so the app can explain it. Input length and nesting are
   limited, so hostile or absurd input fails quickly and cleanly.
4. **It reads only functions of one variable.** `y=...`, `f(x)=...`, `...=y` and a bare expression. Other letters are
   constants the user gives values to. Implicit curves such as `x^2+y^2=1`, integrals and sums are out of scope for now.

## Reading rules

Handwritten maths is ambiguous, so these are stated and tested, and don't depend on guessing:

- `*` and `/` go left to right, so `1/2x` is `(1/2)x`, as in MATLAB. `\frac` is the clear way to write the other.
- Things side by side multiply: `2x`, `(x+1)(x-1)`, `2\sin x`. Every letter is its own name, so `ab` is `a` times `b`.
- Powers bind tightest and go right to left: `-x^2` is `-(x^2)`.
- A script or `\frac` argument without braces takes one digit, as LaTeX shows it: `x^23` is `x²·3`, and `\frac12` a half.
- A function without brackets takes what follows up to the next `+`, `-` or function: `\sin 2x + 1` is `\sin(2x) + 1`,
  and `\sin x \cos x` is `\sin(x)\cos(x)`. With brackets it takes only those: `\sin(x)y` is `\sin(x)` times `y`.
- `\sin^2 x` is `(\sin x)^2`, and `\sin^{-1} x` is `\arcsin x`.
- `\log` is base 10 and `\ln` natural, as in engineering notes. `e` and `\pi` are the constants.
- Odd roots of negative numbers are defined: `\sqrt[3]{x}`, `x^{1/3}`.
- The horizontal axis is the named argument, or else the first of `x`, `t`, `s`, `\theta`, `\omega`, `n`, `u`, or the
  only variable. Otherwise the user chooses it.
- Angles are radians unless degrees are chosen. It applies to trigonometric functions only, not to `\sinh` or `\ln`.

## Consequences

- `\log` as base 10 differs from some maths texts, where it's natural. `\log_{e}` is always available.
- `ab` meaning a product means a multi-letter variable needs a subscript or `\mathrm{name}`.
- Reading more of mathematics (implicit curves, sums, piecewise) is added to this module later, without other changes.
