package net.vulkanmod.vulkan.shader.parser;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GlslConverter {

    private final Pattern uniformPattern = Pattern.compile("uniform ([a-zA-Z0-9_]+) ([a-zA-Z0-9_]+) (.*)");
    private final Pattern inOutPattern = Pattern.compile("(in|out) ([a-zA-Z0-9_]+) ([a-zA-Z0-9_]+) (.*)");

    private UBO uniformBlock;
    private List<ImageDescriptor> samplerList;

    public void process(VertexFormat vertexFormat, String vertShader, String fragShader) {
        this.uniformBlock = new UBO();
        this.samplerList = new ArrayList<>();

        // TODO: version

        for (String line : vertShader.split("\n")) {
            if (parseLine(line) != null) {
                continue;
            }
        }

        for (String line : fragShader.split("\n")) {
            if (parseLine(line) != null) {
                continue;
            }
        }
    }

    private String parseLine(String line) {
        Matcher matcher;

        if (matcher = uniformPattern.matcher(line)) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            String value = matcher.group(3);

            // TODO: parse type

            uniformBlock.addUniform(name, value);
            return null;
        } else if (matcher = inOutPattern.matcher(line)) {
            String direction = matcher.group(1);
            String type = matcher.group(2);
            String name = matcher.group(3);

            // TODO: parse type

            if (direction.equals("in")) {
                // TODO: parse vertex attributes
            } else {
                // TODO: parse fragment attributes
            }

            return null;
        }

        return line;
    }

    public UBO getUBO() {
        return uniformBlock;
    }

    public List<ImageDescriptor> getSamplerList() {
        return samplerList;
    }
}
