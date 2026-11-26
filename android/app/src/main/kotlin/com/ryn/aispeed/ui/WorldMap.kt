package com.ryn.aispeed.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

data class MapMarker(val countryCode: String, val active: Boolean)

/** Country centre points (longitude, latitude) used to place server markers. */
object CountryGeo {
    private val POINTS: Map<String, Pair<Double, Double>> = mapOf(
        "US" to (-98.0 to 39.0), "CA" to (-100.0 to 57.0), "MX" to (-102.0 to 23.0), "BR" to (-52.0 to -10.0),
        "AR" to (-64.0 to -34.0), "CL" to (-71.0 to -33.0), "CO" to (-74.0 to 4.0),
        "GB" to (-2.0 to 54.0), "IE" to (-8.0 to 53.0), "FR" to (2.0 to 46.0), "DE" to (10.0 to 51.0),
        "NL" to (5.0 to 52.0), "BE" to (4.0 to 50.5), "LU" to (6.1 to 49.8), "CH" to (8.0 to 47.0),
        "AT" to (14.0 to 47.5), "IT" to (12.0 to 42.0), "ES" to (-4.0 to 40.0), "PT" to (-8.0 to 39.5),
        "SE" to (16.0 to 62.0), "NO" to (9.0 to 61.0), "FI" to (26.0 to 64.0), "DK" to (10.0 to 56.0),
        "IS" to (-19.0 to 65.0), "PL" to (19.0 to 52.0), "CZ" to (15.0 to 49.8), "SK" to (19.5 to 48.7),
        "HU" to (19.0 to 47.0), "RO" to (25.0 to 46.0), "BG" to (25.0 to 42.7), "GR" to (22.0 to 39.0),
        "RS" to (21.0 to 44.0), "HR" to (16.0 to 45.1), "SI" to (14.8 to 46.1), "AL" to (20.0 to 41.0),
        "MD" to (28.5 to 47.2), "UA" to (31.0 to 49.0), "LV" to (25.0 to 57.0), "LT" to (24.0 to 55.3),
        "EE" to (25.5 to 58.7), "CY" to (33.0 to 35.0), "TR" to (35.0 to 39.0),
        "AE" to (54.0 to 24.0), "SA" to (45.0 to 24.0), "EG" to (30.0 to 27.0), "ZA" to (24.0 to -29.0),
        "IN" to (78.0 to 22.0), "JP" to (138.0 to 36.0), "KR" to (128.0 to 36.0), "HK" to (114.2 to 22.3),
        "TW" to (121.0 to 23.7), "SG" to (103.8 to 1.35), "MY" to (102.0 to 4.0), "TH" to (101.0 to 15.0),
        "ID" to (113.0 to -2.0), "PH" to (122.0 to 12.0), "VN" to (106.0 to 16.0),
        "AU" to (134.0 to -25.0), "NZ" to (172.0 to -41.0)
    )

    fun of(code: String): Pair<Double, Double>? = POINTS[code]
}

