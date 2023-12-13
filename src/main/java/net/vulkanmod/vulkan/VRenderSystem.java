package net.vulkanmod.vulkan;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.util.ColorUtil;
import net.vulkanmod.vulkan.util.MappedBuffer;
import net.vulkanmod.vulkan.util.VUtil;
import org.joml.Math;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static com.mojang.blaze3d.platform.GlConst.GL_DEPTH_BUFFER_BIT;

public abstract class VRenderSystem {
    private static long window;

    public static boolean depthTest = true;
    public static boolean depthMask = true;
    public static int depthFun = 515;

    public static int colorMask = PipelineState.ColorMask.getColorMask(true, true, true, true);

    public static boolean cull = true;

    public static final float clearDepth = 1.0f;
    public static FloatBuffer clearColor = MemoryUtil.memCallocFloat(4);

    public static final MappedBuffer modelViewMatrix = MappedBuffer.getMappedBuffer(16 * 4);
    public static final MappedBuffer projectionMatrix = MappedBuffer.getMappedBuffer(16 * 4);
    public static final MappedBuffer TextureMatrix = MappedBuffer.getMappedBuffer(16 * 4);
    public static final MappedBuffer MVP = MappedBuffer.getMappedBuffer(16 * 4);

    public static final MappedBuffer ChunkOffset = MappedBuffer.getMappedBuffer(3 * 4);
    public static final MappedBuffer lightDirection0 = MappedBuffer.getMappedBuffer(3 * 4);
    public static final MappedBuffer lightDirection1 = MappedBuffer.getMappedBuffer(3 * 4);

    public static final MappedBuffer shaderColor = MappedBuffer.getMappedBuffer(4 * 4);
    public static final MappedBuffer shaderFogColor = MappedBuffer.getMappedBuffer(4 * 4);

    public static final MappedBuffer screenSize = MappedBuffer.getMappedBuffer(2 * 4);

    public static float alphaCutout = 0.0f;

    private static final float[] depthBias = new float[2];
    private static boolean clearColorUpdate = false;

    public static void initRenderer()
    {
        RenderSystem.assertInInitPhase();

        Vulkan.initVulkan(window);
    }

    public static ByteBuffer getChunkOffset() { return ChunkOffset.buffer(); }

    public static int maxSupportedTextureSize() {
        return DeviceManager.deviceProperties.limits().maxImageDimension2D();
    }

    public static void renderCrosshair(int p_69348_, boolean p_69349_, boolean p_69350_, boolean p_69351_) {
        RenderSystem.assertOnRenderThread();
//        GlStateManager._disableTexture();
//        GlStateManager._depthMask(false);
//        GlStateManager._disableCull();
        VRenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
        Tesselator tesselator = RenderSystem.renderThreadTesselator();
        BufferBuilder bufferbuilder = tesselator.getBuilder();
        RenderSystem.lineWidth(4.0F);
        bufferbuilder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        if (p_69349_) {
            bufferbuilder.vertex(0.0D, 0.0D, 0.0D).color(0, 0, 0, 255).normal(1.0F, 0.0F, 0.0F).endVertex();
            bufferbuilder.vertex((double)p_69348_, 0.0D, 0.0D).color(0, 0, 0, 255).normal(1.0F, 0.0F, 0.0F).endVertex();
        }

        if (p_69350_) {
            bufferbuilder.vertex(0.0D, 0.0D, 0.0D).color(0, 0, 0, 255).normal(0.0F, 1.0F, 0.0F).endVertex();
            bufferbuilder.vertex(0.0D, (double)p_69348_, 0.0D).color(0, 0, 0, 255).normal(0.0F, 1.0F, 0.0F).endVertex();
        }

        if (p_69351_) {
            bufferbuilder.vertex(0.0D, 0.0D, 0.0D).color(0, 0, 0, 255).normal(0.0F, 0.0F, 1.0F).endVertex();
            bufferbuilder.vertex(0.0D, 0.0D, (double)p_69348_).color(0, 0, 0, 255).normal(0.0F, 0.0F, 1.0F).endVertex();
        }

        tesselator.end();
        RenderSystem.lineWidth(2.0F);
        bufferbuilder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        if (p_69349_) {
            bufferbuilder.vertex(0.0D, 0.0D, 0.0D).color(255, 0, 0, 255).normal(1.0F, 0.0F, 0.0F).endVertex();
            bufferbuilder.vertex((double)p_69348_, 0.0D, 0.0D).color(255, 0, 0, 255).normal(1.0F, 0.0F, 0.0F).endVertex();
        }

        if (p_69350_) {
            bufferbuilder.vertex(0.0D, 0.0D, 0.0D).color(0, 255, 0, 255).normal(0.0F, 1.0F, 0.0F).endVertex();
            bufferbuilder.vertex(0.0D, (double)p_69348_, 0.0D).color(0, 255, 0, 255).normal(0.0F, 1.0F, 0.0F).endVertex();
        }

        if (p_69351_) {
            bufferbuilder.vertex(0.0D, 0.0D, 0.0D).color(127, 127, 255, 255).normal(0.0F, 0.0F, 1.0F).endVertex();
            bufferbuilder.vertex(0.0D, 0.0D, (double)p_69348_).color(127, 127, 255, 255).normal(0.0F, 0.0F, 1.0F).endVertex();
        }

        tesselator.end();
        RenderSystem.lineWidth(1.0F);
//        GlStateManager._enableCull();
        RenderSystem.depthMask(true);
//        GlStateManager._enableTexture();
    }

