package dev.chexson.cwiccompat.mixin.client;

import com.mojang.blaze3d.platform.GlStateManager;
import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.shader.GlShader;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.gl.shader.ShaderWorkarounds;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.KHRDebug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;
import java.util.regex.Pattern;

@Mixin(value = GlShader.class, remap = false)
public abstract class IrisGlShaderMixin {
    private static final Pattern FLW_LIGHT_IF_PATTERN = Pattern.compile("if\\s*\\(\\s*flw_light\\s*\\(");

    private static final String COMPAT_HELPER =
            "bool _cwic_tryLight(vec3 worldPos, vec3 normal, inout FlwLightAo light) {\r\n" +
            "    vec2 cwicLight;\r\n" +
            "    if (!flw_lightFetch(ivec3(floor(worldPos)) + flw_renderOrigin, cwicLight)) {\r\n" +
            "        return false;\r\n" +
            "    }\r\n" +
            "    light.light = cwicLight;\r\n" +
            "    light.ao = 1.0;\r\n" +
            "    return true;\r\n" +
            "}\r\n\r\n";

    @Inject(method = "createShader", at = @At("HEAD"), cancellable = true)
    private static void cwiccompat$retryColorwheelVertexShader(
            final ShaderType type,
            final String name,
            final String src,
            final CallbackInfoReturnable<Integer> cir
    ) {
        if (!cwiccompat$shouldIntercept(type, name, src)) {
            return;
        }

        final ShaderCompileResult first = cwiccompat$compile(type, name, src);
        if (first.success()) {
            cir.setReturnValue(first.handle());
            return;
        }

        if (!cwiccompat$isLightSignatureFailure(first.log())) {
            throw new ShaderCompileException(name, first.log());
        }

        final String patched = cwiccompat$patchSource(src);
        final ShaderCompileResult second = cwiccompat$compile(type, name, patched);
        if (second.success()) {
            cir.setReturnValue(second.handle());
            return;
        }

        throw new ShaderCompileException(name, second.log());
    }

    private static boolean cwiccompat$shouldIntercept(final ShaderType type, final String name, final String src) {
        return type == ShaderType.VERTEX
                && name != null
                && name.startsWith("clrwl_")
                && name.endsWith(".vsh")
                && src != null
                && src.contains("flw_light(")
                && src.contains("FlwLightAo");
    }

    private static boolean cwiccompat$isLightSignatureFailure(final String log) {
        return log != null
                && log.contains("unable to find compatible overloaded function")
                && log.contains("flw_light");
    }

    private static String cwiccompat$patchSource(final String source) {
        if (source.contains("_cwic_tryLight(")) {
            return source;
        }

        final String replaced = FLW_LIGHT_IF_PATTERN.matcher(source).replaceAll("if (_cwic_tryLight(");
        if (replaced.equals(source)) {
            return source;
        }

        final int mainIndex = replaced.indexOf("void main()");
        if (mainIndex < 0) {
            return replaced;
        }

        return replaced.substring(0, mainIndex) + COMPAT_HELPER + replaced.substring(mainIndex);
    }

    private static ShaderCompileResult cwiccompat$compile(final ShaderType type, final String name, final String src) {
        final int handle = GlStateManager.glCreateShader(type.id);
        ShaderWorkarounds.safeShaderSource(handle, src);
        GlStateManager.glCompileShader(handle);

        GLDebug.nameObject(KHRDebug.GL_SHADER, handle, name + "(" + type.name().toLowerCase(Locale.ROOT) + ")");

        final String log = IrisRenderSystem.getShaderInfoLog(handle);
        final int result = GlStateManager.glGetShaderi(handle, GL20C.GL_COMPILE_STATUS);

        if (result == GL20C.GL_TRUE) {
            return new ShaderCompileResult(handle, log, true);
        }

        GlStateManager.glDeleteShader(handle);
        return new ShaderCompileResult(0, log, false);
    }

    private record ShaderCompileResult(int handle, String log, boolean success) {
    }
}
