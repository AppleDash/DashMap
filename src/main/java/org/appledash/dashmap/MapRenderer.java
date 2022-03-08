package org.appledash.dashmap;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector3f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.lwjgl.opengl.GL11;

public class MapRenderer {
    private static final int TEXT_COLOUR = 0xFFFFFFFF;    /* Colour of text rendered on the map */
    private static final int BORDER_COLOUR = 0xFF34373E;  /* Colour of the border around the map */
    private static final int HEADING_COLOUR = 0xFF000000; /* Colour of the heading indicator triangle on the map */
    private static final int CIRCLE_RADIUS = 40;          /* Radius of the main map circle */
    private static final int BORDER_THICKNESS = 2;        /* Thickness of the border around the main map circle */
    private boolean initialized;

    public void renderMap(Minecraft mc, MapManager mapManager, PoseStack matrices) {
        this.initIfRequired(mc, mapManager);

        /* Texture is only uploaded if it has changed */
        mapManager.uploadTexture();

        float viewportWidth = mc.getWindow().getGuiScaledWidth();

        matrices.pushPose();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        /* Translate to the middle of where the map circle should be on screen */
        matrices.translate(viewportWidth - CIRCLE_RADIUS - 10, 10 + CIRCLE_RADIUS, 0);

        /* Draw the border circle (map will be rendered on top of it) */
        this.fillCircle(matrices, CIRCLE_RADIUS + BORDER_THICKNESS, 100, BORDER_COLOUR);

        /* Set up the stencil buffer appropriately. The next thing we render with these settings will set the pixels of the
         * stencil buffer to 1 wherever there was a pixel rendered.
         */
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        RenderSystem.stencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
        RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        RenderSystem.stencilMask(0xFF);

        /* Don't want to actually render to the screen, just the stencil buffer. */
        RenderSystem.depthMask(false);
        RenderSystem.colorMask(false, false, false, false);

        RenderSystem.clear(GL11.GL_STENCIL_BUFFER_BIT, true);

        /* Render a circle into the stencil buffer, forming a mask through which the minimap texture will show. */
        this.fillCircle(matrices, CIRCLE_RADIUS, 100, 0xFFFFFFFF);

        /* Revert the settings we made earlier, so that subsequent render calls don't affect the stencil buffer and instead render on the screen. */
        RenderSystem.stencilFunc(GL11.GL_EQUAL, 1, 0xFF);
        RenderSystem.stencilMask(0x00);
        RenderSystem.depthMask(true);
        RenderSystem.colorMask(true, true, true, true);

        matrices.pushPose();
        matrices.mulPose(Vector3f.ZP.rotationDegrees(180.0F - mc.player.yHeadRot));

        /* Offset the map based on the player's position relative to the upper-left chunk of the map, so the center of the image on screen is where the player is. */
        double deltaX = mc.player.getX() - mapManager.getUpperLeftPosition().getMinBlockX();
        double deltaZ = mc.player.getZ() - mapManager.getUpperLeftPosition().getMinBlockZ();

        matrices.translate(-deltaX, -deltaZ, 0);

        /* Now render the actual map to the screen. Since GL_STENCIL_TEST is on, only pixels where the
         * stencil buffer is 1 will actually end up on the screen. End result: Circular map!
         */
        final NativeImage image = mapManager.getImage();
        RenderSystem.setShaderTexture(0, mapManager.getTextureLocation());
        GuiComponent.blit(matrices, 0, 0, 0, 0, image.getWidth(), image.getHeight(), image.getWidth(), image.getHeight());

        matrices.popPose();

        /* Time to render outside the circle, so we don't want to stencil test anymore. */
        GL11.glDisable(GL11.GL_STENCIL_TEST);

        /* Render the N, E, S, W indicators */
        final float westAngle = (float) Math.toRadians(-mc.player.yHeadRot);
        final float northAngle = (float) Math.toRadians(-mc.player.yHeadRot + 90);
        final float eastAngle = (float) Math.toRadians(-mc.player.yHeadRot + 180);
        final float southAngle = (float) Math.toRadians(-mc.player.yHeadRot + 270);

        this.drawStringOnCircle(matrices, mc.font, "W", CIRCLE_RADIUS - 1, westAngle, TEXT_COLOUR);
        this.drawStringOnCircle(matrices, mc.font, "N", CIRCLE_RADIUS - 1, northAngle, TEXT_COLOUR);
        this.drawStringOnCircle(matrices, mc.font, "E", CIRCLE_RADIUS - 1, eastAngle, TEXT_COLOUR);
        this.drawStringOnCircle(matrices, mc.font, "S", CIRCLE_RADIUS - 1, southAngle, TEXT_COLOUR);

        /* Heading indicator triangle */
        this.fillTriangle(matrices, 5, 8, HEADING_COLOUR);

        /* Player coordinates beneath the map, for convenience - this could be improved by scaling the text as the text gets wider, so it doesn't go off-screen. */
        matrices.translate(0, CIRCLE_RADIUS + 5, 0);
        matrices.scale(1.0F / (float) mc.getWindow().getGuiScale(), 1.0F / (float) mc.getWindow().getGuiScale(), 1.0F);
        final String playerCoords = String.format("X: %.2f Z: %.2f", mc.player.getX(), mc.player.getZ());
        GuiComponent.drawCenteredString(matrices, mc.font, playerCoords, 0, 0, TEXT_COLOUR);

        matrices.popPose();
    }

