# Diagrams

The Diagram pen turns each stroke into a clean part as soon as it ends. `Region.diagram` (`core/model/.../notebook/Diagram.kt`) is a typed figure region of shapes, lines and labels, not ink.

## What a stroke becomes

`DiagramBuilder` uses plain geometry, with `ShapeRecognizer` for closed shapes. All its limits are constants at the top of the file. In order:

1. A tap: a dot, on a line or by itself. A tap on a label opens it to be corrected.
2. More of the writing just done, if small and close.
3. In a small circle, a straight stroke makes it a summing point: diagonal for ×, level or upright for +. In any other shape, its label.
4. Beside a summing point, a short level stroke is a − and an upright one across it makes it a +.
5. A small V with its point on a line is an arrowhead there.
6. A short straight stroke across a line is a tick.
7. A box, circle, ellipse or triangle; a straight or right-angled line, with any arrowhead drawn on its end; a zigzag (a resistor); an arc.
8. Otherwise, small is writing and larger is a steadied curve.

## Clean by construction

- Lines within 13 degrees of level or upright are made so, and every part of a right-angled line is exactly so.
- A new shape lines up with others within 14 units, and takes the size of a like one within 15%.
- A line's end within reach of a shape is held by it, and is drawn only up to its edge (`DiagramGeometry.path`). A shape drawn later takes the free ends it reaches.
- Moving a shape brings its lines and labels, and a right-angled line keeps its right angles.
- A stroke within 150 units (about an inch) of a diagram joins it, and one within reach of two makes them one, as one undo (`Diagram.merged`).

## Labels

Writing is read 1.2 seconds after the pen rests, as maths or words by a choice in the panel. A label in a shape stays centred, moves with it, and is made smaller if it wouldn't fit. The handwriting is kept, so **Use my handwriting** and **Read as…** go back to it. Writing that can't be read stays as handwriting in the figure. Labels aren't confirmed first: a wrong reading exports as read, and a tap corrects it.

## One drawing everywhere

`DiagramGeometry.plot` makes every part plain paths.

- **Page, thumbnails and reading view:** `ink/DiagramRenderer`.
- **PDF:** vector lines, with maths labels as images, as equations are.
- **LaTeX:** plain TikZ with no library (`DiagramLatex`), so `circuitikz` isn't needed. `\nibinside` fits a label to its shape.
- **Markdown:** a PNG.

## Limits

- **Tuned on generated strokes.** Real handwriting may ask for changes. The likeliest trouble is telling small writing from small shapes: a circle under 24 units is writing, a straight stroke under 34 is writing unless it crosses a line, and cursive over 90 units in one stroke is a curve.
- **A "1" in a circle up to 80 units wide** makes it a summing point. Larger circles, or changing the shape in the panel, avoid it.
- **Conversion replaces the stroke.** Shapes and lines don't keep their original strokes; undo takes back the part.
- **Lines don't route round things.** A straight line held at both ends goes slanted if one shape moves off level.
- **Inductors, capacitors and sources aren't recognized from drawing:** draw a line, and choose the symbol.
