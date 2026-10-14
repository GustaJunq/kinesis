package dev.kinesis.editor.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import dev.kinesis.core.model.EffectCategory
import dev.kinesis.core.model.EffectChoice
import dev.kinesis.core.model.EffectParam
import dev.kinesis.core.model.EffectRegistry
import dev.kinesis.core.model.EffectSpec

/** Effect names live in the core registry (pt-BR and English); the UI picks one by locale. */
@Composable
fun isPortuguese(): Boolean = LocalConfiguration.current.locales[0].language == "pt"

fun EffectCategory.label(pt: Boolean) = if (pt) namePt else nameEn
fun EffectSpec.label(pt: Boolean) = if (pt) namePt else nameEn
fun EffectParam.label(pt: Boolean) = if (pt) labelPt else labelEn
fun EffectChoice.label(pt: Boolean) = if (pt) namePt else nameEn

/** Label of an effect parameter from its property label key `fx.<type>.<param>`. */
fun effectParamLabel(key: String, pt: Boolean): String? {
    val parts = key.split('.')
    if (parts.size != 3 || parts[0] != "fx") return null
    return EffectRegistry.get(parts[1])?.paramOrNull(parts[2])?.label(pt)
}
