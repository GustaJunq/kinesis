package dev.kinesis.editor.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.kinesis.core.anim.EasingPreset
import dev.kinesis.core.model.BlendMode
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.LightKind
import dev.kinesis.core.model.MaskMode
import dev.kinesis.core.model.MatteMode
import dev.kinesis.core.model.Primitive
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.core.model.TextAnimatorType
import dev.kinesis.core.model.TransitionType
import dev.kinesis.editor.R

/** String resource for a property label key (see LayerProps). */
fun propLabelRes(key: String): Int = when (key) {
    "transform.position", "transform3d.position", "camera.position", "light.position", "mask.position" -> R.string.prop_position
    "transform.scale", "transform3d.scale" -> R.string.prop_scale
    "transform.rotation", "transform3d.rotation" -> R.string.prop_rotation
    "transform.opacity", "transform3d.opacity", "mask.opacity", "effect.opacity" -> R.string.opacity
    "transform.anchor", "transform3d.anchor" -> R.string.prop_anchor
    "text.fontSize" -> R.string.prop_font_size
    "text.color", "material.color", "light.color", "effect.color" -> R.string.color
    "text.letterSpacing" -> R.string.prop_letter_spacing
    "stroke.color" -> R.string.prop_stroke_color
    "stroke.width" -> R.string.prop_stroke_width
    "fill.color" -> R.string.prop_fill_color
    "shape.size", "mask.size" -> R.string.prop_size
    "shape.roundness", "mask.roundness" -> R.string.prop_roundness
    "shape.sides" -> R.string.prop_sides
    "shape.innerRatio" -> R.string.prop_inner_radius
    "shape.trim.start" -> R.string.prop_trim_start
    "shape.trim.end" -> R.string.prop_trim_end
    "shape.trim.offset" -> R.string.prop_trim_offset
    "media.volume" -> R.string.prop_volume
    "object.size" -> R.string.prop_size
    "material.metallic" -> R.string.prop_metallic
    "material.roughness" -> R.string.prop_roughness
    "material.emissive" -> R.string.prop_emissive
    "camera.target", "light.target" -> R.string.prop_target
    "camera.fov" -> R.string.prop_fov
    "light.intensity", "effect.intensity" -> R.string.prop_intensity
    "light.radius", "effect.radius" -> R.string.prop_radius
    "mask.feather" -> R.string.prop_feather
    "effect.brightness" -> R.string.prop_brightness
    "effect.contrast" -> R.string.prop_contrast
    "effect.saturation" -> R.string.prop_saturation
    "effect.hue" -> R.string.prop_hue
    "effect.amount" -> R.string.prop_amount
    "effect.angle" -> R.string.prop_angle
    "effect.distance" -> R.string.prop_distance
    "effect.softness" -> R.string.prop_softness
    "effect.size" -> R.string.prop_size
    else -> R.string.prop_value
}

fun sectionTitleRes(id: String): Int = when {
    id == "transform" || id == "transform3d" -> R.string.section_transform
    id == "text" -> R.string.section_text
    id == "shape" -> R.string.section_shape
    id == "trim" -> R.string.section_trim
    id == "audio" -> R.string.section_audio
    id == "object3d" || id == "model3d" -> R.string.section_object
    id == "camera" -> R.string.layer_camera
    id == "light" -> R.string.layer_light
    id.startsWith("effects.") -> R.string.section_effects
    id.startsWith("masks.") -> R.string.section_masks
    else -> R.string.prop_value
}

fun layerTypeRes(type: LayerType): Int = when (type) {
    LayerType.TEXT -> R.string.layer_text
    LayerType.SHAPE -> R.string.layer_shape
    LayerType.IMAGE -> R.string.layer_image
    LayerType.VIDEO -> R.string.layer_video
    LayerType.AUDIO -> R.string.layer_audio
    LayerType.GROUP -> R.string.layer_group
    LayerType.OBJECT3D -> R.string.layer_object3d
    LayerType.MODEL3D -> R.string.layer_model3d
    LayerType.CAMERA -> R.string.layer_camera
    LayerType.LIGHT -> R.string.layer_light
}

fun effectNameRes(e: Effect): Int = when (e) {
    is Effect.Blur -> R.string.effect_blur
    is Effect.ColorAdjust -> R.string.effect_color_adjust
    is Effect.Tint -> R.string.effect_tint
    is Effect.DropShadow -> R.string.effect_drop_shadow
    is Effect.Glow -> R.string.effect_glow
    is Effect.Invert -> R.string.effect_invert
    is Effect.Pixelate -> R.string.effect_pixelate
    is Effect.ChromaticAberration -> R.string.effect_chromatic
    is Effect.Vignette -> R.string.effect_vignette
}