// Simplified continent outlines (longitude, latitude). Stylised on purpose: this is a status visual, not an atlas.
private val LAND: List<List<Pair<Double, Double>>> = listOf(
    // North America
    listOf(
        -168.0 to 66.0, -156.0 to 71.0, -141.0 to 70.0, -125.0 to 70.0, -95.0 to 72.0, -82.0 to 73.0,
        -62.0 to 66.0, -56.0 to 52.0, -67.0 to 45.0, -70.0 to 41.0, -76.0 to 35.0, -81.0 to 31.0,
        -80.0 to 25.0, -84.0 to 30.0, -90.0 to 29.0, -97.0 to 26.0, -97.0 to 21.0, -88.0 to 21.0,
        -83.0 to 15.0, -78.0 to 9.0, -80.0 to 7.0, -86.0 to 10.0, -92.0 to 15.0, -105.0 to 20.0,
        -110.0 to 24.0, -115.0 to 30.0, -118.0 to 34.0, -124.0 to 40.0, -124.0 to 48.0, -130.0 to 55.0,
        -140.0 to 60.0, -152.0 to 59.0, -163.0 to 55.0, -165.0 to 62.0
    ),
    // South America
    listOf(
        -78.0 to 8.0, -72.0 to 12.0, -62.0 to 10.0, -52.0 to 5.0, -50.0 to 0.0, -35.0 to -5.0,
        -39.0 to -14.0, -41.0 to -22.0, -48.0 to -26.0, -58.0 to -35.0, -62.0 to -40.0, -66.0 to -47.0,
        -69.0 to -52.0, -74.0 to -50.0, -73.0 to -40.0, -71.0 to -30.0, -70.0 to -18.0, -76.0 to -14.0,
        -81.0 to -5.0, -80.0 to 0.0
    ),
    // Europe + Asia (+ Arabia)
    listOf(
        -9.0 to 37.0, -9.0 to 43.0, -2.0 to 44.0, -4.0 to 48.0, 2.0 to 51.0, 5.0 to 53.0, 9.0 to 55.0,
        8.0 to 58.0, 11.0 to 59.0, 5.0 to 62.0, 14.0 to 68.0, 25.0 to 71.0, 40.0 to 68.0, 60.0 to 69.0,
        70.0 to 73.0, 90.0 to 76.0, 110.0 to 77.0, 130.0 to 72.0, 150.0 to 70.0, 170.0 to 70.0,
        180.0 to 66.0, 165.0 to 60.0, 160.0 to 55.0, 155.0 to 50.0, 142.0 to 53.0, 140.0 to 48.0,
        135.0 to 43.0, 130.0 to 42.0, 127.0 to 35.0, 122.0 to 40.0, 121.0 to 31.0, 119.0 to 25.0,
        110.0 to 20.0, 106.0 to 10.0, 100.0 to 13.0, 103.0 to 2.0, 98.0 to 8.0, 97.0 to 17.0,
        92.0 to 22.0, 88.0 to 22.0, 80.0 to 15.0, 78.0 to 8.0, 73.0 to 18.0, 70.0 to 22.0,
        67.0 to 25.0, 57.0 to 25.0, 57.0 to 27.0, 50.0 to 30.0, 48.0 to 29.0, 52.0 to 24.0,
        56.0 to 26.0, 60.0 to 22.0, 56.0 to 17.0, 45.0 to 13.0, 43.0 to 15.0, 39.0 to 21.0,
        35.0 to 28.0, 34.0 to 31.0, 36.0 to 36.0, 30.0 to 36.0, 26.0 to 37.0, 23.0 to 36.0,
        21.0 to 40.0, 19.0 to 42.0, 14.0 to 45.0, 12.0 to 45.0, 16.0 to 40.0, 13.0 to 38.0,
        9.0 to 44.0, 3.0 to 43.0, -1.0 to 38.0
    ),
    // Africa
    listOf(
        -17.0 to 21.0, -10.0 to 30.0, -6.0 to 36.0, 10.0 to 37.0, 11.0 to 33.0, 20.0 to 32.0,
        32.0 to 31.0, 35.0 to 27.0, 43.0 to 12.0, 51.0 to 12.0, 41.0 to -2.0, 40.0 to -15.0,
        35.0 to -25.0, 27.0 to -34.0, 19.0 to -35.0, 12.0 to -18.0, 14.0 to -10.0, 9.0 to -1.0,
        9.0 to 4.0, -8.0 to 4.0, -13.0 to 8.0, -17.0 to 14.0
    ),
    // Australia
    listOf(
        114.0 to -22.0, 122.0 to -18.0, 131.0 to -12.0, 137.0 to -12.0, 142.0 to -11.0, 146.0 to -19.0,
        153.0 to -26.0, 150.0 to -37.0, 141.0 to -38.0, 135.0 to -34.0, 129.0 to -32.0, 115.0 to -34.0
    ),
    // Greenland
    listOf(-55.0 to 60.0, -45.0 to 60.0, -20.0 to 70.0, -20.0 to 82.0, -60.0 to 82.0, -70.0 to 76.0),
    // British Isles
    listOf(-5.0 to 50.0, 1.0 to 51.0, 2.0 to 55.0, -3.0 to 59.0, -6.0 to 56.0),
    // Japan
    listOf(130.0 to 31.0, 135.0 to 34.0, 141.0 to 38.0, 142.0 to 45.0, 140.0 to 41.0, 133.0 to 35.0),
    // Indonesia
    listOf(95.0 to 5.0, 106.0 to -6.0, 115.0 to -8.0, 110.0 to -1.0, 100.0 to 2.0)
)

@Composable
fun WorldMap(markers: List<MapMarker>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Equirectangular projection, cropped to latitudes 84N..56S where all land is.
        fun px(lon: Double): Float = ((lon + 180.0) / 360.0 * w).toFloat()
        fun py(lat: Double): Float = ((84.0 - lat) / 140.0 * h).toFloat()

        val grid = Color(0xFF16242A)
        for (i in 1..5) {
            drawLine(grid, Offset(0f, h * i / 6f), Offset(w, h * i / 6f), strokeWidth = 1f)
        }
        for (i in 1..11) {
            drawLine(grid, Offset(w * i / 12f, 0f), Offset(w * i / 12f, h), strokeWidth = 1f)
        }

        for (polygon in LAND) {
            val path = Path()
            polygon.forEachIndexed { index, (lon, lat) ->
                if (index == 0) path.moveTo(px(lon), py(lat)) else path.lineTo(px(lon), py(lat))
            }
            path.close()
            drawPath(path, color = Color(0xFF12302A))
            drawPath(path, color = Color(0xFF1F5A44), style = Stroke(width = 1.5f))
        }

        for (marker in markers) {
            val geo = CountryGeo.of(marker.countryCode) ?: continue
            val center = Offset(px(geo.first), py(geo.second))
            if (marker.active) {
                drawCircle(RynColors.Accent.copy(alpha = 0.25f), radius = 20f, center = center)
                drawCircle(RynColors.Accent, radius = 8f, center = center)
            } else {
                drawCircle(RynColors.Muted.copy(alpha = 0.9f), radius = 4.5f, center = center)
            }
        }
    }
}
