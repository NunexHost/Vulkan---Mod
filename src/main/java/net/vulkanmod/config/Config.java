package net.vulkanmod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.vulkanmod.render.vertex.TerrainRenderType;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

public class Config {

    public int frameQueueSize = 2;
    public VideoResolution resolution = VideoResolution.getFirstAvailable();
    public boolean windowedFullscreen = false;
    public boolean guiOptimizations = false;
    public int advCulling = 2;
    public boolean indirectDraw = false;
    public boolean uniqueOpaqueLayer = true;
    public boolean perRenderTypeAreaBuffers = true;
    public boolean useGigaBarriers = false;
    public boolean renderSky = true;
    public boolean entityCulling = true;
    public boolean animations = true;

    private static final int max = Runtime.getRuntime().availableProcessors();
    public int chunkLoadFactor = max /2;
    public int buildLimit = 512;
    public int ssaaPreset = 0;
    public boolean ssaaQuality;

    private static Path path;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static Config load(Path path) {
        Config config;
        Config.path = path;

        if (Files.exists(path)) {
            try (FileReader fileReader = new FileReader(path.toFile())) {
                config = GSON.fromJson(fileReader, Config.class);
            }
            catch (IOException exception) {
                throw new RuntimeException(exception.getMessage());
            }
        }
        else {
            config = null;
        }

        return config;
    }

    public void write() {

        if(!Files.exists(path.getParent())) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        try {
            Files.write(path, Collections.singleton(GSON.toJson(this)));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
