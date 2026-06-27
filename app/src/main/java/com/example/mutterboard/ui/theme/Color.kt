package com.example.mutterboard.ui.theme

import androidx.compose.ui.graphics.Color

// Mutterboard brand palette — warm peach / coral / salmon, matching the app icon.
// Button (primary) coral is deliberately deeper than the icon's #F4533E so white
// text clears WCAG AA (≈5:1); soft peaches are used for card/container fills.

// ---- Light ----
val BrandPrimaryLight = Color(0xFFC83E28)            // deep coral — buttons (white text ~5.03:1)
val BrandOnPrimaryLight = Color(0xFFFFFFFF)
val BrandPrimaryContainerLight = Color(0xFFFFDBCF)   // soft peach
val BrandOnPrimaryContainerLight = Color(0xFF3A0B02)
val BrandSecondaryLight = Color(0xFF9A5240)
val BrandOnSecondaryLight = Color(0xFFFFFFFF)
val BrandSecondaryContainerLight = Color(0xFFFFDBCF)
val BrandOnSecondaryContainerLight = Color(0xFF3A0B02)
val BrandTertiaryLight = Color(0xFFB5752A)
val BrandOnTertiaryLight = Color(0xFFFFFFFF)
val BrandTertiaryContainerLight = Color(0xFFFFDEA8)
val BrandOnTertiaryContainerLight = Color(0xFF2A1B00)
val BrandBackgroundLight = Color(0xFFFFF8F4)          // warm off-white
val BrandOnBackgroundLight = Color(0xFF271712)
val BrandSurfaceLight = Color(0xFFFFF8F4)
val BrandOnSurfaceLight = Color(0xFF271712)
val BrandSurfaceVariantLight = Color(0xFFF6DDD3)      // light peach (cards)
val BrandOnSurfaceVariantLight = Color(0xFF6E5C56)    // muted warm gray (~5:1 on peach)
val BrandSurfaceContainerLowestLight = Color(0xFFFFFFFF)
val BrandSurfaceContainerLowLight = Color(0xFFFEEEE8)
val BrandSurfaceContainerLight = Color(0xFFFBE7DE)    // light peach (cards)
val BrandSurfaceContainerHighLight = Color(0xFFF6E0D6)
val BrandSurfaceContainerHighestLight = Color(0xFFF1DACE)
val BrandOutlineLight = Color(0xFF9C8A82)
val BrandOutlineVariantLight = Color(0xFFE6CDC3)
val BrandErrorLight = Color(0xFFBA1A1A)
val BrandOnErrorLight = Color(0xFFFFFFFF)
val BrandErrorContainerLight = Color(0xFFFFDAD6)
val BrandOnErrorContainerLight = Color(0xFF410002)
val BrandInversePrimaryLight = Color(0xFFFFB59C)

// ---- Dark ----
val BrandPrimaryDark = Color(0xFFFFB59C)             // light peach — buttons (dark text)
val BrandOnPrimaryDark = Color(0xFF561F0F)
val BrandPrimaryContainerDark = Color(0xFF7A2E1A)
val BrandOnPrimaryContainerDark = Color(0xFFFFDBCF)
val BrandSecondaryDark = Color(0xFFE7BCAC)
val BrandOnSecondaryDark = Color(0xFF442A1F)
val BrandSecondaryContainerDark = Color(0xFF5D4033)
val BrandOnSecondaryContainerDark = Color(0xFFFFDBCF)
val BrandTertiaryDark = Color(0xFFE8C08D)
val BrandOnTertiaryDark = Color(0xFF412D04)
val BrandTertiaryContainerDark = Color(0xFF5B431B)
val BrandOnTertiaryContainerDark = Color(0xFFFFDEA8)
val BrandBackgroundDark = Color(0xFF1A1411)          // warm charcoal
val BrandOnBackgroundDark = Color(0xFFECE0DB)
val BrandSurfaceDark = Color(0xFF1A1411)
val BrandOnSurfaceDark = Color(0xFFECE0DB)
val BrandSurfaceVariantDark = Color(0xFF4A3A33)
val BrandOnSurfaceVariantDark = Color(0xFFD8C2B8)
val BrandSurfaceContainerLowestDark = Color(0xFF140F0D)
val BrandSurfaceContainerLowDark = Color(0xFF221A16)
val BrandSurfaceContainerDark = Color(0xFF2A211D)    // dark peach-brown (cards)
val BrandSurfaceContainerHighDark = Color(0xFF352B26)
val BrandSurfaceContainerHighestDark = Color(0xFF403530)
val BrandOutlineDark = Color(0xFFA08C84)
val BrandOutlineVariantDark = Color(0xFF52423B)
val BrandErrorDark = Color(0xFFFFB4AB)
val BrandOnErrorDark = Color(0xFF690005)
val BrandErrorContainerDark = Color(0xFF93000A)
val BrandOnErrorContainerDark = Color(0xFFFFDAD6)
val BrandInversePrimaryDark = Color(0xFFC83E28)

// Success accent (semantic, kept green for "ready/done"); theme-aware so it
// reads on both light and dark surfaces. Used directly in MainActivity.
val SuccessLight = Color(0xFF2E7D32)
val OnSuccessLight = Color(0xFFFFFFFF)
val SuccessDark = Color(0xFF7BD88F)
val OnSuccessDark = Color(0xFF06320F)
