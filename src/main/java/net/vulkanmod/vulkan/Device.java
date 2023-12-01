package net.vulkanmod.vulkan;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.queue.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Set;

import static java.util.stream.Collectors.toSet;
import static net.vulkanmod.vulkan.queue.Queue.*;
import static net.vulkanmod.vulkan.queue.QueueFamilyIndices.findQueueFamilies;
import static net.vulkanmod.vulkan.util.VUtil.asPointerBuffer;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_1;

public class Device {

    public static VkPhysicalDevice physicalDevice;
    public static VkDevice device;

    public static DeviceInfo deviceInfo;

    public static VkPhysicalDeviceProperties deviceProperties;
    public static VkPhysicalDeviceMemoryProperties memoryProperties;

    public static SurfaceProperties surfaceProperties;


    static void pickPhysicalDevice(VkInstance instance) {

        try(MemoryStack stack = stackPush()) {

            IntBuffer deviceCount = stack.ints(0);

            vkEnumeratePhysicalDevices(instance, deviceCount, null);

            if(deviceCount.get(0) == 0) {
                throw new RuntimeException("Failed to find GPUs with Vulkan support");
            }

            PointerBuffer ppPhysicalDevices = stack.mallocPointer(deviceCount.get(0));

            vkEnumeratePhysicalDevices(instance, deviceCount, ppPhysicalDevices);

            ArrayList<GPUCandidate> dGPUs = new ArrayList<>();
            ArrayList<GPUCandidate> iGPUs = new ArrayList<>();
            ArrayList<GPUCandidate> misc = new ArrayList<>();

            final GPUCandidate currentDevice;



            Initializer.LOGGER.info(deviceCount.get(0) + " Devices Detected");



            for(int i = 0; i < ppPhysicalDevices.capacity();i++) {

                 var a = new VkPhysicalDevice(ppPhysicalDevices.get(i), instance);

                VkPhysicalDeviceProperties deviceProperties = VkPhysicalDeviceProperties.malloc(stack);
                vkGetPhysicalDeviceProperties(a, deviceProperties);


                int i1 = deviceProperties.deviceType();
                Initializer.LOGGER.info("Checking GPU Device: "+deviceProperties.deviceNameString()+"...");
                Initializer.LOGGER.info("Type: "+ getDeviceTypeString(i1));
                if(isDeviceSuitable(a)) {
                    final GPUCandidate e = new GPUCandidate(a, deviceProperties.deviceNameString(), i1);
                    switch (i1)
                    {
                        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> dGPUs.add(e);
                        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU-> iGPUs.add(e);
                        case VK_PHYSICAL_DEVICE_TYPE_OTHER, VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU, VK_PHYSICAL_DEVICE_TYPE_CPU -> misc.add(e);
                        default -> Initializer.LOGGER.error("Device doesn't seem to be an actual GPU, Skipping...");
                    }

                }
            }


            if(!dGPUs.isEmpty()) currentDevice = dGPUs.get(0);
            else if(!iGPUs.isEmpty()) currentDevice = iGPUs.get(0);
            else if(!misc.isEmpty()) currentDevice = misc.get(0);
            else {
                Initializer.LOGGER.error(DeviceInfo.debugString(ppPhysicalDevices, Vulkan.REQUIRED_EXTENSION, instance));
                throw new RuntimeException("Failed to find a suitable GPU");
            }

            Initializer.LOGGER.info("Using GPU Device: "+currentDevice.deviceName());
            physicalDevice = currentDevice.physicalDevice();

            //Get device properties
            deviceProperties = VkPhysicalDeviceProperties.malloc();
            vkGetPhysicalDeviceProperties(physicalDevice, deviceProperties);

            memoryProperties = VkPhysicalDeviceMemoryProperties.malloc();
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);

            surfaceProperties = querySurfaceProperties(physicalDevice, stack);

            deviceInfo = new DeviceInfo(physicalDevice, deviceProperties);
        }
    }

    private static String getDeviceTypeString(int i) {
        return switch (i) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> "DISCRETE_GPU";
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> "INTEGRATED_GPU";
            case VK_PHYSICAL_DEVICE_TYPE_OTHER -> "OTHER";
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> "VIRTUAL_GPU";
            case VK_PHYSICAL_DEVICE_TYPE_CPU -> "CPU";
            default -> "UNKNOWN";
        };
    }

    static void createLogicalDevice() {

        try(MemoryStack stack = stackPush()) {

            int[] uniqueQueueFamilies = QueueFamilyIndices.unique();

            VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(uniqueQueueFamilies.length, stack);

            for(int i = 0;i < uniqueQueueFamilies.length;i++) {
                VkDeviceQueueCreateInfo queueCreateInfo = queueCreateInfos.get(i);
                queueCreateInfo.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO);
                queueCreateInfo.queueFamilyIndex(uniqueQueueFamilies[i]);
                queueCreateInfo.pQueuePriorities(stack.floats(1.0f));
            }

            VkPhysicalDeviceFeatures2 deviceFeatures = VkPhysicalDeviceFeatures2.calloc(stack);
            deviceFeatures.sType$Default();

            //TODO indirect draw option disabled in case it is not supported

            deviceFeatures.features()
                    .samplerAnisotropy(deviceInfo.availableFeatures.features().samplerAnisotropy())
                    .logicOp(deviceInfo.availableFeatures.features().logicOp())
                    .sampleRateShading(deviceInfo.availableFeatures.features().sampleRateShading());

            VkPhysicalDeviceVulkan11Features deviceVulkan11Features = VkPhysicalDeviceVulkan11Features.calloc(stack);
            deviceVulkan11Features.sType$Default();

            VkPhysicalDeviceVulkan12Features deviceVulkan12Features = VkPhysicalDeviceVulkan12Features.calloc(stack);
            deviceVulkan12Features.sType$Default()
                    .imagelessFramebuffer(true);

            if(deviceInfo.isDrawIndirectSupported()) {
                deviceFeatures.features().multiDrawIndirect(true);
                deviceVulkan11Features.shaderDrawParameters(true);
            }

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack);

            createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
            createInfo.pQueueCreateInfos(queueCreateInfos);
            // queueCreateInfoCount is automatically set

            createInfo.pNext(deviceFeatures.pNext(deviceVulkan11Features));

            //Vulkan 1.3 dynamic rendering