    public static void applyMVP(Matrix4f MV, Matrix4f P) {
        applyModelViewMatrix(MV);
        applyProjectionMatrix(P);
        calculateMVP();
    }

    public static void applyModelViewMatrix(Matrix4f mat) {
        mat.getToAddress(modelViewMatrix.ptr());
        //MemoryUtil.memPutFloat(MemoryUtil.memAddress(modelViewMatrix), 1);
    }

    public static void applyProjectionMatrix(Matrix4f mat) {
        mat.getToAddress(projectionMatrix.ptr());


    	Matrix4f pretransformMatrix = Vulkan.getPretransformMatrix();
        long projMatrixBuffer = projectionMatrix.ptr();
        // This allows us to skip allocating an object
        // if the matrix is known to be an identity matrix.
        // Tbh idk if the jvm will just optimize out the allocation but i can't be sure
        // as java is sometimes pretty pedantic about object allocations.
        if((pretransformMatrix.properties() & Matrix4f.PROPERTY_IDENTITY) != 0) {
        	mat.getToAddress(projMatrixBuffer);
        } else {
        	mat.mulLocal(pretransformMatrix, new Matrix4f()).getToAddress(projMatrixBuffer);
        }
    }


    public static void calculateMVP() {
        org.joml.Matrix4f MV = new org.joml.Matrix4f().setFromAddress(modelViewMatrix.ptr());
        org.joml.Matrix4f P = new org.joml.Matrix4f().setFromAddress(projectionMatrix.ptr());
        P.mul(MV).getToAddress(MVP.ptr());

    public static void calculateMVP() {
        org.joml.Matrix4f MV = new org.joml.Matrix4f(modelViewMatrix.buffer().asFloatBuffer());
        org.joml.Matrix4f P = new org.joml.Matrix4f(projectionMatrix.buffer().asFloatBuffer());
        P.mul(MV).get(MVP.buffer());
    }

    public static void translateMVP(float x, float y, float z) {
        org.joml.Matrix4f MVP_ = new org.joml.Matrix4f().setFromAddress(MVP.ptr());

        MVP_.translate(x, y, z);
        MVP_.getToAddress(MVP.ptr());
    }

    public static void setTextureMatrix(Matrix4f mat) {
        mat.getToAddress(TextureMatrix.ptr());
    }

    public static MappedBuffer getTextureMatrix() {
        return TextureMatrix;
    }

    public static MappedBuffer getModelViewMatrix() {
        return modelViewMatrix;
    }

    public static MappedBuffer getProjectionMatrix() {
        return projectionMatrix;
    }

    public static MappedBuffer getMVP() {
        return MVP;
    }

    public static void setChunkOffset(float f1, float f2, float f3) {
        long ptr = ChunkOffset.ptr();
        VUtil.UNSAFE.putFloat(ptr, f1);
        VUtil.UNSAFE.putFloat(ptr + 4, f2);
        VUtil.UNSAFE.putFloat(ptr + 8, f3);
    }

    public static void setShaderColor(float f1, float f2, float f3, float f4) {
        ColorUtil.setRGBA_Buffer(shaderColor, f1, f2, f3, f4);
    }

    public static void setShaderFogColor(float f1, float f2, float f3, float f4) {
        ColorUtil.setRGBA_Buffer(shaderFogColor, f1, f2, f3, f4);
    }

    public static MappedBuffer getShaderColor() {
        return shaderColor;
    }

    public static MappedBuffer getShaderFogColor() {
        return shaderFogColor;
    }

    public static void enableColorLogicOp() {
        PipelineState.currentLogicOpState = new PipelineState.LogicOpState(true, 0);
    }

    public static void disableColorLogicOp() {
        PipelineState.currentLogicOpState = PipelineState.DEFAULT_LOGICOP_STATE;
    }

    public static void logicOp(GlStateManager.LogicOp p_69836_) {
        PipelineState.currentLogicOpState.setLogicOp(p_69836_);
    }
    public static void setFogClearColor(float f1, float f2, float f3, float f4)
    {
        ColorUtil.setRGBA_Buffer(clearColor, f1, f2, f3, f4);
    }
    public static void clearColor(float f1, float f2, float f3, float f4) {
//        if(f1==1&&f2==1&&f3==1&&f4==1) return; //Test JM Clear Fix
        clearColorUpdate=checkClearisActuallyDifferent(f1, f2, f3, f4); //set to true if different colour
        if(!clearColorUpdate) return;
        ColorUtil.setRGBA_Buffer(clearColor, f1, f2, f3, f4);
    }

    private static boolean checkClearisActuallyDifferent(float f0, float f1, float f2, float f3) {
        float f0_ = clearColor.get(0);
        float f1_ = clearColor.get(1);
        float f2_ = clearColor.get(2);
        float f3_ = clearColor.get(3);
        return f0_!=f0&&f1_!=f1&&f2_!=f2&&f3_!=f3;
    }

    public static void clear(int v) {
        //Skip Mods reapplying the same colour over and over per clear
        //if(/*currentClearColor==clearColor||*/!clearColorUpdate) return;
        Renderer.clearAttachments(clearColorUpdate ? v : GL_DEPTH_BUFFER_BIT); //Depth Only Clears needed to fix Chat + Command Elements
        clearColorUpdate=false;
    }

    public static void disableDepthTest() {
        depthTest = false;
    }

    public static void depthMask(boolean b) {
        depthMask = b;
    }

    public static PipelineState.DepthState getDepthState() {
        return new PipelineState.DepthState(depthTest, depthMask, depthFun);
    }

    public static void colorMask(boolean b, boolean b1, boolean b2, boolean b3) {
        colorMask = PipelineState.ColorMask.getColorMask(b, b1, b2, b3);
    }

    public static int getColorMask() {
        return colorMask;
    }

    public static void enableDepthTest() {
        depthTest = true;
    }
    
    public static void enableCull() {
        cull = true;
    }
    
    public static void disableCull() {
        cull = false;
    }

    public static void polygonOffset(float v, float v1) {
        depthBias[0] = v;
        depthBias[1] = v1;
    }

    public static void enablePolygonOffset() {
        Renderer.setDepthBias(depthBias[0], depthBias[1]);
    }

    public static void disablePolygonOffset() {
        Renderer.setDepthBias(0.0F, 0.0F);
    }

    public static MappedBuffer getScreenSize() {
        updateScreenSize();
        return screenSize;
    }

    public static void updateScreenSize() {
        Window window = Minecraft.getInstance().getWindow();

        screenSize.putFloat(0, (float)window.getWidth());
        screenSize.putFloat(4, (float)window.getHeight());
    }
    
    public static void setWindow(long window) {
        VRenderSystem.window = window;
    }

    public static void depthFunc(int p_69457_) {
        depthFun = p_69457_;
    }

    public static void enableBlend() {
        PipelineState.blendInfo.enabled = true;
    }

    public static void disableBlend() {
        PipelineState.blendInfo.enabled = false;
    }

    public static void blendFunc(GlStateManager.SourceFactor sourceFactor, GlStateManager.DestFactor destFactor) {
        PipelineState.blendInfo.setBlendFunction(sourceFactor, destFactor);
    }

    public static void blendFunc(int srcFactor, int dstFactor) {
        PipelineState.blendInfo.setBlendFunction(srcFactor, dstFactor);
    }

    public static void blendFuncSeparate(GlStateManager.SourceFactor p_69417_, GlStateManager.DestFactor p_69418_, GlStateManager.SourceFactor p_69419_, GlStateManager.DestFactor p_69420_) {
        PipelineState.blendInfo.setBlendFuncSeparate(p_69417_, p_69418_, p_69419_, p_69420_);
    }

    public static void blendFuncSeparate(int srcFactorRGB, int dstFactorRGB, int srcFactorAlpha, int dstFactorAlpha) {
        PipelineState.blendInfo.setBlendFuncSeparate(srcFactorRGB, dstFactorRGB, srcFactorAlpha, dstFactorAlpha);
    }
}
