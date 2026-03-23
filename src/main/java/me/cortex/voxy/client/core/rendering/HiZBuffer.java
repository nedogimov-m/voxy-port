package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import org.lwjgl.opengl.GL11;

import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL33.glGenSamplers;
import static org.lwjgl.opengl.GL33C.glDeleteSamplers;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL42C.GL_FRAMEBUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL45C.glBlitNamedFramebuffer;
import static org.lwjgl.opengl.GL45C.glNamedFramebufferTexture;
import static org.lwjgl.opengl.GL45C.glTextureBarrier;

public class HiZBuffer {
    private final Shader hiz = Shader.make()
            .add(ShaderType.VERTEX, "voxy:hiz/blit.vsh")
            .add(ShaderType.FRAGMENT, "voxy:hiz/blit.fsh")
            .compile();
    private final GlFramebuffer fb = new GlFramebuffer();
    // Temporary framebuffer for reading the source depth texture during blit
    private final GlFramebuffer srcFb = new GlFramebuffer();
    private final int sampler = glGenSamplers();
    private GlTexture texture;
    private int levels;
    private int width;
    private int height;

    public HiZBuffer() {
        glNamedFramebufferDrawBuffer(this.fb.id, GL_NONE);
        glNamedFramebufferDrawBuffer(this.srcFb.id, GL_NONE);
    }

    private void alloc(int width, int height) {
        this.levels = (int)Math.ceil(Math.log(Math.max(width, height))/Math.log(2));
        //We dont care about e.g. 1x1 size texture since you dont get meshlets that big to cover such a large area
        this.levels -= 3;//Arbitrary size, shinks the max level by alot and saves a significant amount of processing time
        // (could probably increase it to be defined by a max meshlet coverage computation thing)

        this.texture = new GlTexture().store(GL_DEPTH_COMPONENT32, this.levels, width, height);
        glTextureParameteri(this.texture.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(this.texture.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTextureParameteri(this.texture.id, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        glTextureParameteri(this.texture.id, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTextureParameteri(this.texture.id, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glSamplerParameteri(this.sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glSamplerParameteri(this.sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.sampler, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        glSamplerParameteri(this.sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(this.sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        this.width  = width;
        this.height = height;
    }

    public void buildMipChain(int srcDepthTex, int width, int height) {
        if (this.width != width || this.height != height) {
            if (this.texture != null) {
                this.texture.free();
                this.texture = null;
            }
            this.alloc(width, height);
        }
        glBindVertexArray(me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer.INSTANCE.getVao());
        int boundFB = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        this.hiz.bind();
        this.fb.bind(GL_DEPTH_ATTACHMENT, this.texture, 0).verify();
        glBindFramebuffer(GL_FRAMEBUFFER, this.fb.id);

        glDepthFunc(GL_ALWAYS);

        // Use glBlitNamedFramebuffer instead of glCopyImageSubData to handle
        // depth format mismatches (e.g. GL_DEPTH24_STENCIL8 source -> GL_DEPTH_COMPONENT32 dest).
        // glCopyImageSubData requires format-compatible textures which fails here.
        {
            // Query source texture's internal format to determine correct attachment point
            int srcFormat = glGetTextureLevelParameteri(srcDepthTex, 0, GL_TEXTURE_INTERNAL_FORMAT);
            int attachment = (srcFormat == GL_DEPTH24_STENCIL8 || srcFormat == GL_DEPTH32F_STENCIL8)
                    ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT;
            glNamedFramebufferTexture(this.srcFb.id, attachment, srcDepthTex, 0);
            // Clear the other attachment to avoid incomplete framebuffer
            if (attachment == GL_DEPTH_STENCIL_ATTACHMENT) {
                // No need to clear GL_DEPTH_ATTACHMENT separately — DEPTH_STENCIL_ATTACHMENT covers both
            } else {
                glNamedFramebufferTexture(this.srcFb.id, GL_STENCIL_ATTACHMENT, 0, 0);
            }
        }
        glBlitNamedFramebuffer(this.srcFb.id, this.fb.id,
                0, 0, width, height,
                0, 0, width, height,
                GL_DEPTH_BUFFER_BIT, GL_NEAREST);


        glBindTextureUnit(0, this.texture.id);
        glBindSampler(0, this.sampler);
        glUniform1i(0, 0);
        int cw = this.width;
        int ch = this.height;
        for (int i = 0; i < this.levels-1; i++) {
            glTextureParameteri(this.texture.id, GL_TEXTURE_BASE_LEVEL, i);
            glTextureParameteri(this.texture.id, GL_TEXTURE_MAX_LEVEL, i);
            this.fb.bind(GL_DEPTH_ATTACHMENT, this.texture, i+1);
            cw /= 2; ch /= 2; glViewport(0, 0, cw, ch);
            glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
            glTextureBarrier();
            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
        }
        glTextureParameteri(this.texture.id, GL_TEXTURE_BASE_LEVEL, 0);
        glTextureParameteri(this.texture.id, GL_TEXTURE_MAX_LEVEL, this.levels-1);//TODO: CHECK IF ITS -1 or -0

        glDepthFunc(GL_LEQUAL);
        glBindFramebuffer(GL_FRAMEBUFFER, boundFB);
        glViewport(0, 0, width, height);
        glBindVertexArray(0);
    }

    public void free() {
        this.fb.free();
        this.srcFb.free();
        this.texture.free();
        this.texture = null;
        glDeleteSamplers(this.sampler);
        this.hiz.free();
    }

    public int getHizTextureId() {
        return this.texture.id;
    }

    public int getPackedLevels() {
        return (this.width<<16)|this.height;
    }
}
