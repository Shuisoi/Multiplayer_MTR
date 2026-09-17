package org.mtr.mod.render;

/**
 * Render ordering, walked in DECLARATION ORDER by {@link MainRenderer}. New layers are appended at the
 * END so that adding one never renumbers the existing layers (and therefore never silently reorders
 * what is drawn on top of what).
 *
 * <p>{@code EXTERIOR_TRANSLUCENT_DOUBLE} exists because {@code EXTERIOR_TRANSLUCENT} resolves to
 * {@code getEntityTranslucentCull}, which keeps BACK-FACE CULLING - so a translucent quad placed on a
 * vehicle is only visible from the side its winding faces, and "draw it twice" does not make it
 * two-sided. This layer resolves to {@code getEntityTranslucent} (no culling) instead. MMTR uses it for
 * the windshield's precipitation layer, which has to be visible from inside AND outside the cab.</p>
 */
public enum QueuedRenderLayer {LIGHT, INTERIOR, EXTERIOR, LIGHT_TRANSLUCENT, INTERIOR_TRANSLUCENT, EXTERIOR_TRANSLUCENT, LIGHT_2, LINES, TEXT, EXTERIOR_TRANSLUCENT_DOUBLE}