//            VkPhysicalDeviceVulkan13Features deviceVulkan13Features = VkPhysicalDeviceVulkan13Features.calloc(stack);
//            deviceVulkan13Features.sType$Default();
//            if(!deviceInfo.availableFeatures13.dynamicRendering())
//                throw new RuntimeException("Device does not support dynamic rendering feature.");
//
//            deviceVulkan13Features.dynamicRendering(true);
//            createInfo.pNext(deviceVulkan13Features);
//            deviceVulkan13Features.pNext(deviceVulkan11Features.address());

            createInfo.ppEnabledExtensionNames(asPointerBuffer(Vulkan.REQUIRED_EXTENSION));

//            Configuration.DEBUG_FUNCTIONS.set(true);

            if(Vulkan.ENABLE_VALIDATION_LAYERS) {
                createInfo.ppEnabledLayerNames(asPointerBuffer(Vulkan.VALIDATION_LAYERS));
            }

            PointerBuffer pDevice = stack.pointers(VK_NULL_HANDLE);

            if(vkCreateDevice(physicalDevice, createInfo, null, pDevice) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create logical device");
            }

            device = new VkDevice(pDevice.get(0), physicalDevice, createInfo, VK_API_VERSION_1_1);

//            PointerBuffer pQueue = stack.pointers(VK_NULL_HANDLE);
//
//            vkGetDeviceQueue(device, indices.graphicsFamily, 0, pQueue);
//            graphicsQueue = new VkQueue(pQueue.get(0), device);
//
//            vkGetDeviceQueue(device, indices.presentFamily, 0, pQueue);
//            presentQueue = new VkQueue(pQueue.get(0), device);
//
//            vkGetDeviceQueue(device, indices.transferFamily, 0, pQueue);
//            transferQueue = new VkQueue(pQueue.get(0), device);


