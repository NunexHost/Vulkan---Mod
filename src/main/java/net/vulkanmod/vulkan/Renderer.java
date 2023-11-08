package net.vulkanmod.vulkan;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.client.Minecraft;
import net.vulkanmod.render.chunk.AreaUploadManager;
import net.vulkanmod.render.chunk.TerrainShaderManager;
import net.vulkanmod.render.profiling.Profiler2;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.passes.DefaultMainPass;
import net.vulkanmod.vulkan.passes.MainPass;
import net.vulkanmod.vulkan.shader.*;
import net.vulkanmod.vulkan.shader.layout.PushConstants;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static net.vulkanmod.vulkan.Vulkan.*;
import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.EXTFullScreenExclusive.VK_ERROR_FULL_SCREEN_EXCLUSIVE_MODE_LOST_EXT;
import static org.lwjgl.vulkan.KHRSurface.VK_ERROR_SURFACE_LOST_KHR;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class Renderer {
    private static Renderer INSTANCE;

    private static VkDevice device;

    private static boolean swapCahinUpdate = false;
    public static boolean skipRendering = false;
    private int imagesNum;
    private final IntArrayFIFOQueue presentableImages = new IntArrayFIFOQueue(3);;

    public static void initRenderer() { INSTANCE = new Renderer(); }

    public static Renderer getInstance() { return INSTANCE; }

    public static Drawer getDrawer() { return INSTANCE.drawer; }
    private static final IntArrayList unusedImages = new IntArrayList(3);
    private static final IntArrayFIFOQueue availableImages = new IntArrayFIFOQueue(3);
    private static final IntArrayFIFOQueue renderingImages = new IntArrayFIFOQueue(3);
    private static final IntArrayFIFOQueue presentingImages = new IntArrayFIFOQueue(3);
    public static int getCurrentFrame() { return currentFrame; }
    public static int getImageIndex() { return renderingImages.firstInt(); }
    public static int getPresentIndex() { return presentingImages.firstInt(); }

    Runnable runnable = () -> initPresent();
    private final Set<Pipeline> usedPipelines = new ObjectOpenHashSet<>();

    private final Drawer drawer;

    private int framesNum;
    private List<VkCommandBuffer> commandBuffers;
    private ArrayList<Long> imageAvailableSemaphores;
    private ArrayList<Long> renderFinishedSemaphores;
    private ArrayList<Long> inFlightFences;



    private Framebuffer boundFramebuffer;
    private RenderPass boundRenderPass;

    private static int currentFrame = 0;
//    private static int imageIndex = 0;
    private VkCommandBuffer currentCmdBuffer;

    MainPass mainPass = DefaultMainPass.PASS;

    private final List<Runnable> onResizeCallbacks = new ObjectArrayList<>();

    public Renderer() {
        device = Vulkan.getDevice();

        Uniforms.setupDefaultUniforms();
        TerrainShaderManager.init();
        AreaUploadManager.createInstance();

        framesNum = getSwapChain().getFramesNum();
        imagesNum = getSwapChain().getImagesNum();

        drawer = new Drawer();
        drawer.createResources(framesNum);

        for(int i = 0; i < imagesNum; i++)
        {
            unusedImages.push(i);
        }

        allocateCommandBuffers();
        createSyncObjects();

        AreaUploadManager.INSTANCE.init();
    }

    private void allocateCommandBuffers() {
        if(commandBuffers != null) {
            commandBuffers.forEach(commandBuffer -> vkFreeCommandBuffers(device, Vulkan.getCommandPool(), commandBuffer));
        }

        commandBuffers = new ArrayList<>(framesNum);

        try(MemoryStack stack = stackPush()) {

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.commandPool(getCommandPool());
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandBufferCount(framesNum);

            PointerBuffer pCommandBuffers = stack.mallocPointer(framesNum);

            if (vkAllocateCommandBuffers(device, allocInfo, pCommandBuffers) != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate command buffers");
            }

            for (int i = 0; i < framesNum; i++) {
                commandBuffers.add(new VkCommandBuffer(pCommandBuffers.get(i), device));
            }
        }
    }

    private void createSyncObjects() {
        imageAvailableSemaphores = new ArrayList<>(framesNum);
        renderFinishedSemaphores = new ArrayList<>(framesNum);
        inFlightFences = new ArrayList<>(framesNum);

        try(MemoryStack stack = stackPush()) {

            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack);
            semaphoreInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack);
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);

            LongBuffer pImageAvailableSemaphore = stack.mallocLong(1);
            LongBuffer pRenderFinishedSemaphore = stack.mallocLong(1);
            LongBuffer pFence = stack.mallocLong(1);

            for(int i = 0;i < framesNum; i++) {

                if(vkCreateSemaphore(device, semaphoreInfo, null, pImageAvailableSemaphore) != VK_SUCCESS
                        || vkCreateSemaphore(device, semaphoreInfo, null, pRenderFinishedSemaphore) != VK_SUCCESS
                        || vkCreateFence(device, fenceInfo, null, pFence) != VK_SUCCESS) {

                    throw new RuntimeException("Failed to create synchronization objects for the frame " + i);
                }

                imageAvailableSemaphores.add(pImageAvailableSemaphore.get(0));
                renderFinishedSemaphores.add(pRenderFinishedSemaphore.get(0));
                inFlightFences.add(pFence.get(0));

            }

        }
    }

    public void beginFrame() {
        Profiler2 p = Profiler2.getMainProfiler();
        p.pop();
        p.push("Frame_fence");

        if(swapCahinUpdate) {
            recreateSwapChain();
            swapCahinUpdate = false;

            if(getSwapChain().getWidth() == 0 && getSwapChain().getHeight() == 0) {
                skipRendering = true;
                Minecraft.getInstance().noRender = true;
            }
            else {
                skipRendering = false;
                Minecraft.getInstance().noRender = false;
            }
        }


        if(skipRendering)
            return;

        vkWaitForFences(device, inFlightFences.get(currentFrame), true, VUtil.UINT64_MAX);

        p.pop();
        p.start();
        p.push("Begin_rendering");

//        AreaUploadManager.INSTANCE.updateFrame();

        MemoryManager.getInstance().initFrame(currentFrame);
        drawer.setCurrentFrame(currentFrame);

        //Moved before texture updates
//        this.vertexBuffers[currentFrame].reset();
//        this.uniformBuffers.reset();
//        Vulkan.getStagingBuffer(currentFrame).reset();

        resetDescriptors();

        currentCmdBuffer = commandBuffers.get(currentFrame);
        vkResetCommandBuffer(currentCmdBuffer, 0);

        try(MemoryStack stack = stackPush()) {

            if (aquireNextImage(stack.mallocInt(1))) return;

            renderingImages.enqueue(availableImages.dequeueInt());
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            VkCommandBuffer commandBuffer = currentCmdBuffer;

            int err = vkBeginCommandBuffer(commandBuffer, beginInfo);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("Failed to begin recording command buffer:" + err);
            }

            mainPass.begin(commandBuffer, stack);

            vkCmdSetDepthBias(commandBuffer, 0.0F, 0.0F, 0.0F);
        }

        p.pop();
    }

    private int remImg() {
        for (int i = 0; i < unusedImages.size(); i++) {
            if (unusedImages.getInt(i) == availableImages.firstInt()) return unusedImages.removeInt(i);
        }
        return 0;
    }

    private boolean aquireNextImage(IntBuffer pImageIndex) {
        if (presentingImages.size() > imagesNum) return true;
        if (renderingImages.size() > framesNum) return true;
        if (availableImages.size()>imagesNum) {
            return true;
        }
//        if(unusedImages.isEmpty()) return true;

        int vkResult = vkAcquireNextImageKHR(device, Vulkan.getSwapChain().getId(), -1,
                imageAvailableSemaphores.get(currentFrame), VK_NULL_HANDLE, pImageIndex);
//        imageIndex = pImageIndex.get(0);

//        if (!presentingImages.isEmpty()) {
//            int i1 = presentingImages.firstInt();
//            if (i1 == i) {
//               unusedImages.add(presentingImages.dequeueInt());
//            }
//            if(!renderingImages.isEmpty() && renderingImages.firstInt() == i1)
//            {
//                return true;
//            }
//        }
        if(vkResult == VK_ERROR_OUT_OF_DATE_KHR || vkResult == VK_SUBOPTIMAL_KHR || swapCahinUpdate) {
            swapCahinUpdate = true;
//                shouldRecreate = false;
//                recreateSwapChain();
            return true;
        }
        if (vkResult != VK_SUCCESS) {
            throw new RuntimeException("Failed to present swap chain image " + vkResult);
        }

        availableImages.enqueue(pImageIndex.get(0));
        return false;
    }

    public void endFrame() {
        if(skipRendering||renderingImages.isEmpty())
            return;

        Profiler2 p = Profiler2.getMainProfiler();
        p.push("End_rendering");

        mainPass.end(currentCmdBuffer);

        submitFrame();

        p.pop();
    }

    public void endRenderPass() {
        this.boundRenderPass.endRenderPass(currentCmdBuffer);
        this.boundRenderPass = null;
    }

    //TODO
    public void beginRendering(Framebuffer framebuffer) {
        if(skipRendering) 
            return;

        if(this.boundFramebuffer != framebuffer) {
            this.endRendering();

            try (MemoryStack stack = stackPush()) {
                //TODO
//                framebuffer.beginRenderPass(currentCmdBuffer, stack);
            }

            this.boundFramebuffer = framebuffer;
        }
    }

    public void endRendering() {
        if(skipRendering)
            return;
        
        this.boundRenderPass.endRenderPass(currentCmdBuffer);

        this.boundFramebuffer = null;
        this.boundRenderPass = null;
    }

    public void setBoundFramebuffer(Framebuffer framebuffer) {
        this.boundFramebuffer = framebuffer;
    }

    public void resetBuffers() {
        Profiler2 p = Profiler2.getMainProfiler();
        p.push("Frame_ops");

        drawer.resetBuffers(currentFrame);

        AreaUploadManager.INSTANCE.updateFrame();
        Vulkan.getStagingBuffer(currentFrame).reset();
    }

    public void addUsedPipeline(Pipeline pipeline) {
        usedPipelines.add(pipeline);
    }

    public void removeUsedPipeline(Pipeline pipeline) { usedPipelines.remove(pipeline); }

    private void resetDescriptors() {
        for(Pipeline pipeline : usedPipelines) {
            pipeline.resetDescriptorPool(currentFrame);
        }

        usedPipelines.clear();
    }

    private void initPresent() {
        //Keep things stack local if possible to avoid Thread sharing overhead
        int internRenderIndex = 0;

        while (!swapCahinUpdate)
        {

        }
    }


    private void submitFrame() {
        if(swapCahinUpdate||renderingImages.isEmpty())
            return;

        try(MemoryStack stack = stackPush()) {

            int vkResult;


            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);

            submitInfo.waitSemaphoreCount(1);
            submitInfo.pWaitSemaphores(stackGet().longs(imageAvailableSemaphores.get(currentFrame)));
            submitInfo.pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT));

            submitInfo.pSignalSemaphores(stackGet().longs(renderFinishedSemaphores.get(currentFrame)));

            submitInfo.pCommandBuffers(stack.pointers(currentCmdBuffer));

            vkResetFences(device, stackGet().longs(inFlightFences.get(currentFrame)));

            Synchronization.INSTANCE.waitFences();

            if((vkResult = vkQueueSubmit(Device.getGraphicsQueue().queue(), submitInfo, inFlightFences.get(currentFrame))) != VK_SUCCESS) {
                vkResetFences(device, stackGet().longs(inFlightFences.get(currentFrame)));
                throw new RuntimeException("Failed to submit draw command buffer: " + vkResult);
            }

            presentableImages.enqueue(renderingImages.dequeueInt());

            if (flipFrame(stack)) return;

            currentFrame = (currentFrame + 1) % framesNum;


        }
    }

    private boolean flipFrame(MemoryStack stack) {
        if(!presentableImages.isEmpty()) {
            int vkResult;
            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack);
            presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);

            presentInfo.pWaitSemaphores(stack.longs(renderFinishedSemaphores.get(currentFrame)));

            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(stack.longs(Vulkan.getSwapChain().getId()));
