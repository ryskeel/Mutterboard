package com.example.mutterboard.ui.theme

import androidx.compose.ui.graphics.Color

// Mutterboard palette: black, greys and white, like the keycap icon. The
// canvas is a light grey just dark enough to separate the white section cards
// from the page. The coral and peach this replaced were borrowed from Checkr.

// ---- Light (monochrome chrome on a light-grey canvas; cards are white) ----
val BrandPrimaryLight = Color(0xFF1A1A1A)            // near-black — buttons, radio, links, progress
val BrandOnPrimaryLight = Color(0xFFFFFFFF)
val BrandPrimaryContainerLight = Color(0xFFE0E0E0)
val BrandOnPrimaryContainerLight = Color(0xFF1A1A1A)
val BrandSecondaryLight = Color(0xFF424242)
val BrandOnSecondaryLight = Color(0xFFFFFFFF)
val BrandSecondaryContainerLight = Color(0xFFE6E6E6)
val BrandOnSecondaryContainerLight = Color(0xFF1A1A1A)
val BrandTertiaryLight = Color(0xFF424242)
val BrandOnTertiaryLight = Color(0xFFFFFFFF)
val BrandTertiaryContainerLight = Color(0xFFE6E6E6)
val BrandOnTertiaryContainerLight = Color(0xFF1A1A1A)
val BrandBackgroundLight = Color(0xFFF2F2F2)          // light grey canvas
val BrandOnBackgroundLight = Color(0xFF1A1A1A)
val BrandSurfaceLight = Color(0xFFF2F2F2)
val BrandOnSurfaceLight = Color(0xFF1A1A1A)
val BrandSurfaceVariantLight = Color(0xFFEBEBEB)      // neutral light gray
val BrandOnSurfaceVariantLight = Color(0xFF5A5A5A)    // neutral gray (subtitles ~6:1 on white)
val BrandSurfaceContainerLowestLight = Color(0xFFFFFFFF)  // cards
val BrandSurfaceContainerLowLight = Color(0xFFFFFFFF)
val BrandSurfaceContainerLight = Color(0xFFFFFFFF)    // cards = white
val BrandSurfaceContainerHighLight = Color(0xFFEEEEEE)
val BrandSurfaceContainerHighestLight = Color(0xFFE8E8E8)
val BrandOutlineLight = Color(0xFF2E2E2E)             // visible outlined-button / badge ring
val BrandOutlineVariantLight = Color(0xFFE0E0E0)      // hairline dividers on white
val BrandErrorLight = Color(0xFFBA1A1A)
val BrandOnErrorLight = Color(0xFFFFFFFF)
val BrandErrorContainerLight = Color(0xFFFFDAD6)
val BrandOnErrorContainerLight = Color(0xFF410002)
val BrandInversePrimaryLight = Color(0xFFB0B0B0)

// ---- Dark ----
val BrandPrimaryDark = Color(0xFFE6E6E6)             // light neutral — buttons (dark text)
val BrandOnPrimaryDark = Color(0xFF1A1A1A)
val BrandPrimaryContainerDark = Color(0xFF3A3A3A)
val BrandOnPrimaryContainerDark = Color(0xFFE6E6E6)
val BrandSecondaryDark = Color(0xFFC8C8C8)
val BrandOnSecondaryDark = Color(0xFF1A1A1A)
val BrandSecondaryContainerDark = Color(0xFF3A3A3A)
val BrandOnSecondaryContainerDark = Color(0xFFE6E6E6)
val BrandTertiaryDark = Color(0xFFC8C8C8)
val BrandOnTertiaryDark = Color(0xFF1A1A1A)
val BrandTertiaryContainerDark = Color(0xFF3A3A3A)
val BrandOnTertiaryContainerDark = Color(0xFFE6E6E6)
val BrandBackgroundDark = Color(0xFF121212)          // charcoal
val BrandOnBackgroundDark = Color(0xFFE6E6E6)
val BrandSurfaceDark = Color(0xFF121212)
val BrandOnSurfaceDark = Color(0xFFE6E6E6)
val BrandSurfaceVariantDark = Color(0xFF3A3A3A)
val BrandOnSurfaceVariantDark = Color(0xFFBDBDBD)
val BrandSurfaceContainerLowestDark = Color(0xFF0D0D0D)
val BrandSurfaceContainerLowDark = Color(0xFF1A1A1A)
val BrandSurfaceContainerDark = Color(0xFF212121)    // dark grey (cards)
val BrandSurfaceContainerHighDark = Color(0xFF2B2B2B)
val BrandSurfaceContainerHighestDark = Color(0xFF363636)
val BrandOutlineDark = Color(0xFF8C8C8C)
val BrandOutlineVariantDark = Color(0xFF424242)
val BrandErrorDark = Color(0xFFFFB4AB)
val BrandOnErrorDark = Color(0xFF690005)
val BrandErrorContainerDark = Color(0xFF93000A)
val BrandOnErrorContainerDark = Color(0xFFFFDAD6)
val BrandInversePrimaryDark = Color(0xFF3A3A3A)

// "Ready/done" accent — monochrome to match the black-and-white chrome (done
// badges, checkmarks, "Setup complete"). Theme-aware so it reads on both
// surfaces. Used directly in MainActivity.
val SuccessLight = Color(0xFF1A1A1A)
val OnSuccessLight = Color(0xFFFFFFFF)
val SuccessDark = Color(0xFFE6E6E6)
val OnSuccessDark = Color(0xFF1A1A1A)

// "Accent pill" used for confirmation chips and vocabulary badges
// (API-key-saved, model-downloaded, custom-word tags). Theme-aware so the text
// stays readable: a light grey pill with dark content on light, a mid grey pill
// with light content on dark. Used directly in MainActivity.
val AccentContainerLight = Color(0xFFE6E6E6)   // light grey pill
val OnAccentContainerLight = Color(0xFF1A1A1A) // near-black content
val AccentContainerDark = Color(0xFF3A3A3A)    // mid grey pill, lifts off cards
val OnAccentContainerDark = Color(0xFFE6E6E6)  // light grey content
