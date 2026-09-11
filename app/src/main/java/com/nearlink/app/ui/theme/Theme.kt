package com.nearlink.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Tema de NearLink sobre Material 3 Expressive (Material You 3).
 *
 * - En Android 12+ se usa color dinamico (el tema se adapta al fondo de pantalla).
 * - En versiones anteriores se cae a la paleta NearLink.
 * - El movimiento y las formas se toman del esquema "expressive".
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NearLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    typography: Typography = NearLinkTypography,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> darkColorScheme(
            primary = NearLinkPrimaryDark,
            onPrimary = NearLinkOnPrimaryDark,
            primaryContainer = NearLinkPrimaryContainerDark,
            onPrimaryContainer = NearLinkOnPrimaryContainerDark,
            secondary = NearLinkSecondaryDark,
            onSecondary = NearLinkOnSecondaryDark,
            secondaryContainer = NearLinkSecondaryContainerDark,
            onSecondaryContainer = NearLinkOnSecondaryContainerDark,
            tertiary = NearLinkTertiaryDark,
            onTertiary = NearLinkOnTertiaryDark,
            tertiaryContainer = NearLinkTertiaryContainerDark,
            onTertiaryContainer = NearLinkOnTertiaryContainerDark,
            error = NearLinkErrorDark,
            onError = NearLinkOnErrorDark,
            errorContainer = NearLinkErrorContainerDark,
            onErrorContainer = NearLinkOnErrorContainerDark,
            background = NearLinkBackgroundDark,
            onBackground = NearLinkOnBackgroundDark,
            surface = NearLinkSurfaceDark,
            onSurface = NearLinkOnSurfaceDark,
            surfaceVariant = NearLinkSurfaceVariantDark,
            onSurfaceVariant = NearLinkOnSurfaceVariantDark,
            outline = NearLinkOutlineDark,
        )

        else -> lightColorScheme(
            primary = NearLinkPrimary,
            onPrimary = NearLinkOnPrimary,
            primaryContainer = NearLinkPrimaryContainer,
            onPrimaryContainer = NearLinkOnPrimaryContainer,
            secondary = NearLinkSecondary,
            onSecondary = NearLinkOnSecondary,
            secondaryContainer = NearLinkSecondaryContainer,
            onSecondaryContainer = NearLinkOnSecondaryContainer,
            tertiary = NearLinkTertiary,
            onTertiary = NearLinkOnTertiary,
            tertiaryContainer = NearLinkTertiaryContainer,
            onTertiaryContainer = NearLinkOnTertiaryContainer,
            error = NearLinkError,
            onError = NearLinkOnError,
            errorContainer = NearLinkErrorContainer,
            onErrorContainer = NearLinkOnErrorContainer,
            background = NearLinkBackground,
            onBackground = NearLinkOnBackground,
            surface = NearLinkSurface,
            onSurface = NearLinkOnSurface,
            surfaceVariant = NearLinkSurfaceVariant,
            onSurfaceVariant = NearLinkOnSurfaceVariant,
            outline = NearLinkOutline,
        )
    }

    val shapes = Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(22.dp),
        extraLarge = RoundedCornerShape(30.dp),
        largeIncreased = RoundedCornerShape(28.dp),
        extraLargeIncreased = RoundedCornerShape(36.dp),
    )

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = shapes,
        typography = typography,
        content = content,
    )
}
