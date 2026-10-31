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
        '&' to arrayOf(".##..", "#..#.", "#.#..", ".#...", "#.#.#", "#..#.", ".##.#"),
        ',' to arrayOf("..", "..", "..", "..", "..", ".#", "#."),
        ';' to arrayOf("..", "..", ".#", "..", "..", ".#", "#."),
        '\'' to arrayOf("#", "#", ".", ".", ".", ".", "."),
        '"' to arrayOf("#.#", "#.#", "...", "...", "...", "...", "..."),
        '(' to arrayOf("..#", ".#.", "#..", "#..", "#..", ".#.", "..#"),
        ')' to arrayOf("#..", ".#.", "..#", "..#", "..#", ".#.", "#.."),
        '=' to arrayOf("...", "...", "###", "...", "###", "...", "..."),
        '*' to arrayOf(".....", "..#..", "#.#.#", ".###.", "#.#.#", "..#..", "....."),
        '_' to arrayOf("....", "....", "....", "....", "....", "....", "####"),
        '<' to arrayOf("...#", "..#.", ".#..", "#...", ".#..", "..#.", "...#"),
        '>' to arrayOf("#...", ".#..", "..#.", "...#", "..#.", ".#..", "#..."),
        '\u00B7' to arrayOf(".", ".", ".", "#", ".", ".", "."),
        '\u2026' to arrayOf(".....", ".....", ".....", ".....", ".....", ".....", "#.#.#"),
        '@' to arrayOf(".###.", "#...#", "#.###", "#.#.#", "#.###", "#....", ".####"),
        ' ' to arrayOf("..", "..", "..", "..", "..", "..", "..")
    )

    private val blank: Array<String> = glyphs.getValue(' ')

    /** Lazily filled map for characters outside the base set (accents, non-ASCII digits…). */
    private val fallback = java.util.concurrent.ConcurrentHashMap<Char, Array<String>>()

    fun glyph(c: Char): Array<String> {
        glyphs[c]?.let { return it }
        glyphs[c.uppercaseChar()]?.let { return it }
        return fallback.getOrPut(c) { resolveFallback(c) }
    }

    fun glyphFor(c: Char): Array<String> = glyph(c)

    /**
     * Maps characters the font has no glyph for onto ones it does: locale digits ('٣' → '3'),
     * accented letters ('É' → 'E', 'ß' → 'S'), typographic punctuation and other dashes/spaces.
     * Anything else renders as a blank cell rather than garbage.
     */
    private fun resolveFallback(c: Char): Array<String> {
        val digit = Character.digit(c, 10)
        if (digit in 0..9) return glyphs.getValue('0' + digit)
        when (c) {
            '\u2018', '\u2019', '\u201A', '`', '\u00B4' -> return glyphs.getValue('\'')
            '\u201C', '\u201D', '\u201E' -> return glyphs.getValue('"')
            '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2212' -> return glyphs.getValue('-')
            '\u2022', '\u2219' -> return glyphs.getValue('\u00B7')
            '\u00D7' -> return glyphs.getValue('X')
            '\u00DF' -> return glyphs.getValue('S')
            '\u00C6', '\u00E6' -> return glyphs.getValue('E')
            '\u00D8', '\u00F8' -> return glyphs.getValue('O')
            '\u0141', '\u0142' -> return glyphs.getValue('L')
            '\u00BA', '\u02DA' -> return glyphs.getValue('\u00B0')
        }
        val base = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
            .firstOrNull() ?: return blank
        if (base != c) {
            glyphs[base]?.let { return it }
            glyphs[base.uppercaseChar()]?.let { return it }
        }
        return blank
    }

    /**
     * A rasterised line of text.
     * [lit] is ROWS × [columns]; [charIndexAtColumn] maps each column back to the
     * source character (or -1 for inter-letter spacing).
     */
    class Raster(val columns: Int, val lit: Array<BooleanArray>, val charIndexAtColumn: IntArray)

    private val rasterCache = android.util.LruCache<String, Raster>(96)

    /**
     * Same as [rasterize] but memoised, so views can call it from onDraw without allocating
     * a new raster every frame. The returned raster is shared: treat it as read-only.
     */
    fun rasterizeCached(text: String, spacing: Int = 1): Raster {
        val key = if (spacing == 1) text else "$spacing\u0000$text"
        rasterCache.get(key)?.let { return it }
        return rasterize(text, spacing).also { rasterCache.put(key, it) }
    }

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