//            renderingImages.dequeueInt();
            int imageIndex1 = presentableImages.dequeueInt();
            presentInfo.pImageIndices(stack.ints(imageIndex1));

            vkResult = vkQueuePresentKHR(Device.getPresentQueue().queue(), presentInfo);
//            presentingImages.enqueue(imageIndex1);
            if (vkResult == VK_ERROR_OUT_OF_DATE_KHR || vkResult == VK_SUBOPTIMAL_KHR || swapCahinUpdate) {
                swapCahinUpdate = true;
//                shouldRecreate = false;
//                recreateSwapChain();
                return true;
            } else if (vkResult != VK_SUCCESS) {
                throw new RuntimeException("Failed to present swap chain image");
            }
            return false;
        }
        return true;
    }

    private String decVkErr(int vkResult) {
       return switch (vkResult)
        {
            case VK_TIMEOUT -> "VK_TIMEOUT";
            case VK_SUBOPTIMAL_KHR -> "VK_SUBOPTIMAL_KHR";
            case VK_NOT_READY -> "VK_NOT_READY";
            case VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK_ERROR_DEVICE_LOST -> "VK_ERROR_DEVICE_LOST";
            case VK_ERROR_OUT_OF_DATE_KHR -> "VK_ERROR_OUT_OF_DATE_KHR";
            case VK_ERROR_SURFACE_LOST_KHR -> "VK_ERROR_SURFACE_LOST_KHR";
            case VK_ERROR_FULL_SCREEN_EXCLUSIVE_MODE_LOST_EXT -> "VK_ERROR_FULL_SCREEN_EXCLUSIVE_MODE_LOST_EXT";
            default -> "VK_SUCCESS";
        };
    }

    void waitForSwapChain()
    {
        vkResetFences(device, inFlightFences.get(currentFrame));

//        constexpr VkPipelineStageFlags t=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        try(MemoryStack stack = MemoryStack.stackPush()) {
            //Empty Submit
            VkSubmitInfo info = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pWaitSemaphores(stack.longs(imageAvailableSemaphores.get(currentFrame)))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT));

            vkQueueSubmit(Device.getGraphicsQueue().queue(), info, inFlightFences.get(currentFrame));
            vkWaitForFences(device, inFlightFences.get(currentFrame),  true, -1);
        }
    }

    private void recreateSwapChain() {
//        for(Long fence : inFlightFences) {
//            vkWaitForFences(device, fence, true, VUtil.UINT64_MAX);
//        }
        renderingImages.clear();
        presentingImages.clear(); unusedImages.clear();
        availableImages.clear();

//        waitForSwapChain();
        Vulkan.waitIdle();

//        for(int i = 0; i < getSwapChainImages().size(); ++i) {
//            vkDestroyFence(device, inFlightFences.get(i), null);
//            vkDestroySemaphore(device, imageAvailableSemaphores.get(i), null);
//            vkDestroySemaphore(device, renderFinishedSemaphores.get(i), null);
//        }




        commandBuffers.forEach(commandBuffer -> vkResetCommandBuffer(commandBuffer, 0));

        Vulkan.recreateSwapChain();

        //Semaphores need to be recreated in order to make them unsignaled
        destroySyncObjects();

        int newFramesNum = getSwapChain().getFramesNum();
        imagesNum = getSwapChain().getImagesNum();
        for(int i = 0; i < imagesNum; i++) {
            unusedImages.add(i);
        }

        if(framesNum != newFramesNum) {
            AreaUploadManager.INSTANCE.waitUploads();


            framesNum = newFramesNum;
            allocateCommandBuffers();

            Pipeline.recreateDescriptorSets(framesNum);

            drawer.createResources(framesNum);
        }

        createSyncObjects();

        this.onResizeCallbacks.forEach(Runnable::run);

        currentFrame = 0;
    }

    public void cleanUpResources() {
        destroySyncObjects();

        drawer.cleanUpResources();

        TerrainShaderManager.destroyPipelines();
        VTextureSelector.getWhiteTexture().free();
    }

    private void destroySyncObjects() {
        for (int i = 0; i < framesNum; ++i) {
            vkDestroyFence(device, inFlightFences.get(i), null);
            vkDestroySemaphore(device, imageAvailableSemaphores.get(i), null);
            vkDestroySemaphore(device, renderFinishedSemaphores.get(i), null);
        }
    }

    public void setBoundRenderPass(RenderPass boundRenderPass) {
        this.boundRenderPass = boundRenderPass;
    }

    public RenderPass getBoundRenderPass() {
        return boundRenderPass;
    }

    public void setMainPass(MainPass mainPass) { this.mainPass = mainPass; }

    public void addOnResizeCallback(Runnable runnable) {
        this.onResizeCallbacks.add(runnable);
    }

    public void bindGraphicsPipeline(GraphicsPipeline pipeline) {
        VkCommandBuffer commandBuffer = currentCmdBuffer;

        PipelineState currentState = PipelineState.getCurrentPipelineState(boundRenderPass);
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.getHandle(currentState));

        addUsedPipeline(pipeline);
    }

    public void uploadAndBindUBOs(Pipeline pipeline) {
        VkCommandBuffer commandBuffer = currentCmdBuffer;
        pipeline.bindDescriptorSets(commandBuffer, currentFrame);
    }

    public void pushConstants(Pipeline pipeline) {
        VkCommandBuffer commandBuffer = currentCmdBuffer;

        PushConstants pushConstants = pipeline.getPushConstants();

        try (MemoryStack stack = stackPush()) {
            ByteBuffer buffer = stack.malloc(pushConstants.getSize());
            long ptr = MemoryUtil.memAddress0(buffer);
            pushConstants.update(ptr);

            nvkCmdPushConstants(commandBuffer, pipeline.getLayout(), VK_SHADER_STAGE_VERTEX_BIT, 0, pushConstants.getSize(), ptr);
        }

    }

    public static void setDepthBias(float units, float factor) {
        VkCommandBuffer commandBuffer = INSTANCE.currentCmdBuffer;

        vkCmdSetDepthBias(commandBuffer, units, 0.0f, factor);
    }

    public static void clearAttachments(int v) {
        Framebuffer framebuffer = Renderer.getInstance().boundFramebuffer;
        if(framebuffer == null)
            return;

        clearAttachments(v, framebuffer.getWidth(), framebuffer.getHeight());
    }

    public static void clearAttachments(int v, int width, int height) {
        if(skipRendering || v==0x4000 || v==0x4100)
            return;

        VkCommandBuffer commandBuffer = INSTANCE.currentCmdBuffer;

        try(MemoryStack stack = stackPush()) {
            //ClearValues have to be different for each attachment to clear, it seems it works like a buffer: color and depth attributes override themselves
//            VkClearValue colorValue = VkClearValue.malloc(stack);
//            colorValue.color().float32(VRenderSystem.clearColor);

            VkClearValue depthValue = VkClearValue.malloc(stack);
            depthValue.depthStencil().depth(VRenderSystem.clearDepth);

            int attachmentsCount;
            VkClearAttachment.Buffer pAttachments;
            if (v == 0x100) {
                attachmentsCount = 1;

                pAttachments = VkClearAttachment.malloc(attachmentsCount, stack);

                VkClearAttachment clearDepth = pAttachments.get(0);
                clearDepth.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                clearDepth.clearValue(depthValue);
            } else {
                throw new RuntimeException("unexpected value");
            }

            //Rect to clear
            VkRect2D renderArea = VkRect2D.malloc(stack);
            renderArea.offset().set(0, 0);
            renderArea.extent().set(width, height);

            VkClearRect.Buffer pRect = VkClearRect.calloc(1, stack);
            pRect.get(0).rect(renderArea);
            pRect.get(0).layerCount(1);

            vkCmdClearAttachments(commandBuffer, pAttachments, pRect);
        }
    }

    public static void setViewport(int x, int y, int width, int height) {
        try(MemoryStack stack = stackPush()) {
            VkExtent2D transformedExtent = transformToExtent(VkExtent2D.calloc(stack), width, height);
            VkOffset2D transformedOffset = transformToOffset(VkOffset2D.calloc(stack), x, y, width, height);
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);

            x = transformedOffset.x();
            y = transformedOffset.y();
            width = transformedExtent.width();
            height = transformedExtent.height();

            viewport.x(x);
            viewport.y(height + y);
            viewport.width(width);
            viewport.height(-height);
            viewport.minDepth(0.0f);
            viewport.maxDepth(1.0f);

            VkRect2D.Buffer scissor = VkRect2D.malloc(1, stack);
            scissor.offset(VkOffset2D.malloc(stack).set(0, 0));
            scissor.extent(transformedExtent);

            vkCmdSetViewport(INSTANCE.currentCmdBuffer, 0, viewport);
            vkCmdSetScissor(INSTANCE.currentCmdBuffer, 0, scissor);
        }
    }

    /**
     * Transform the X/Y coordinates from Minecraft coordinate space to Vulkan coordinate space
     * and write them to VkOffset2D
     * @param offset2D the offset to which the coordinates should be written
     * @param x the X coordinate
     * @param y the Y coordinate
     * @param w the viewport/scissor operation width
     * @param h the viewport/scissor operation height
     * @return same offset2D with transformations applied as necessary
     */
    private static VkOffset2D transformToOffset(VkOffset2D offset2D, int x, int y, int w, int h) {
        int pretransformFlags = Vulkan.getPretransformFlags();
        if(pretransformFlags == 0) {
            offset2D.set(x, y);
            return offset2D;
        }
        Framebuffer boundFramebuffer = Renderer.getInstance().boundFramebuffer;
        int framebufferWidth = boundFramebuffer.getWidth();
        int framebufferHeight = boundFramebuffer.getHeight();
        switch (pretransformFlags) {
            case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR -> {
                offset2D.x(framebufferWidth - h - y);
                offset2D.y(x);
            }
            case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR -> {
                offset2D.x(framebufferWidth - w - x);
                offset2D.y(framebufferHeight - h - y);
            }
            case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR -> {
                offset2D.x(y);
                offset2D.y(framebufferHeight - w - x);
            }
            default -> {
                offset2D.x(x);
                offset2D.y(y);
            }
        }
        return offset2D;
    }

    /**
     * Transform the width and height from Minecraft coordinate space to the Vulkan coordinate space
     * and write them to VkExtent2D
     * @param extent2D the extent to which the values should be written
     * @param w the viewport/scissor operation width
     * @param h the viewport/scissor operation height
     * @return the same VkExtent2D with transformations applied as necessary
     */
    private static VkExtent2D transformToExtent(VkExtent2D extent2D, int w, int h) {
        int pretransformFlags = Vulkan.getPretransformFlags();
        if(pretransformFlags == VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR ||
                pretransformFlags == VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR) {
            return extent2D.set(h, w);
        }
        return extent2D.set(w, h);
    }

    public static void setScissor(int x, int y, int width, int height) {
        try(MemoryStack stack = stackPush()) {
        	VkExtent2D extent = VkExtent2D.malloc(stack);
            Framebuffer boundFramebuffer = Renderer.getInstance().boundFramebuffer;
            // Since our x and y are still in Minecraft's coordinate space, pre-transform the framebuffer's width and height to get expected results.
            transformToExtent(extent, boundFramebuffer.getWidth(), boundFramebuffer.getHeight());
            int framebufferHeight = extent.height();

            VkRect2D.Buffer scissor = VkRect2D.malloc(1, stack);
            // Use this corrected height to transform from OpenGL to Vulkan coordinate space.
            scissor.offset(transformToOffset(VkOffset2D.malloc(stack), x, framebufferHeight - (y + height), width, height));
            // Reuse the extent to transform the scissor width/height
            scissor.extent(transformToExtent(extent, width, height));

            vkCmdSetScissor(INSTANCE.currentCmdBuffer, 0, scissor);
        }
    }

    public static void resetScissor() {
        if(Renderer.getInstance().boundFramebuffer == null)
            return;

        try(MemoryStack stack = stackPush()) {
            VkRect2D.Buffer scissor = Renderer.getInstance().boundFramebuffer.scissor(stack);
            vkCmdSetScissor(INSTANCE.currentCmdBuffer, 0, scissor);
        }
    }

    public static void pushDebugSection(String s) {
        if(Vulkan.ENABLE_VALIDATION_LAYERS) {
            VkCommandBuffer commandBuffer = INSTANCE.currentCmdBuffer;

            try(MemoryStack stack = stackPush()) {
                VkDebugUtilsLabelEXT markerInfo = VkDebugUtilsLabelEXT.calloc(stack);
                markerInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_LABEL_EXT);
                ByteBuffer string = stack.UTF8(s);
                markerInfo.pLabelName(string);
                vkCmdBeginDebugUtilsLabelEXT(commandBuffer, markerInfo);
            }
        }
    }

    public static void popDebugSection() {
        if(Vulkan.ENABLE_VALIDATION_LAYERS) {
            VkCommandBuffer commandBuffer = INSTANCE.currentCmdBuffer;

            vkCmdEndDebugUtilsLabelEXT(commandBuffer);
        }
    }

    public static void popPushDebugSection(String s) {
        popDebugSection();
        pushDebugSection(s);
    }

    public static int getFramesNum() { return INSTANCE.framesNum; }

    public static VkCommandBuffer getCommandBuffer() { return INSTANCE.currentCmdBuffer; }

    public static void scheduleSwapChainUpdate() { swapCahinUpdate = true; }
}
