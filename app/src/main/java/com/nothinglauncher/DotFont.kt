package com.nothinglauncher

/**
 * A hand-built 5×7 dot-matrix typeface (digits, A–Z and a few symbols).
 *
 * Glyphs are written as strings so they are easy to read and tweak: '#' is a lit dot.
 * Narrow glyphs (I, colon, period…) simply use fewer columns.
 */
object DotFont {

    const val ROWS = 7

    private val glyphs: Map<Char, Array<String>> = mapOf(
        '0' to arrayOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        '1' to arrayOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to arrayOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
        '4' to arrayOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to arrayOf(".###.", "#....", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to arrayOf(".###.", "#...#", "#...#", ".####", "....#", "....#", ".###."),
        'A' to arrayOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'B' to arrayOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
        'C' to arrayOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
        'D' to arrayOf("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
        'E' to arrayOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
        'F' to arrayOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
        'G' to arrayOf(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####"),
        'H' to arrayOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'I' to arrayOf("###", ".#.", ".#.", ".#.", ".#.", ".#.", "###"),
        'J' to arrayOf("..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."),
        'K' to arrayOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
        'L' to arrayOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
        'M' to arrayOf("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
        'N' to arrayOf("#...#", "##..#", "##..#", "#.#.#", "#..##", "#..##", "#...#"),
        'O' to arrayOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'P' to arrayOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
        'Q' to arrayOf(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
        'R' to arrayOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
        'S' to arrayOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
        'T' to arrayOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'U' to arrayOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'V' to arrayOf("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
        'W' to arrayOf("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "#.#.#", ".#.#."),
        'X' to arrayOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
        'Y' to arrayOf("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
        'Z' to arrayOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
        ':' to arrayOf(".", ".", "#", ".", "#", ".", "."),
        '.' to arrayOf(".", ".", ".", ".", ".", ".", "#"),
        '-' to arrayOf("...", "...", "...", "###", "...", "...", "..."),
        '+' to arrayOf(".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."),
        '/' to arrayOf("....#", "...#.", "...#.", "..#..", ".#...", ".#...", "#...."),
        '%' to arrayOf("##..#", "##..#", "...#.", "..#..", ".#...", "#..##", "#..##"),
        '°' to arrayOf("###", "#.#", "###", "...", "...", "...", "..."),
        '!' to arrayOf("#", "#", "#", "#", "#", ".", "#"),
        '?' to arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#.."),
        '#' to arrayOf(".#.#.", ".#.#.", "#####", ".#.#.", "#####", ".#.#.", ".#.#."),
        ' ' to arrayOf("..", "..", "..", "..", "..", "..", "..")
    )

    fun glyph(c: Char): Array<String> = glyphs[c.uppercaseChar()] ?: glyphs.getValue(' ')
    fun glyphFor(c: Char): Array<String> = glyph(c)

    /**
     * A rasterised line of text.
     * [lit] is ROWS × [columns]; [charIndexAtColumn] maps each column back to the
     * source character (or -1 for inter-letter spacing).
     */
    class Raster(val columns: Int, val lit: Array<BooleanArray>, val charIndexAtColumn: IntArray)

    fun rasterize(text: String, spacing: Int = 1): Raster {
        if (text.isEmpty()) return Raster(0, Array(ROWS) { BooleanArray(0) }, IntArray(0))
        val glyphList = text.map { glyph(it) }
        val columns = glyphList.sumOf { it[0].length } + spacing * (glyphList.size - 1)
        val lit = Array(ROWS) { BooleanArray(columns) }
        val owner = IntArray(columns) { -1 }
        var x = 0
        glyphList.forEachIndexed { index, g ->
            val w = g[0].length
            for (row in 0 until ROWS) {
                val line = g[row]
                for (col in 0 until w) if (line[col] == '#') lit[row][x + col] = true
            }
            for (col in 0 until w) owner[x + col] = index
            x += w + spacing
        }
        return Raster(columns, lit, owner)
    }
}
