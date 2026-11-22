package com.github.damontecres.wholphin.jellytip.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.theme.JellytipFontFamily

/**
 * "Skip intro" / "Skip recap" / "Skip credits" (parity §5, the library player's skip-segment button), bottom-right in the safe
 * area while the overlay is hidden. Drawn "focused" (paper on ink, like the Up next card's badge): OK skips, Back dismisses, both
 * handled by the page's key handler so seeking with Left / Right keeps working underneath.
 */
@Composable
internal fun IptvSkipButton(
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .background(Color(0xFFF2F2F0), RoundedCornerShape(28.dp))
                .padding(horizontal = 24.dp, vertical = 14.dp)
                .testTag("iptv-skip"),
    ) {
        Text(label, color = Color(0xFF0A0A0A), fontFamily = JellytipFontFamily, fontWeight = FontWeight.Medium, fontSize = 18.sp)
        Text("OK", color = Color(0xFF0A0A0A).copy(alpha = 0.55f), fontFamily = JellytipFontFamily, fontSize = 13.sp)
    }
}
