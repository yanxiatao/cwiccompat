package dev.chexson.cwiccompat.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(targets = "dev.djefrey.colorwheel.compile.ClrwlProgram", remap = false)
public abstract class ClrwlProgramMixin {
    private static final Pattern GLOBAL_IN_DECLARATION = Pattern.compile(
            "(?m)^\\s*((?:(?:flat|smooth|noperspective|centroid|sample|invariant)\\s+)*)in\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(\\[[^;\\n]*])?\\s*;"
    );
    private static final Pattern GLOBAL_OUT_DECLARATION = Pattern.compile(
            "(?m)^\\s*((?:(?:flat|smooth|noperspective|centroid|sample|invariant)\\s+)*)out\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(\\[[^;\\n]*])?\\s*;"
    );

    @ModifyArgs(
            method = "createProgram",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/djefrey/colorwheel/compile/ClrwlProgram;<init>(Ljava/lang/String;Ldev/djefrey/colorwheel/shaderpack/ClrwlProgramId;Ldev/djefrey/colorwheel/shaderpack/ClrwlShaderProperties;Ljava/lang/String;Ljava/util/Optional;Ljava/lang/String;Lnet/irisshaders/iris/uniforms/custom/CustomUniforms;Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;)V",
                    remap = false
            ),
            remap = false
    )
    private static void cwiccompat$patchColorwheelStageInterfaces(final Args args) {
        final String vertex = args.get(3);
        @SuppressWarnings("unchecked")
        final Optional<String> geometry = args.get(4);
        final String fragment = args.get(5);

        final PatchedStages patched = cwiccompat$patchMissingStageInputs(vertex, geometry, fragment);
        args.set(3, patched.vertex());
        args.set(4, patched.geometry());
        args.set(5, patched.fragment());
    }

    private static PatchedStages cwiccompat$patchMissingStageInputs(
            final String vertex,
            final Optional<String> geometry,
            final String fragment
    ) {
        String patchedVertex = vertex;
        Optional<String> patchedGeometry = geometry;
        String patchedFragment = fragment;

        final Map<String, Decl> vertexOutputs = cwiccompat$findDeclarations(vertex, GLOBAL_OUT_DECLARATION);

        if (geometry.isPresent()) {
            String geometrySource = geometry.get();
            final Map<String, Decl> geometryInputs = cwiccompat$findDeclarations(geometrySource, GLOBAL_IN_DECLARATION);
            final Map<String, Decl> geometryOutputs = cwiccompat$findDeclarations(geometrySource, GLOBAL_OUT_DECLARATION);
            final Map<String, Decl> fragmentInputs = cwiccompat$findDeclarations(fragment, GLOBAL_IN_DECLARATION);

            patchedVertex = cwiccompat$ensureVertexOutputs(vertex, vertexOutputs, geometryInputs.values());

            for (Decl fragmentInput : fragmentInputs.values()) {
                if (geometryOutputs.containsKey(fragmentInput.name())) {
                    continue;
                }

                final String alias = "cwiccompat_" + fragmentInput.name();
                geometrySource = cwiccompat$ensureGeometryOutput(geometrySource, geometryInputs.get(fragmentInput.name()), fragmentInput, alias);
                patchedFragment = cwiccompat$rewriteFragmentInputAlias(patchedFragment, fragmentInput, alias);
            }

            patchedGeometry = Optional.of(geometrySource);
        } else {
            final Map<String, Decl> fragmentInputs = cwiccompat$findDeclarations(fragment, GLOBAL_IN_DECLARATION);
            patchedVertex = cwiccompat$ensureVertexOutputs(vertex, vertexOutputs, fragmentInputs.values());
        }

        return new PatchedStages(patchedVertex, patchedGeometry, patchedFragment);
    }

    private static String cwiccompat$ensureVertexOutputs(
            final String source,
            final Map<String, Decl> existingOutputs,
            final Collection<Decl> requiredInputs
    ) {
        String patched = source;

        for (Decl input : requiredInputs) {
            if (existingOutputs.containsKey(input.name())) {
                continue;
            }

            patched = cwiccompat$injectBeforeMain(patched, input.qualifiers() + "out " + input.type() + " " + input.name() + ";\r\n\r\n");
            patched = cwiccompat$ensureAssignedInFunction(
                    patched,
                    "void main()",
                    input.name(),
                    input.name() + " = " + cwiccompat$defaultExpr(input.type()) + ";"
            );
            existingOutputs.put(input.name(), input);
        }

        return patched;
    }

    private static String cwiccompat$ensureGeometryOutput(
            final String source,
            final Decl matchingInput,
            final Decl fragmentInput,
            final String alias
    ) {
        String patched = source;
        final String aliasDecl = fragmentInput.qualifiers() + "out " + fragmentInput.type() + " " + alias + ";\r\n\r\n";

        if (!patched.contains("out " + fragmentInput.type() + " " + alias + ";")) {
            patched = cwiccompat$injectBeforeMain(patched, aliasDecl);
        }

        final String assignment;
        if (matchingInput != null) {
            assignment = alias + " = " + matchingInput.name() + "[i];";
            if (patched.contains("void clrwl_setVertexOut(int i)")) {
                patched = cwiccompat$ensureAssignedInFunction(patched, "void clrwl_setVertexOut(int i)", alias, assignment);
            } else {
                patched = cwiccompat$ensureAssignedInFunction(patched, "void main()", alias, alias + " = " + cwiccompat$defaultExpr(fragmentInput.type()) + ";");
            }
        } else {
            patched = cwiccompat$ensureAssignedInFunction(patched, "void main()", alias, alias + " = " + cwiccompat$defaultExpr(fragmentInput.type()) + ";");
        }

        return patched;
    }

    private static String cwiccompat$rewriteFragmentInputAlias(final String source, final Decl input, final String alias) {
        final String aliasDecl = input.qualifiers() + "in " + input.type() + " " + alias + ";";
        final Pattern declarationPattern = Pattern.compile(
                "(?m)^\\s*" + Pattern.quote(input.qualifiers()) + "in\\s+" + Pattern.quote(input.type()) + "\\s+" + Pattern.quote(input.name()) + "\\s*;\\s*$"
        );

        String patched = declarationPattern.matcher(source).replaceFirst(aliasDecl);

        if (!patched.contains("#define " + input.name() + " " + alias)) {
            patched = cwiccompat$injectBeforeMain(patched, "#define " + input.name() + " " + alias + "\r\n\r\n");
        }

        return patched;
    }

    private static Map<String, Decl> cwiccompat$findDeclarations(final String source, final Pattern pattern) {
        final Map<String, Decl> declarations = new LinkedHashMap<>();
        final Matcher matcher = pattern.matcher(source);

        while (matcher.find()) {
            final String qualifiers = matcher.group(1) == null ? "" : matcher.group(1);
            final String type = matcher.group(2);
            final String name = matcher.group(3);
            final boolean array = matcher.group(4) != null && !matcher.group(4).isBlank();

            declarations.putIfAbsent(name, new Decl(qualifiers, type, name, array));
        }

        return declarations;
    }

    private static String cwiccompat$ensureAssignedInFunction(
            final String source,
            final String signature,
            final String variable,
            final String assignment
    ) {
        if (cwiccompat$hasAssignment(source, variable)) {
            return source;
        }

        final int functionIndex = source.indexOf(signature);
        if (functionIndex < 0) {
            return source;
        }

        final int braceIndex = source.indexOf('{', functionIndex);
        if (braceIndex < 0) {
            return source;
        }

        return source.substring(0, braceIndex + 1) + "\r\n    " + assignment + "\r\n" + source.substring(braceIndex + 1);
    }

    private static boolean cwiccompat$hasAssignment(final String source, final String variable) {
        return Pattern.compile("\\b" + Pattern.quote(variable) + "\\s*=").matcher(source).find();
    }

    private static String cwiccompat$injectBeforeMain(final String source, final String injection) {
        final int mainIndex = source.indexOf("void main()");
        if (mainIndex < 0) {
            return source;
        }

        return source.substring(0, mainIndex) + injection + source.substring(mainIndex);
    }

    private static String cwiccompat$defaultExpr(final String type) {
        if ("bool".equals(type)) {
            return "false";
        }

        return type + "(0)";
    }

    private record Decl(String qualifiers, String type, String name, boolean array) {
    }

    private record PatchedStages(String vertex, Optional<String> geometry, String fragment) {
    }
}
