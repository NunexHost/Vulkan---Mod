package net.vulkanmod.vulkan.framebuffer;

import net.vulkanmod.Initializer;
import net.vulkanmod.render.util.MathUtil;
import net.vulkanmod.vulkan.Device;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static net.vulkanmod.vulkan.Device.device;
import static net.vulkanmod.vulkan.Vulkan.*;
import static net.vulkanmod.vulkan.util.VUtil.UINT32_MAX;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRSharedPresentableImage.VK_PRESENT_MODE_SHARED_CONTINUOUS_REFRESH_KHR;
import static org.lwjgl.vulkan.KHRSharedPresentableImage.VK_PRESENT_MODE_SHARED_DEMAND_REFRESH_KHR;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class SwapChain extends Framebuffer {

    public static final int[] supportedImageModes;
    private static int DEFAULT_DEPTH_FORMAT = 0;
    private static final boolean imagelessFramebuffers = Device.vk12;
    private final boolean sharedRefreshMode = false;

    public static int getDefaultDepthFormat() {
        return DEFAULT_DEPTH_FORMAT;
    }

    private RenderPass renderPass;
    private long[] framebuffer;
    private long swapChain = VK_NULL_HANDLE;
    private List<VulkanImage> swapChainImages;
    private VkExtent2D extent2D;
    public boolean isBGRAformat;
    private boolean vsync = false;
    public static final int minImages, maxImages;
    private int[] currentLayout;
    public static final boolean hasImmediate, hasFastSync, hasVSync, hasAdaptiveVSync;

    static {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Device.SurfaceProperties surfaceProperties = Device.querySurfaceProperties(device.getPhysicalDevice(), stack);
            minImages = surfaceProperties.capabilities.minImageCount();
            int maxImageCount = surfaceProperties.capabilities.maxImageCount();

            boolean hasInfiniteSwapChain = maxImageCount == 0; //Applicable if Mesa/RADV Driver are present
            maxImages = hasInfiniteSwapChain ? 64 : Math.min(maxImageCount, 32);

            final IntBuffer presentModes = surfaceProperties.presentModes;
            supportedImageModes = new int[presentModes.capacity()];
            Arrays.setAll(SwapChain.supportedImageModes, presentModes::get);
            Initializer.LOGGER.info("--=SUPPORTED PRESENT MODES:=--");
            for (int supportedImageMode : SwapChain.supportedImageModes) {
                Initializer.LOGGER.info(SwapChain.getDisplayModeString(supportedImageMode));
            }
        }
        hasAdaptiveVSync = hasMode(VK_PRESENT_MODE_FIFO_RELAXED_KHR);
        hasVSync = hasMode(VK_PRESENT_MODE_FIFO_KHR);
        hasFastSync = hasMode(VK_PRESENT_MODE_MAILBOX_KHR);
        hasImmediate = hasMode(VK_PRESENT_MODE_IMMEDIATE_KHR);
    }


    private static boolean hasMode(int i1) {
        for (int supportedImageMode : SwapChain.supportedImageModes)
            if (i1 == supportedImageMode) return true;
        return false;
    }

    public SwapChain() {
        DEFAULT_DEPTH_FORMAT = Device.findDepthFormat();

        this.attachmentCount = 2;

        this.depthFormat = DEFAULT_DEPTH_FORMAT;
        this.framebuffer = new long[imagelessFramebuffers ? 1 : getImagesNum()];
        createSwapChain();

    }

    public static int checkPresentMode(int requestedMode, int fallback) {
        try(MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer surfaceProperties = Device.querySurfaceProperties(device.getPhysicalDevice(), stack).presentModes;
            for (int i = 0; i < surfaceProperties.capacity(); i++) {
                if (surfaceProperties.get(i) == requestedMode) {
                    return requestedMode;
                }
            }
            return fallback;
        }
    }

    public int recreateSwapChain() {
        Synchronization.INSTANCE.waitFences();

        if(this.depthAttachment != null) {
            this.depthAttachment.free();
            this.depthAttachment = null;
        }

        if(!DYNAMIC_RENDERING) {
//            this.renderPass.cleanUp();
            for (long id : framebuffer) {
                vkDestroyFramebuffer(getDevice(), id, null);
            }
            if(getImagesNum()!=framebuffer.length) framebuffer=new long[getImagesNum()];
        }

        createSwapChain();

        return this.getFramesNum();
    }

    public void createSwapChain() {

        try(MemoryStack stack = stackPush()) {
            VkDevice device = Vulkan.getDevice();
            Device.SurfaceProperties surfaceProperties = Device.querySurfaceProperties(device.getPhysicalDevice(), stack);

            VkSurfaceFormatKHR surfaceFormat = getFormat(surfaceProperties.formats);
            int presentMode = getPresentMode(surfaceProperties.presentModes);
            VkExtent2D extent = getExtent(surfaceProperties.capabilities);

            if(extent.width() == 0 && extent.height() == 0) {
                if(swapChain != VK_NULL_HANDLE) {
                    this.swapChainImages.forEach(image -> vkDestroyImageView(device, image.getImageView(), null));
                    vkDestroySwapchainKHR(device, swapChain, null);
                    swapChain = VK_NULL_HANDLE;
                }

                this.width = 0;
                this.height = 0;
                return;
            }

            if(Initializer.CONFIG.minImageCount < surfaceProperties.capabilities.minImageCount())
                Initializer.CONFIG.minImageCount = surfaceProperties.capabilities.minImageCount();

            if(sharedRefreshMode)
                Initializer.CONFIG.minImageCount = 1;

            int requestedFrames = Initializer.CONFIG.minImageCount;

            Initializer.LOGGER.info("requestedFrames" + requestedFrames);


            IntBuffer imageCount = stack.ints(requestedFrames);
//            IntBuffer imageCount = stack.ints(Math.max(surfaceProperties.capabilities.minImageCount(), preferredImageCount));

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.callocStack(stack);

            createInfo.sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR);
            createInfo.surface(Vulkan.getSurface());

            // Image settings
            this.format = surfaceFormat.format();
            this.extent2D = VkExtent2D.create().set(extent);

            createInfo.minImageCount(imageCount.get(0));
            createInfo.imageFormat(this.format);
            createInfo.imageColorSpace(surfaceFormat.colorSpace());
            createInfo.imageExtent(extent);
            createInfo.imageArrayLayers(1);
            createInfo.imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);

            Queue.QueueFamilyIndices indices = Queue.getQueueFamilies();

            if(!indices.graphicsFamily.equals(indices.presentFamily)) {
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT);
                createInfo.pQueueFamilyIndices(stack.ints(indices.graphicsFamily, indices.presentFamily));
            } else {
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            }

            createInfo.preTransform(surfaceProperties.capabilities.currentTransform());
            createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
            createInfo.presentMode(presentMode);
            createInfo.clipped(true);

            createInfo.oldSwapchain(swapChain);

            LongBuffer pSwapChain = stack.longs(VK_NULL_HANDLE);

            if(vkCreateSwapchainKHR(device, createInfo, null, pSwapChain) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create swap chain");
            }

            if(swapChain != VK_NULL_HANDLE) {
                this.swapChainImages.forEach(iamge -> vkDestroyImageView(device, iamge.getImageView(), null));
                vkDestroySwapchainKHR(device, swapChain, null);
            }

            swapChain = pSwapChain.get(0);

            vkGetSwapchainImagesKHR(device, swapChain, imageCount, null);

            LongBuffer pSwapchainImages = stack.mallocLong(imageCount.get(0));

            vkGetSwapchainImagesKHR(device, swapChain, imageCount, pSwapchainImages);

            swapChainImages = new ArrayList<>(imageCount.get(0));

            Initializer.LOGGER.info("requested Images: "+pSwapchainImages.capacity());

            this.width = extent2D.width();
            this.height = extent2D.height();

            for(int i = 0;i < pSwapchainImages.capacity(); i++) {
                long imageId = pSwapchainImages.get(i);
                long imageView = VulkanImage.createImageView(imageId, this.format, VK_IMAGE_ASPECT_COLOR_BIT, 1);

                swapChainImages.add(new VulkanImage(imageId, this.format, 1, this.width, this.height, 4, 0, imageView));
            }
            currentLayout = new int[this.swapChainImages.size()];

            createDepthResources();

            //RenderPass
            if(this.renderPass == null)
                createRenderPass();

            if(!DYNAMIC_RENDERING)
                createFramebuffers();

        }
    }
    public static String getDisplayModeString(int requestedMode) {
        return switch(requestedMode)
        {
            case VK_PRESENT_MODE_IMMEDIATE_KHR -> "Immediate";
            case VK_PRESENT_MODE_MAILBOX_KHR -> "FastSync";
            case VK_PRESENT_MODE_FIFO_KHR -> "VSync";
            case VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "Adaptive VSync";
            case VK_PRESENT_MODE_SHARED_DEMAND_REFRESH_KHR -> "Shared Demand Refresh";
            case VK_PRESENT_MODE_SHARED_CONTINUOUS_REFRESH_KHR ->  "Shared Continuous Refresh";
            default -> throw new IllegalStateException("Unexpected value: " + requestedMode);
        };
    }
    private void createRenderPass() {
        this.hasColorAttachment = true;
        this.hasDepthAttachment = true;

        this.renderPass = new RenderPass.Builder(this).build();
    }

    private void createFramebuffers() {

        try(MemoryStack stack = MemoryStack.stackPush()) {
            for(int i = 0; i< framebuffer.length; i++) {

                //attachments = stack.mallocLong(1);

//            var value = imagelessFramebuffers ? 1 : 1.0f;


                LongBuffer pFramebuffer = stack.mallocLong(1);

                VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack);
                framebufferInfo.sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
                framebufferInfo.flags(imagelessFramebuffers ? VK12.VK_FRAMEBUFFER_CREATE_IMAGELESS_BIT : 0);
                framebufferInfo.pNext(FrameBufferAttachments(stack));
                framebufferInfo.renderPass(this.renderPass.getId());
                framebufferInfo.width(this.width);
                framebufferInfo.height(this.height);
                framebufferInfo.layers(1);
                framebufferInfo.attachmentCount(2);
                framebufferInfo.pAttachments(imagelessFramebuffers ? null : stack.longs(this.swapChainImages.get(i).getImageView(), depthAttachment.getImageView()));


                if (vkCreateFramebuffer(Vulkan.getDevice(), framebufferInfo, null, pFramebuffer) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create framebuffer");
                }


                this.framebuffer[i] = pFramebuffer.get(0);
            }
        }
    }

    private long FrameBufferAttachments(MemoryStack stack) {
        if(imagelessFramebuffers) {

            VkFramebufferAttachmentImageInfo.Buffer vkFramebufferAttachmentImageInfo = VkFramebufferAttachmentImageInfo.calloc(2, stack);


            vkFramebufferAttachmentImageInfo.get(0)
                    .sType$Default()
                    .pNext(NULL)
                    .flags(0)
                    .width(width)
                    .height(height)
                    .pViewFormats(stack.ints(this.format))
                    .layerCount(1)
                    .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);

            vkFramebufferAttachmentImageInfo.get(1)
                    .sType$Default()
                    .pNext(NULL)
                    .flags(0)
                    .width(width)
                    .height(height)
                    .pViewFormats(stack.ints(DEFAULT_DEPTH_FORMAT))
                    .layerCount(1)
                    .usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT);


            return VkFramebufferAttachmentsCreateInfo.calloc(stack)
                    .sType$Default()
                    .pAttachmentImageInfos(vkFramebufferAttachmentImageInfo).address();
        }
        return VK_NULL_HANDLE;
    }

    public void beginRenderPass(VkCommandBuffer commandBuffer, MemoryStack stack) {
        if(DYNAMIC_RENDERING) {
//            this.colorAttachmentLayout(stack, commandBuffer, Drawer.getCurrentFrame());
//            beginDynamicRendering(commandBuffer, stack);

            this.renderPass.beginDynamicRendering(commandBuffer, stack);
        }
        else {
            int imageIndex = Renderer.getImageIndex();
            if(imagelessFramebuffers) this.renderPass.beginRenderPassImageless(commandBuffer, this.framebuffer[0], stack, this.swapChainImages.get(imageIndex).getImageView(), depthAttachment.getImageView());
            else this.renderPass.beginRenderPass(commandBuffer, this.framebuffer[imageIndex], stack);
        }

        Renderer.getInstance().setBoundRenderPass(renderPass);
        Renderer.getInstance().setBoundFramebuffer(this);
    }

    public void cleanUp() {
        VkDevice device = Vulkan.getDevice();

        for (long l : framebuffer) {
            vkDestroyFramebuffer(device, l, null);
        }


        vkDestroySwapchainKHR(device, this.swapChain, null);
        swapChainImages.forEach(image -> vkDestroyImageView(device, image.getImageView(), null));

        this.depthAttachment.free();
    }

    private void createDepthResources() {
        this.depthAttachment = VulkanImage.createDepthImage(depthFormat, this.width, this.height,
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
                false, false);
    }

    public long getId() {
        return swapChain;
    }

    public List<VulkanImage> getImages() {
        return swapChainImages;
    }

    public long getImageId(int i) {
        return swapChainImages.get(i).getId();
    }

    public VkExtent2D getExtent() {
        return extent2D;
    }

    public VulkanImage getColorAttachment() {
        return this.swapChainImages.get(Renderer.getImageIndex());
    }

    public long getImageView(int i) { return this.swapChainImages.get(i).getImageView(); }

    private VkSurfaceFormatKHR getFormat(VkSurfaceFormatKHR.Buffer availableFormats) {
        List<VkSurfaceFormatKHR> list = availableFormats.stream().toList();

        VkSurfaceFormatKHR format = list.get(0);

        for (VkSurfaceFormatKHR availableFormat : list) {
            if (availableFormat.format() == VK_FORMAT_R8G8B8A8_UNORM && availableFormat.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
                return availableFormat;

            if (availableFormat.format() == VK_FORMAT_B8G8R8A8_UNORM && availableFormat.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                format = availableFormat;
            }
        }

        if(format.format() == VK_FORMAT_B8G8R8A8_UNORM)
            isBGRAformat = true;
        return format;
    }

    private int getPresentMode(IntBuffer availablePresentModes) {
        int requestedMode = supportedImageModes[Initializer.CONFIG.currentDisplayModeIndex];

        //Display Modes can vary between Windowed and Fullscreen, so can't optimise out this loop
        String displayModeString = getDisplayModeString(requestedMode);
        for(int i = 0; i < availablePresentModes.capacity(); i++) {
            if(availablePresentModes.get(i) == requestedMode) {
                Initializer.LOGGER.info("Using Display mode: "+ displayModeString);
                return requestedMode;
            }
        }

        Initializer.LOGGER.info(displayModeString + " mode not supported!: using VSync mode as Fallback");
        return VK_PRESENT_MODE_FIFO_KHR;

    }

    private static VkExtent2D getExtent(VkSurfaceCapabilitiesKHR capabilities) {

        if(capabilities.currentExtent().width() != UINT32_MAX) {
            return capabilities.currentExtent();
        }

        //Fallback
        IntBuffer width = stackGet().ints(0);
        IntBuffer height = stackGet().ints(0);

        glfwGetFramebufferSize(window, width, height);

        VkExtent2D actualExtent = VkExtent2D.mallocStack().set(width.get(0), height.get(0));

        VkExtent2D minExtent = capabilities.minImageExtent();
        VkExtent2D maxExtent = capabilities.maxImageExtent();

        actualExtent.width(MathUtil.clamp(minExtent.width(), maxExtent.width(), actualExtent.width()));
        actualExtent.height(MathUtil.clamp(minExtent.height(), maxExtent.height(), actualExtent.height()));

        return actualExtent;
    }

    public boolean isVsync() {
        return vsync;
    }

    public void setVsync(boolean vsync) {
        this.vsync = vsync;
    }

    public RenderPass getRenderPass() {
        return renderPass;
    }

    public int getFramesNum() { return Initializer.CONFIG.frameQueueSize; }
    public int getImagesNum() { return Initializer.CONFIG.minImageCount; }
}
