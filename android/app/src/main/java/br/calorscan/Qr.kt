package br.calorscan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * QR code do link de download do app (para instalar em outro celular).
 * Gerado no PC (segno, versao 6, correcao M) e guardado aqui como desenho: nao precisa de biblioteca nem internet.
 * Se o LINK mudar, gere de novo (ver CLAUDE.md) e troque ROWS.
 */
object Qr {
    const val LINK = "https://github.com/lukas-lkc/App-mobile-performance/raw/ccr-e3a2eeb0-ekv3ov/apk/CalorScan.apk"
    val ROWS = listOf(
        "#######......#..#..#.#.##..#####..#######",
        "#.....#.###.#.#.##.##.##.#.#..###.#.....#",
        "#.###.#....#.#..#...####..###.###.#.###.#",
        "#.###.#.....#.##.#.#.#####.##..#..#.###.#",
        "#.###.#.#.....###..#.###..##..##..#.###.#",
        "#.....#..#.#.##....##.###..##.###.#.....#",
        "#######.#.#.#.#.#.#.#.#.#.#.#.#.#.#######",
        "...........####.###..##.##.####.#........",
        "#.#.#.#...#.....###..#.#######.##...#..#.",
        "..####...###..#.##..##...#..##..#.#..####",
        "#.######.#..###.#....##...#..##...##..###",
        "..##....#..###..####.#.###.###..####....#",
        "##..#.#...####.#.##.##.#.#..##.#####.#..#",
        "#..###.#.###.#...#.###.#.#.#.#..#....#..#",
        "##.#.####..####.#.#####...#...#...#.##.##",
        "#...##.#.#.#.#...#..####.#...###..##.#...",
        ".#...##.#.##.#.###.###...######...##....#",
        ".#..##..#.##...##...##..#...#.#...#....##",
        ".....##.###.####.#....#..#...#....#.##.##",
        "#.##.#...#.#..#.....###....###.###.#.#..#",
        ".###..##.#...###...##.###...##..#.##.....",
        "#..###..#...##..#...#.#.#.......###..#.##",
        "....###.#.#.##.#....#...##....#...##.####",
        "#...##....######.##.##..##.########.#....",
        ".##...##...#....##.###..##...#.##.#....#.",
        "##.###..#.#.#.......#.#.##..#.#..##....#.",
        "#...#.#####..###.#..........#.#...##..###",
        "##.#.#..#.#####.###..##.####.##.#.##....#",
        "#.....##..######.#.#.#.###.###.#####.#.#.",
        ".####..##.#..#.##.##...##.#.##..###...###",
        "#.##.####..########..#.##...###....###.##",
        ".###....#...#.######.##.####.#...###...##",
        "#.#...#.####..#####.##.###..##..#####.#.#",
        "........#..###.#.#....#.##..##.##...#.###",
        "#######..##.#...#.#.##....#...###.#.##.##",
        "#.....#.....##..###.#..########.#...#..#.",
        "#.###.#.##.##.#.#.#.#...#.#.##..#####..#.",
        "#.###.#...###.##..#.##..###.###..#..#.#..",
        "#.###.#.####..###.......###.#..#...##.###",
        "#.....#..###.......#.##..#####.##.##...#.",
        "#######.#..#.##.#...##..##.###.######..##"
    )
}

/** Desenha o QR sempre preto no branco (camera le melhor), com a margem branca que o padrao pede. */
class QrView(ctx: Context) : View(ctx) {
    private val dark = Paint().apply { color = Color.BLACK; isAntiAlias = false }
    private val light = Paint().apply { color = Color.WHITE }
    private val border = Paint().apply { color = Ui.INK; style = Paint.Style.STROKE }
    private val quiet = 4

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w).coerceAtMost((260 * resources.displayMetrics.density).toInt())
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val n = Qr.ROWS.size + quiet * 2
        val cell = (width / n).toFloat().coerceAtLeast(1f)   // inteiro: quadradinhos sem borda borrada
        val off = (width - cell * n) / 2f
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), light)
        Qr.ROWS.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch ->
                if (ch == '#') {
                    val l = off + (x + quiet) * cell; val t = off + (y + quiet) * cell
                    c.drawRect(l, t, l + cell, t + cell, dark)
                }
            }
        }
        border.strokeWidth = 3 * resources.displayMetrics.density
        border.color = Ui.INK
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), border)
    }
}
