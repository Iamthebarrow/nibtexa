package io.github.iamthebarrow.nibtexa.feature.mathedit

import io.github.iamthebarrow.nibtexa.feature.mathedit.bridge.DocumentBlock

/** Twenty equations of mixed structure for the one-screen performance check. */
object SampleEquations {
    val TWENTY = listOf(
        """\int_{-1}^{1} x^{2}\,dx=\frac{2}{3}""",
        """x=\frac{-b\pm\sqrt{b^{2}-4ac}}{2a}""",
        """\sum_{n=1}^{\infty}\frac{1}{n^{2}}=\frac{\pi^{2}}{6}""",
        """e^{i\pi}+1=0""",
        """\lim_{x\to0}\frac{\sin x}{x}=1""",
        """\frac{d}{dx}\left(x^{n}\right)=nx^{n-1}""",
        """\begin{pmatrix}a&b\\c&d\end{pmatrix}^{-1}=\frac{1}{ad-bc}\begin{pmatrix}d&-b\\-c&a\end{pmatrix}""",
        """\nabla\cdot\vec{E}=\frac{\rho}{\varepsilon_{0}}""",
        """\binom{n}{k}=\frac{n!}{k!(n-k)!}""",
        """f(x)=\begin{cases}x^{2}&x\geq0\\-x&x<0\end{cases}""",
        """\oint_{C}\vec{F}\cdot d\vec{r}=\iint_{S}(\nabla\times\vec{F})\cdot d\vec{S}""",
        """\sqrt[3]{x^{3}+y^{3}}\leq|x|+|y|""",
        """\prod_{k=1}^{n}k=n!""",
        """\forall\varepsilon>0\,\exists\delta>0:|x-a|<\delta\Rightarrow|f(x)-f(a)|<\varepsilon""",
        """\log_{2}8=3""",
        """\sin^{2}\theta+\cos^{2}\theta=1""",
        """A\cup B=\{x\mid x\in A\lor x\in B\}""",
        """\frac{\partial^{2}u}{\partial t^{2}}=c^{2}\frac{\partial^{2}u}{\partial x^{2}}""",
        """\overline{z_{1}z_{2}}=\overline{z_{1}}\,\overline{z_{2}}""",
        """\mathbb{E}[X]=\sum_{i}x_{i}p_{i}""",
    )

    /** A short document for the PDF page (Phase 12): every block kind, with markup-like text. */
    val DOCUMENT = listOf(
        DocumentBlock(DocumentBlock.Kind.TITLE, "Signals & Systems"),
        DocumentBlock(DocumentBlock.Kind.HEADING, "Week 1 <b>not bold</b>", level = 1),
        DocumentBlock(DocumentBlock.Kind.PARAGRAPH, "The transfer function has a pole at s = -2. <script>x()</script>"),
        DocumentBlock(DocumentBlock.Kind.MATH, TWENTY.first()),
        DocumentBlock(DocumentBlock.Kind.FIGURE, "A sketch", width = 200.0, height = 80.0),
    )
}
