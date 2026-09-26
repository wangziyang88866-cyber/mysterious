package com.mysterious.client;

import com.mysterious.encounter.EncounterPhase;
import com.mysterious.mysterious;
import com.mysterious.network.ClientEncounterStateCache;
import com.mysterious.network.EncounterSnapshotPayload;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.Comparator;
import java.util.UUID;

/** Subtle phase tint and edge pressure built entirely from vanilla GUI primitives. */
@EventBusSubscriber(modid = mysterious.MODID, value = Dist.CLIENT)
public final class EncounterScreenOverlay {
    private static final float FADE_IN_SPEED = 4.5F;
    private static final float FADE_OUT_SPEED = 3.2F;
    private static final float PHASE_BLEND_SPEED = 3.8F;
    private static final float FLASH_DURATION_SECONDS = 1.5F;
    private static UUID lastEncounterId;
    private static EncounterPhase lastPhase;
    private static long lastFrameMillis;
    private static float visibility;
    private static float red;
    private static float green;
    private static float blue;
    private static float baseAlpha;
    private static float pulseAmplitude;
    private static float phaseFlashAge = FLASH_DURATION_SECONDS;
    private static boolean colorInitialized;

    private EncounterScreenOverlay() {
    }

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            resetAnimation();
            return;
        }
        long now = Util.getMillis();
        float deltaSeconds = lastFrameMillis == 0L
                ? 0.0F : Math.clamp((now - lastFrameMillis) / 1000.0F, 0.0F, 0.1F);
        lastFrameMillis = now;
        EncounterSnapshotPayload snapshot = ClientEncounterStateCache.instance().snapshots().stream()
                .filter(value -> !value.lifecycle().isTerminal())
                .max(Comparator.comparingInt(value -> value.phase().ordinal()))
                .orElse(null);
        if (snapshot != null
                && (!snapshot.encounterId().equals(lastEncounterId) || snapshot.phase() != lastPhase)) {
            lastEncounterId = snapshot.encounterId();
            lastPhase = snapshot.phase();
            phaseFlashAge = 0.0F;
        }

        float targetVisibility = snapshot == null ? 0.0F : 1.0F;
        float visibilityBlend = exponentialBlend(deltaSeconds,
                targetVisibility > visibility ? FADE_IN_SPEED : FADE_OUT_SPEED);
        visibility += (targetVisibility - visibility) * visibilityBlend;
        EncounterPhase displayedPhase = snapshot == null ? lastPhase : snapshot.phase();
        if (displayedPhase == null || (snapshot == null && visibility < 0.005F)) {
            if (snapshot == null) {
                visibility = 0.0F;
                lastEncounterId = null;
                lastPhase = null;
                colorInitialized = false;
            }
            return;
        }

        int targetRgb = colorFor(displayedPhase);
        float targetRed = (targetRgb >> 16) & 0xFF;
        float targetGreen = (targetRgb >> 8) & 0xFF;
        float targetBlue = targetRgb & 0xFF;
        float targetBaseAlpha = baseAlphaFor(displayedPhase);
        float targetPulseAmplitude = pulseAmplitudeFor(displayedPhase);
        if (!colorInitialized) {
            red = targetRed;
            green = targetGreen;
            blue = targetBlue;
            baseAlpha = targetBaseAlpha;
            pulseAmplitude = targetPulseAmplitude;
            colorInitialized = true;
        } else {
            float phaseBlend = exponentialBlend(deltaSeconds, PHASE_BLEND_SPEED);
            red += (targetRed - red) * phaseBlend;
            green += (targetGreen - green) * phaseBlend;
            blue += (targetBlue - blue) * phaseBlend;
            baseAlpha += (targetBaseAlpha - baseAlpha) * phaseBlend;
            pulseAmplitude += (targetPulseAmplitude - pulseAmplitude) * phaseBlend;
        }
        phaseFlashAge = Math.min(FLASH_DURATION_SECONDS, phaseFlashAge + deltaSeconds);
        float phaseFlash = phaseFlashAge >= FLASH_DURATION_SECONDS ? 0.0F
                : (float) Math.sin(Math.PI * phaseFlashAge / FLASH_DURATION_SECONDS);
        int rgb = (Math.clamp(Math.round(red), 0, 255) << 16)
                | (Math.clamp(Math.round(green), 0, 255) << 8)
                | Math.clamp(Math.round(blue), 0, 255);
        GuiGraphics graphics = event.getGuiGraphics();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int depth = Math.max(12, Math.min(42, Math.min(width, height) / 7));
        double pulse = (Math.sin(now * 0.0055D) + 1.0D) * 0.5D;
        int alpha = Math.clamp(Math.round((baseAlpha + (float) pulse * pulseAmplitude
                + phaseFlash * 60.0F) * visibility), 0, 180);
        int opaque = argb(alpha, rgb);
        int transparent = argb(0, rgb);

        graphics.fill(0, 0, width, height, argb(Math.round(alpha / 7.0F), rgb));
        graphics.fillGradient(0, 0, width, depth, opaque, transparent);
        graphics.fillGradient(0, height - depth, width, height, transparent, opaque);
        drawSoftSides(graphics, width, height, Math.max(6, depth / 2), alpha, rgb);
        if (displayedPhase == EncounterPhase.PHASE_THREE) {
            int rim = argb(Math.min(190, Math.round((92.0F + phaseFlash * 60.0F
                    + (float) pulse * 44.0F) * visibility)), rgb);
            graphics.fill(0, 0, width, 2, rim);
            graphics.fill(0, height - 2, width, height, rim);
            graphics.fill(0, 0, 2, height, rim);
            graphics.fill(width - 2, 0, width, height, rim);
        }
    }

    private static void drawSoftSides(GuiGraphics graphics, int width, int height,
                                      int sideDepth, int alpha, int rgb) {
        int bands = Math.min(12, sideDepth);
        for (int band = 0; band < bands; band++) {
            int x0 = band * sideDepth / bands;
            int x1 = (band + 1) * sideDepth / bands;
            float distance = (band + 0.5F) / bands;
            int bandAlpha = Math.round(alpha * 0.58F * (1.0F - distance) * (1.0F - distance));
            graphics.fill(x0, 0, x1, height, argb(bandAlpha, rgb));
            graphics.fill(width - x1, 0, width - x0, height, argb(bandAlpha, rgb));
        }
    }

    private static float exponentialBlend(float deltaSeconds, float speed) {
        return 1.0F - (float) Math.exp(-deltaSeconds * speed);
    }

    private static int colorFor(EncounterPhase phase) {
        return switch (phase) {
            case PHASE_ONE -> 0x4A1678;
            case PHASE_TWO -> 0x173A78;
            case PHASE_THREE -> 0x741326;
        };
    }

    private static float baseAlphaFor(EncounterPhase phase) {
        return switch (phase) {
            case PHASE_ONE -> 34.0F;
            case PHASE_TWO -> 46.0F;
            case PHASE_THREE -> 72.0F;
        };
    }

    private static float pulseAmplitudeFor(EncounterPhase phase) {
        return switch (phase) {
            case PHASE_ONE -> 6.0F;
            case PHASE_TWO -> 12.0F;
            case PHASE_THREE -> 30.0F;
        };
    }

    private static void resetAnimation() {
        lastEncounterId = null;
        lastPhase = null;
        lastFrameMillis = 0L;
        visibility = 0.0F;
        phaseFlashAge = FLASH_DURATION_SECONDS;
        colorInitialized = false;
    }

    static int argb(int alpha, int rgb) {
        return (Math.clamp(alpha, 0, 255) << 24) | (rgb & 0x00FFFFFF);
    }
}