    /**
     * Draw a string on-screen, centered around the outside of a circle of the given radius at the given angle.
     *
     * @param matrices PoseStack used for positioning.
     * @param font Font to render with.
     * @param text String to render.
     * @param circleRadius Radius of the circle we will be using for position calculation.
     * @param angle Angle at which the text will be placed around the circle.
     * @param colour Colour of the text.
     */
    private void drawStringOnCircle(PoseStack matrices, Font font, String text, float circleRadius, float angle, int colour) {
        /* The division by 2.0 is so that the center of the letter falls on the circle, since the coordinates are the text's upper left. */
        float posX = (circleRadius * Mth.cos(angle)) - (font.width(text) / 2.0F);
        float posY = (circleRadius * Mth.sin(angle)) - (font.lineHeight / 2.0F);

        font.drawShadow(matrices, text, posX, posY, colour);
    }

    /**
     * Fill a circle on-screen, centered around the current model position of the PoseStack.
     *
     * @param matrices PoseStack used for positioning.
     * @param radius Circle radius, in pixels.
     * @param segments Number of segments that make up the circle.
     * @param colour ARGB colour of the circle.
     */
    private void fillCircle(PoseStack matrices, int radius, int segments, int colour) {
        float red = (float)(colour >> 24 & 0xFF) / 255.0F;
        float green = (float)(colour >> 16 & 0xFF) / 255.0F;
        float blue = (float)(colour >> 8 & 0xFF) / 255.0F;
        float alpha = (float)(colour & 0xFF) / 255.0F;

        final Matrix4f matrix = matrices.last().pose();
        final BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        bufferBuilder.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i <= segments; i++) {
            float angle = (float) (-2 * Math.PI * i / segments);
            float x = radius * Mth.cos(angle);
            float y = radius * Mth.sin(angle);

            bufferBuilder.vertex(matrix, x, y, 0.0F).color(green, blue, alpha, red).endVertex();
        }

        bufferBuilder.end();
        BufferUploader.end(bufferBuilder);
    }

    /**
     * Fill an isosceles triangle on-screen, centered around the current model position of the given PoseStack.
     *
     * @param matrices PoseStack used for positioning.
     * @param base Base width of the triangle, in pixels.
     * @param height Height of the triangle, in pixels.
     * @param colour ARGB colour of the triangle.
     */
    private void fillTriangle(PoseStack matrices, float base, float height, int colour) {
        float red = (float)(colour >> 24 & 0xFF) / 255.0F;
        float green = (float)(colour >> 16 & 0xFF) / 255.0F;
        float blue = (float)(colour >> 8 & 0xFF) / 255.0F;
        float alpha = (float)(colour & 0xFF) / 255.0F;

        final Matrix4f matrix = matrices.last().pose();
        final BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        final float baseOverTwo = base / 2.0F;
        final float heightOverTwo = height / 2.0F;

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        bufferBuilder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        bufferBuilder.vertex(matrix, 0, -heightOverTwo, 0).color(green, blue, alpha, red).endVertex();
        bufferBuilder.vertex(matrix, -baseOverTwo, heightOverTwo, 0).color(green, blue, alpha, red).endVertex();
        bufferBuilder.vertex(matrix, baseOverTwo, heightOverTwo, 0).color(green, blue, alpha, red).endVertex();

        bufferBuilder.end();
        BufferUploader.end(bufferBuilder);
    }

    /**
     * Initialize the renderer if it needs to be initialized. This registers the map texture with Minecraft, and enables
     * the stencil buffer if necessary.
     */
    private void initIfRequired(Minecraft mc, MapManager mapManager) {
        if (!this.initialized) {
            mapManager.registerTexture(mc.textureManager);

            /* We use the stencil buffer when rendering the map, so make sure it's enabled */
            final RenderTarget renderTarget = mc.getMainRenderTarget();

            if (!renderTarget.isStencilEnabled()) {
                renderTarget.enableStencil();
            }

            this.initialized = true;
        }
    }
}