//            GraphicsQueue.createInstance(stack, indices.graphicsFamily);
//            TransferQueue.createInstance(stack, indices.transferFamily);
//            PresentQueue.createInstance(stack, indices.presentFamily);

        }
    }

    private static PointerBuffer getRequiredExtensions() {

        PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();

        if(Vulkan.ENABLE_VALIDATION_LAYERS) {

            MemoryStack stack = stackGet();

            PointerBuffer extensions = stack.mallocPointer(glfwExtensions.capacity() + 1);

            extensions.put(glfwExtensions);
            extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));

            // Rewind the buffer before returning it to reset its position back to 0
            return extensions.rewind();
        }

        return glfwExtensions;
    }

    private static boolean isDeviceSuitable(VkPhysicalDevice device) {

        boolean extensionsSupported = checkDeviceExtensionSupport(device);
        boolean swapChainAdequate = false;

        if(extensionsSupported) {
            try(MemoryStack stack = stackPush()) {
                SurfaceProperties surfaceProperties = querySurfaceProperties(device, stack);
                swapChainAdequate = surfaceProperties.formats.hasRemaining() && surfaceProperties.presentModes.hasRemaining();
            }
        }

        boolean anisotropicFilterSupported = false;
        try(MemoryStack stack = stackPush()) {
            VkPhysicalDeviceFeatures supportedFeatures = VkPhysicalDeviceFeatures.mallocStack(stack);
            vkGetPhysicalDeviceFeatures(device, supportedFeatures);
            anisotropicFilterSupported = supportedFeatures.samplerAnisotropy();
        }


        boolean hasQueues = findQueueFamilies(device);
        Initializer.LOGGER.info("   Has Queues: "+hasQueues);
        Initializer.LOGGER.info("   Has Swapchain Functionality: "+extensionsSupported);
        Initializer.LOGGER.info("   Has Presentable Surface Formats: "+swapChainAdequate);
        Initializer.LOGGER.info((hasQueues && extensionsSupported && swapChainAdequate) ? "Device Suitable!" : "Device not Suitable!");

        return hasQueues && extensionsSupported && swapChainAdequate;

    }

    private static boolean checkDeviceExtensionSupport(VkPhysicalDevice device) {

        try(MemoryStack stack = stackPush()) {

            IntBuffer extensionCount = stack.ints(0);

            vkEnumerateDeviceExtensionProperties(device, (String)null, extensionCount, null);

            VkExtensionProperties.Buffer availableExtensions = VkExtensionProperties.mallocStack(extensionCount.get(0), stack);

            vkEnumerateDeviceExtensionProperties(device, (String)null, extensionCount, availableExtensions);

            Set<String> extensions = availableExtensions.stream()
                    .map(VkExtensionProperties::extensionNameString)
                    .collect(toSet());

            extensions.removeAll(Vulkan.REQUIRED_EXTENSION);

            return availableExtensions.stream()
                    .map(VkExtensionProperties::extensionNameString)
                    .collect(toSet())
                    .containsAll(Vulkan.REQUIRED_EXTENSION);
        }
    }
    // Use the optimal most performant depth format for the specific GPU
    // Nvidia performs best with 24 bit depth, while AMD is most performant with 32-bit float
    public static int findDepthFormat() {
        return findSupportedFormat(
                VK_IMAGE_TILING_OPTIMAL,
                VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT,
                VK_FORMAT_X8_D24_UNORM_PACK32, VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT);
    }

    private static int findSupportedFormat(int tiling, int features, int... formatCandidates) {

        try(MemoryStack stack = stackPush()) {

            VkFormatProperties props = VkFormatProperties.calloc(stack);

            for (int format : formatCandidates) {

                vkGetPhysicalDeviceFormatProperties(physicalDevice, format, props);

                if (tiling == VK_IMAGE_TILING_LINEAR && (props.linearTilingFeatures() & features) == features) {
                    return format;
                } else if (tiling == VK_IMAGE_TILING_OPTIMAL && (props.optimalTilingFeatures() & features) == features) {
                    return format;
                }

            }
        }

        throw new RuntimeException("Failed to find supported format");
    }

    public static void destroy() {
        GraphicsQueue.cleanUp();
        TransferQueue.cleanUp();
        ComputeQueue.cleanUp();

        vkDestroyDevice(device, null);
    }

    public static Queue getGraphicsQueue() {
        return GraphicsQueue;
    }

    public static Queue getPresentQueue() {
        return PresentQueue;
    }

    public static Queue getTransferQueue() {
        return TransferQueue;
    }

    public static Queue getComputeQueue() {
        return ComputeQueue;
    }

    public static SurfaceProperties querySurfaceProperties(VkPhysicalDevice device, MemoryStack stack) {

        long surface = Vulkan.getSurface();
        SurfaceProperties details = new SurfaceProperties();

        details.capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface, details.capabilities);

        IntBuffer count = stack.ints(0);

        vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, null);

        if(count.get(0) != 0) {
            details.formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, details.formats);
        }

        vkGetPhysicalDeviceSurfacePresentModesKHR(device,surface, count, null);

        if(count.get(0) != 0) {
            details.presentModes = stack.mallocInt(count.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, details.presentModes);
        }

        return details;
    }

    public static class SurfaceProperties {
        public VkSurfaceCapabilitiesKHR capabilities;
        public VkSurfaceFormatKHR.Buffer formats;
        public IntBuffer presentModes;
    }

}