val ALL_EFFECTS: List<() -> Effect> = listOf(
    { Effect.Blur() }, { Effect.ColorAdjust() }, { Effect.Tint() }, { Effect.DropShadow() }, { Effect.Glow() },
    { Effect.Invert() }, { Effect.Pixelate() }, { Effect.ChromaticAberration() }, { Effect.Vignette() },
)

fun shapeKindRes(k: ShapeKind): Int = when (k) {
    ShapeKind.RECTANGLE -> R.string.shape_rectangle
    ShapeKind.ELLIPSE -> R.string.shape_ellipse
    ShapeKind.POLYGON -> R.string.shape_polygon
    ShapeKind.STAR -> R.string.shape_star
    ShapeKind.PATH -> R.string.shape_path
}

fun primitiveRes(p: Primitive): Int = when (p) {
    Primitive.CUBE -> R.string.prim_cube
    Primitive.SPHERE -> R.string.prim_sphere
    Primitive.CYLINDER -> R.string.prim_cylinder
    Primitive.CONE -> R.string.prim_cone
    Primitive.TORUS -> R.string.prim_torus
    Primitive.PLANE -> R.string.prim_plane
    Primitive.EMPTY -> R.string.prim_empty
}

fun lightKindRes(k: LightKind): Int = when (k) {
    LightKind.AMBIENT -> R.string.light_ambient
    LightKind.DIRECTIONAL -> R.string.light_directional
    LightKind.POINT -> R.string.light_point
}

fun blendRes(b: BlendMode): Int = when (b) {
    BlendMode.NORMAL -> R.string.blend_normal
    BlendMode.ADD -> R.string.blend_add
    BlendMode.MULTIPLY -> R.string.blend_multiply
    BlendMode.SCREEN -> R.string.blend_screen
}

fun maskModeRes(m: MaskMode): Int = when (m) {
    MaskMode.ADD -> R.string.mask_add
    MaskMode.SUBTRACT -> R.string.mask_subtract
    MaskMode.INTERSECT -> R.string.mask_intersect
}

fun matteModeRes(m: MatteMode): Int = when (m) {
    MatteMode.ALPHA -> R.string.matte_alpha
    MatteMode.ALPHA_INVERTED -> R.string.matte_alpha_inverted
    MatteMode.LUMA -> R.string.matte_luma
    MatteMode.LUMA_INVERTED -> R.string.matte_luma_inverted
}

fun transitionRes(t: TransitionType): Int = when (t) {
    TransitionType.FADE -> R.string.tr_fade
    TransitionType.SLIDE_LEFT -> R.string.tr_slide_left
    TransitionType.SLIDE_RIGHT -> R.string.tr_slide_right
    TransitionType.SLIDE_UP -> R.string.tr_slide_up
    TransitionType.SLIDE_DOWN -> R.string.tr_slide_down
    TransitionType.ZOOM_IN -> R.string.tr_zoom_in
    TransitionType.ZOOM_OUT -> R.string.tr_zoom_out
    TransitionType.SPIN -> R.string.tr_spin
    TransitionType.BLUR -> R.string.tr_blur
    TransitionType.WIPE_LEFT -> R.string.tr_wipe_left
    TransitionType.WIPE_RIGHT -> R.string.tr_wipe_right
}

fun textAnimatorRes(t: TextAnimatorType): Int = when (t) {
    TextAnimatorType.TYPEWRITER -> R.string.ta_typewriter
    TextAnimatorType.FADE -> R.string.tr_fade
    TextAnimatorType.SLIDE_UP -> R.string.tr_slide_up
    TextAnimatorType.SLIDE_DOWN -> R.string.tr_slide_down
    TextAnimatorType.SLIDE_LEFT -> R.string.tr_slide_left
    TextAnimatorType.SLIDE_RIGHT -> R.string.tr_slide_right
    TextAnimatorType.SCALE -> R.string.ta_scale
    TextAnimatorType.ROTATE -> R.string.ta_rotate
    TextAnimatorType.WAVE -> R.string.ta_wave
}

/** Readable easing names for the curve editor (the ids are already descriptive). */
fun easingLabel(p: EasingPreset): String = p.id.replaceFirstChar { it.uppercase() }

@Composable
fun propLabel(key: String): String = stringResource(propLabelRes(key))
