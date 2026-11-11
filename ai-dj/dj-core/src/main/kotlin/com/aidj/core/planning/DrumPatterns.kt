package com.aidj.core.planning

/**
 * 16-step pattern bank for the native drum machine (one bar of 16th notes per voice, velocity 0..1).
 * Uploaded to the engine at start-up; transitions switch patterns with [ActionType.DRUM_PATTERN].
 */
object DrumPatterns {
    class Pattern(val id: Int, val name: String, val steps: Map<DrumVoice, FloatArray>, val swing: Float = 0f)

    private fun p(s: String): FloatArray = FloatArray(16) { i ->
        when (s.getOrElse(i) { '.' }) { 'X' -> 1f; 'x' -> 0.6f; 'o' -> 0.35f; else -> 0f }
    }

    val FOUR_ON_FLOOR = Pattern(0, "Four on the floor", mapOf(
        DrumVoice.KICK to p("X...X...X...X..."),
        DrumVoice.CLAP to p("....X.......X..."),
        DrumVoice.HAT to p("o.x.o.x.o.x.o.xo"),
        DrumVoice.OPEN_HAT to p("..x...x...x...x."),
        DrumVoice.BASS to p("..X...X...X..xX."),
    ))
    val BUILD_SNARE_ROLL = Pattern(1, "Build-up roll", mapOf(
        DrumVoice.KICK to p("X...X...X...X..."),
        DrumVoice.CLAP to p("X.x.X.x.XxXxXXXX"),
        DrumVoice.HAT to p("xxxxxxxxxxxxxxxx"),
    ))
    val BHANGRA_DHOL = Pattern(2, "Dhol bhangra groove", mapOf(
        DrumVoice.KICK to p("X..x..X...X..x.."),
        DrumVoice.PERC to p("..X.x..X.xX..X.x"),
        DrumVoice.CLAP to p("....X.......X..."),
        DrumVoice.HAT to p("x.o.x.o.x.o.x.o."),
    ), swing = 0.12f)
    val BREAKBEAT = Pattern(3, "Breakbeat", mapOf(
        DrumVoice.KICK to p("X.....X...X....."),
        DrumVoice.CLAP to p("....X.......X..x"),
        DrumVoice.HAT to p("x.x.x.x.x.x.x.x."),
        DrumVoice.PERC to p("......o.....o..."),
    ), swing = 0.08f)
    val HALF_TIME = Pattern(4, "Half-time", mapOf(
        DrumVoice.KICK to p("X.........X....."),
        DrumVoice.CLAP to p("........X......."),
        DrumVoice.HAT to p("x.x.x.x.x.x.x.x."),
    ))
    val TRIBAL = Pattern(5, "Tribal percussion", mapOf(
        DrumVoice.KICK to p("X...X...X...X..."),
        DrumVoice.PERC to p("x.Xx..x.x.Xx.xx."),
        DrumVoice.HAT to p("..x...x...x...x."),
    ))

    val all = listOf(FOUR_ON_FLOOR, BUILD_SNARE_ROLL, BHANGRA_DHOL, BREAKBEAT, HALF_TIME, TRIBAL)
    fun byName(name: String): Pattern? = all.firstOrNull { it.name.equals(name, true) || it.name.lowercase().startsWith(name.lowercase()) }

    /** Pattern that suits a tempo/genre context. */
    fun suggest(bpm: Double, genres: List<String>): Pattern {
        val g = genres.joinToString(" ").lowercase()
        return when {
            "bhangra" in g || "punjabi" in g || "bollywood" in g -> BHANGRA_DHOL
            bpm < 100 -> BREAKBEAT
            bpm > 140 -> HALF_TIME
            else -> FOUR_ON_FLOOR
        }
    }
}
