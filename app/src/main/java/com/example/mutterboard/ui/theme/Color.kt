package com.example.mutterboard.ui.theme

import androidx.compose.ui.graphics.Color

// Mutterboard palette. The coral/peach now lives only in the logo mark; the
// settings UI is monochrome (black chrome) on a light-peach canvas with white
// cards, for cleaner contrast and accessibility. The peach background is just
// saturated enough to separate the white section cards from the page.

// ---- Light (monochrome chrome on a light-peach canvas; cards are white) ----
val BrandPrimaryLight = Color(0xFF1A1A1A)            // near-black — buttons, radio, links, progress
val BrandOnPrimaryLight = Color(0xFFFFFFFF)
val BrandPrimaryContainerLight = Color(0xFFE2DEDB)
val BrandOnPrimaryContainerLight = Color(0xFF1A1A1A)
val BrandSecondaryLight = Color(0xFF44403E)
val BrandOnSecondaryLight = Color(0xFFFFFFFF)
val BrandSecondaryContainerLight = Color(0xFFE7E2DF)
val BrandOnSecondaryContainerLight = Color(0xFF1A1A1A)
val BrandTertiaryLight = Color(0xFF44403E)
val BrandOnTertiaryLight = Color(0xFFFFFFFF)
val BrandTertiaryContainerLight = Color(0xFFE7E2DF)
val BrandOnTertiaryContainerLight = Color(0xFF1A1A1A)
val BrandBackgroundLight = Color(0xFFFCF2EB)          // very light peach canvas
val BrandOnBackgroundLight = Color(0xFF1A1A1A)
val BrandSurfaceLight = Color(0xFFFCF2EB)
val BrandOnSurfaceLight = Color(0xFF1A1A1A)
val BrandSurfaceVariantLight = Color(0xFFEDE9E7)      // neutral light gray
val BrandOnSurfaceVariantLight = Color(0xFF5A5A5A)    // neutral gray (subtitles ~6:1 on white)
val BrandSurfaceContainerLowestLight = Color(0xFFFFFFFF)  // cards
val BrandSurfaceContainerLowLight = Color(0xFFFFFFFF)
val BrandSurfaceContainerLight = Color(0xFFFFFFFF)    // cards = white
val BrandSurfaceContainerHighLight = Color(0xFFF3EDE9)
val BrandSurfaceContainerHighestLight = Color(0xFFEDE6E1)
val BrandOutlineLight = Color(0xFF2E2E2E)             // visible outlined-button / badge ring
val BrandOutlineVariantLight = Color(0xFFE2DEDB)      // hairline dividers on white
val BrandErrorLight = Color(0xFFBA1A1A)
val BrandOnErrorLight = Color(0xFFFFFFFF)
val BrandErrorContainerLight = Color(0xFFFFDAD6)
val BrandOnErrorContainerLight = Color(0xFF410002)
val BrandInversePrimaryLight = Color(0xFFB0B0B0)

// ---- Dark ----
val BrandPrimaryDark = Color(0xFFE6E6E6)             // light neutral — buttons (dark text)
val BrandOnPrimaryDark = Color(0xFF1A1A1A)
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

// "Ready/done" accent — monochrome to match the black-and-white chrome (done
// badges, checkmarks, "Setup complete"). Theme-aware so it reads on both
// surfaces. Used directly in MainActivity.
val SuccessLight = Color(0xFF1A1A1A)
val OnSuccessLight = Color(0xFFFFFFFF)
val SuccessDark = Color(0xFFE6E6E6)
val OnSuccessDark = Color(0xFF1A1A1A)
