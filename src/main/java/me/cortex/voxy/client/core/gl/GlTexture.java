package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.util.TrackedObject;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.ARBFramebufferObject.glDeleteFramebuffers;
import static org.lwjgl.opengl.ARBFramebufferObject.glGenFramebuffers;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_STENCIL;
import static org.lwjgl.opengl.GL44C.glClearTexImage;
import static org.lwjgl.opengl.GL45C.glCreateTextures;
import static org.lwjgl.opengl.GL45C.glTextureStorage2D;

public class GlTexture extends TrackedObject {
    public final int id;
    private final int type;
    private int storedWidth;
    private int storedHeight;
    private int storedFormat;
    private int storedLevels;

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
        this.storedFormat = format;
        this.storedLevels = levels;
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
        // Determine the appropriate pixel format for clearing based on internal format
        int pixelFormat;
        int pixelType;
        switch (this.storedFormat) {
            case GL_DEPTH_COMPONENT:
            case 0x81A5: // GL_DEPTH_COMPONENT16
            case 0x81A6: // GL_DEPTH_COMPONENT24
            case 0x81A7: // GL_DEPTH_COMPONENT32
            case 0x8CAC: // GL_DEPTH_COMPONENT32F
                pixelFormat = GL_DEPTH_COMPONENT;
                pixelType = GL_FLOAT;
                break;
            case GL_DEPTH_STENCIL:
            case 0x88F0: // GL_DEPTH24_STENCIL8
            case 0x8CAD: // GL_DEPTH32F_STENCIL8
                pixelFormat = GL_DEPTH_STENCIL;
                pixelType = 0x84FA; // GL_UNSIGNED_INT_24_8
                break;
            default:
                pixelFormat = GL_RGBA;
                pixelType = GL_UNSIGNED_BYTE;
                break;
        }
        for (int lvl = 0; lvl < this.storedLevels; lvl++) {
            glClearTexImage(this.id, lvl, pixelFormat, pixelType, (ByteBuffer) null);
        }
        return this;
    }

    @Override
    public void free() {
        super.free0();
        glDeleteTextures(this.id);
    }
}
