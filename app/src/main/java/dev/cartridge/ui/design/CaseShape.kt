package dev.cartridge.ui.design

import dev.cartridge.domain.Media

/** What a game's case is made of, which decides its edges and how light falls on it. */
enum class CaseKind {
    /** A cardboard box printed all over: softly bevelled edges, matte. */
    Box,
    /** A plastic keep case or clamshell: a rounded spine, a rim round the cover, a glossy clear sleeve. */
    Keep,
    /** A CD jewel case: a hinge down the left of a clear lid over the booklet. */
    Jewel,
}

/**
 * A case at its real size in millimetres (ADR 0029, as `tools/generate_cases.py` models it): its outside, and for a
 * plastic case the cover sheet under the sleeve, [sheetWidth] by [sheetHeight], [inset] in from the right edge.
 * [shell] is the plastic's colour.
 */
class CaseShape(val kind: CaseKind, val width: Float, val height: Float, val depth: Float,
    val sheetWidth: Float = width, val sheetHeight: Float = height, val inset: Float = 0f, val shell: Int = 0xFF0B0B0C.toInt()) {
    val aspect get() = width / height
}

val Media.shape: CaseShape get() = when (this) {
    Media.JewelCase -> CaseShape(CaseKind.Jewel, 142f, 125f, 10.4f, 120f, 120f, 3f, 0xFF0E0E10.toInt())
    Media.HandheldBox -> CaseShape(CaseKind.Box, 125f, 125f, 22f)
    Media.GbaBoxJapan -> CaseShape(CaseKind.Box, 88f, 136f, 22f)
    Media.GbBoxJapan -> CaseShape(CaseKind.Box, 90f, 120f, 22f)
    Media.GameGearBox -> CaseShape(CaseKind.Box, 130f, 165f, 30f)
    Media.NesBox -> CaseShape(CaseKind.Box, 127f, 184f, 32f)
    Media.FamicomBox -> CaseShape(CaseKind.Box, 133f, 95f, 25f)
    Media.SnesBox -> CaseShape(CaseKind.Box, 184f, 127f, 38f)
    Media.SuperFamicomBox -> CaseShape(CaseKind.Box, 190f, 107f, 31f)
    Media.Clamshell -> CaseShape(CaseKind.Keep, 135f, 190f, 25f, 132f, 187f, 1.5f, 0xFF0C0C0E.toInt())
    Media.SaturnCase -> CaseShape(CaseKind.Keep, 145f, 215f, 22f, 142f, 212f, 1.5f, 0xFF0C0C0E.toInt())
    Media.DvdCase -> CaseShape(CaseKind.Keep, 135f, 190f, 14f, 130f, 183f, 2.5f, 0xFF09090B.toInt())
    Media.GameCubeCase -> CaseShape(CaseKind.Keep, 135f, 186f, 14f, 130f, 180f, 2.5f, 0xFF09090B.toInt())
}
