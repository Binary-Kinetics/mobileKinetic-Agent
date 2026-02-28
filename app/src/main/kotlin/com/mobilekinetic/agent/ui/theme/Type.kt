package com.mobilekinetic.agent.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mobilekinetic.agent.R

val MyriadProCond = FontFamily(
    Font(R.font.myriad_pro_cond, FontWeight.Normal)
)

val MyriadPro = FontFamily(
    Font(R.font.myriad_pro_light, FontWeight.Light),
    Font(R.font.myriad_pro_regular, FontWeight.Normal),
    Font(R.font.myriad_pro_bold, FontWeight.Bold),
    Font(R.font.myriad_pro_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.myriad_pro_black_semi_ext, FontWeight.Black)
)

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = MyriadPro,
        fontWeight = FontWeight.Bold,
        fontSize = 71.sp,
        lineHeight = 80.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = MyriadPro,
        fontWeight = FontWeight.Bold,
        fontSize = 56.sp,
        lineHeight = 65.sp
    ),
    displaySmall = TextStyle(
        fontFamily = MyriadPro,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 55.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = MyriadPro,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 50.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = MyriadPro,
        fontWeight = FontWeight.Bold,
        fontSize = 35.sp,
        lineHeight = 45.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = MyriadPro,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 40.sp
    ),
    titleLarge = TextStyle(
        fontFamily = MyriadPro,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 35.sp
    ),
    titleMedium = TextStyle(
        fontFamily = MyriadPro,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = MyriadPro,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = MyriadPro,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = MyriadPro,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = MyriadPro,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = MyriadPro,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = MyriadPro,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = MyriadPro,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.5.sp
    )
)
