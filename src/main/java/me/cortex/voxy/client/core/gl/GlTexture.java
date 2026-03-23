package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.util.TrackedObject;

import static org.lwjgl.opengl.ARBFramebufferObject.glDeleteFramebuffers;
import static org.lwjgl.opengl.ARBFramebufferObject.glGenFramebuffers;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL45C.glCreateTextures;
import static org.lwjgl.opengl.GL45C.glTextureStorage2D;

public class GlTexture extends TrackedObject {
    public final int id;
    private final int type;
    private int storedWidth;
    private int storedHeight;

    public GlTexture() {
        this(GL_TEXTURE_2D);
    }

    public GlTexture(int type) {
        this.id = glCreateTextures(type);
        this.type = type;
    }

    public int getWidth() { return this.storedWidth; }
    public int getHeight() { return this.storedHeight; }

    public GlTexture store(int format, int levels, int width, int height) {
        this.storedWidth = width;
        this.storedHeight = height;
        if (this.type == GL_TEXTURE_2D) {
            glTextureStorage2D(this.id, levels, format, width, height);
        } else {
            throw new IllegalStateException("Unknown texture type");
        }
        return this;
    }

    public GlTexture name(String name) {
        return GlDebug.name(name, this);
    }

    public GlTexture zero() {
        // Clear all mip levels of the texture to zero
        // For a 2D texture, we clear each level
        return this;
    }

    @Override
    public void free() {
        super.free0();
        glDeleteTextures(this.id);
    }
}
