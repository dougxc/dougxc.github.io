/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.hotspot.hsail;

import static com.oracle.graal.api.code.CallingConvention.Type.*;
import static com.oracle.graal.api.code.CodeUtil.*;
import static com.oracle.graal.api.code.ValueUtil.*;
import static com.oracle.graal.api.meta.LocationIdentity.*;
import static com.oracle.graal.compiler.GraalCompiler.*;

import java.lang.reflect.*;
import java.util.*;

import com.amd.okra.*;
import com.oracle.graal.api.code.*;
import com.oracle.graal.api.code.Assumptions.Assumption;
import com.oracle.graal.api.code.CallingConvention.Type;
import com.oracle.graal.api.code.CompilationResult.Call;
import com.oracle.graal.api.code.CompilationResult.CodeAnnotation;
import com.oracle.graal.api.code.CompilationResult.DataPatch;
import com.oracle.graal.api.code.CompilationResult.ExceptionHandler;
import com.oracle.graal.api.code.CompilationResult.Infopoint;
import com.oracle.graal.api.code.CompilationResult.Mark;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.asm.*;
import com.oracle.graal.asm.hsail.*;
import com.oracle.graal.compiler.gen.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.gpu.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.hotspot.*;
import com.oracle.graal.hotspot.bridge.CompilerToVM.CodeInstallResult;
import com.oracle.graal.hotspot.meta.*;
import com.oracle.graal.hotspot.nodes.*;
import com.oracle.graal.hsail.*;
import com.oracle.graal.java.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.asm.*;
import com.oracle.graal.lir.hsail.*;
import com.oracle.graal.lir.hsail.HSAILControlFlow.DeoptimizeOp;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.StructuredGraph.GuardsStage;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.nodes.type.*;
import com.oracle.graal.phases.*;
import com.oracle.graal.phases.tiers.*;

/**
 * HSAIL specific backend.
 */
public class HSAILHotSpotBackend extends HotSpotBackend {

    private Map<String, String> paramTypeMap = new HashMap<>();
    private final boolean deviceInitialized;
    // TODO: get maximum Concurrency from okra
    private int maxDeoptIndex = 8 * 40 * 64;   // see gpu_hsail.hpp

    public HSAILHotSpotBackend(HotSpotGraalRuntime runtime, HotSpotProviders providers) {
        super(runtime, providers);
        paramTypeMap.put("HotSpotResolvedPrimitiveType<int>", "s32");
        paramTypeMap.put("HotSpotResolvedPrimitiveType<float>", "f32");
        paramTypeMap.put("HotSpotResolvedPrimitiveType<double>", "f64");
        paramTypeMap.put("HotSpotResolvedPrimitiveType<long>", "s64");

        // The order of the conjunction below is important: the OkraUtil
        // call may provision the native library required by the initialize() call
        deviceInitialized = OkraUtil.okraLibExists() && initialize();
    }

    @Override
    public boolean shouldAllocateRegisters() {
        return true;
    }

    /**
     * Initializes the GPU device.
     *
     * @return whether or not initialization was successful
     */
    private static native boolean initialize();

    /**
     * Control how many threads run on simulator (used only from junit tests).
     */
    public void setSimulatorSingleThreaded() {
        String simThrEnv = System.getenv("SIMTHREADS");
        if (simThrEnv == null || !simThrEnv.equals("1")) {
            setSimulatorSingleThreaded0();
        }
    }

    private static native void setSimulatorSingleThreaded0();

    /**
     * Determines if the GPU device (or simulator) is available and initialized.
     */
    public boolean isDeviceInitialized() {
        return deviceInitialized;
    }

    /**
     * Completes the initialization of the HSAIL backend. This includes initializing the providers
     * and registering any method substitutions specified by the HSAIL backend.
     */
    @Override
    public void completeInitialization() {
        final HotSpotProviders providers = getProviders();
        HotSpotVMConfig config = getRuntime().getConfig();
        // Initialize the lowering provider.
        final HotSpotLoweringProvider lowerer = (HotSpotLoweringProvider) providers.getLowerer();
        lowerer.initialize(providers, config);

        // Register the replacements used by the HSAIL backend.
        HSAILHotSpotReplacementsImpl replacements = (HSAILHotSpotReplacementsImpl) providers.getReplacements();
        replacements.completeInitialization();
    }

    /**
     * Compiles and installs a given method to a GPU binary.
     */
    public HotSpotNmethod compileAndInstallKernel(Method method) {
        ResolvedJavaMethod javaMethod = getProviders().getMetaAccess().lookupJavaMethod(method);
        return installKernel(javaMethod, compileKernel(javaMethod, true));
    }

    /**
     * Compiles a given method to HSAIL code.
     *
     * @param makeBinary specifies whether a GPU binary should also be generated for the HSAIL code.
     *            If true, the returned value is guaranteed to have a non-zero
     *            {@linkplain ExternalCompilationResult#getEntryPoint() entry point}.
     * @return the HSAIL code compiled from {@code method}'s bytecode
     */
    public ExternalCompilationResult compileKernel(ResolvedJavaMethod method, boolean makeBinary) {
        StructuredGraph graph = new StructuredGraph(method);
        HotSpotProviders providers = getProviders();
        MetaAccessProvider metaAccess = getProviders().getMetaAccess();

        // changed this from default to help us generate deopts when needed
        OptimisticOptimizations optimisticOpts = OptimisticOptimizations.ALL;
        optimisticOpts.remove(OptimisticOptimizations.Optimization.UseExceptionProbabilityForOperations);
        new GraphBuilderPhase.Instance(metaAccess, GraphBuilderConfiguration.getSnippetDefault(), optimisticOpts).apply(graph);
        PhaseSuite<HighTierContext> graphBuilderSuite = providers.getSuites().getDefaultGraphBuilderSuite();
        CallingConvention cc = getCallingConvention(providers.getCodeCache(), Type.JavaCallee, graph.method(), false);

        // append special HSAILNonNullParametersPhase
        int numArgs = cc.getArguments().length;
        graphBuilderSuite.appendPhase(new HSAILNonNullParametersPhase(numArgs));

        Suites suites = providers.getSuites().getDefaultSuites();
        ExternalCompilationResult hsailCode = compileGraph(graph, null, cc, method, providers, this, this.getTarget(), null, graphBuilderSuite, optimisticOpts, getProfilingInfo(graph), null, suites,
                        new ExternalCompilationResult(), CompilationResultBuilderFactory.Default);

        // this code added to dump infopoints
        try (Scope s = Debug.scope("CodeGen")) {
            if (Debug.isLogEnabled()) {
                // show infopoints
                List<Infopoint> infoList = hsailCode.getInfopoints();
                Debug.log("%d HSAIL infopoints", infoList.size());
                for (Infopoint info : infoList) {
                    Debug.log(info.toString());
                    Debug.log(info.debugInfo.frame().toString());
                }
            }
        } catch (Throwable e) {
            throw Debug.handle(e);
        }

        if (makeBinary) {
            if (!deviceInitialized) {
                throw new GraalInternalError("Cannot generate GPU kernel if device is not initialized");
            }
            try (Scope ds = Debug.scope("GeneratingKernelBinary")) {
                long kernel = generateKernel(hsailCode.getTargetCode(), method.getName());
                if (kernel == 0) {
                    throw new GraalInternalError("Failed to compile HSAIL kernel");
                }
                hsailCode.setEntryPoint(kernel);
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
        }
        return hsailCode;
    }

    private static class HSAILNonNullParametersPhase extends Phase {
        // we use this to limit the stamping to exclude the final argument in an obj stream method
        private int numArgs;

        public HSAILNonNullParametersPhase(int numArgs) {
            this.numArgs = numArgs;
        }

        @Override
        protected void run(StructuredGraph graph) {
            int argCount = 0;
            for (ParameterNode param : graph.getNodes(ParameterNode.class)) {
                argCount++;
                if (argCount < numArgs && param.stamp() instanceof ObjectStamp) {
                    param.setStamp(StampFactory.declaredNonNull(((ObjectStamp) param.stamp()).type()));
                }
            }
        }
    }

    /**
     * Generates a GPU binary from HSAIL code.
     */
    private static native long generateKernel(byte[] hsailCode, String name);

    /**
     * Installs the {@linkplain ExternalCompilationResult#getEntryPoint() GPU binary} associated
     * with some given HSAIL code in the code cache and returns a {@link HotSpotNmethod} handle to
     * the installed code.
     *
     * @param hsailCode HSAIL compilation result for which a GPU binary has been generated
     * @return a handle to the binary as installed in the HotSpot code cache
     */
    public final HotSpotNmethod installKernel(ResolvedJavaMethod method, ExternalCompilationResult hsailCode) {
        assert hsailCode.getEntryPoint() != 0L;
        // code below here lifted from HotSpotCodeCacheProviders.addExternalMethod
        // used to be return getProviders().getCodeCache().addExternalMethod(method, hsailCode);
        HotSpotResolvedJavaMethod javaMethod = (HotSpotResolvedJavaMethod) method;
        if (hsailCode.getId() == -1) {
            hsailCode.setId(javaMethod.allocateCompileId(hsailCode.getEntryBCI()));
        }
        CompilationResult compilationResult = hsailCode;
        StructuredGraph hostGraph = hsailCode.getHostGraph();
        if (hostGraph != null) {
            // TODO get rid of the unverified entry point in the host code
            try (Scope ds = Debug.scope("GeneratingHostGraph")) {
                HotSpotBackend hostBackend = getRuntime().getHostBackend();
                JavaType[] parameterTypes = new JavaType[hostGraph.getNodes(ParameterNode.class).count()];
                Debug.log("Param count: %d", parameterTypes.length);
                for (int i = 0; i < parameterTypes.length; i++) {
                    ParameterNode parameter = hostGraph.getParameter(i);
                    Debug.log("Param [%d]=%d", i, parameter);
                    parameterTypes[i] = parameter.stamp().javaType(hostBackend.getProviders().getMetaAccess());
                    Debug.log(" %s", parameterTypes[i]);
                }
                CallingConvention cc = hostBackend.getProviders().getCodeCache().getRegisterConfig().getCallingConvention(Type.JavaCallee, method.getSignature().getReturnType(null), parameterTypes,
                                hostBackend.getTarget(), false);
                CompilationResult hostCode = compileGraph(hostGraph, null, cc, method, hostBackend.getProviders(), hostBackend, this.getTarget(), null,
                                hostBackend.getProviders().getSuites().getDefaultGraphBuilderSuite(), OptimisticOptimizations.NONE, null, null,
                                hostBackend.getProviders().getSuites().getDefaultSuites(), new CompilationResult(), CompilationResultBuilderFactory.Default);
                compilationResult = merge(hostCode, hsailCode);
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
        }

        HotSpotNmethod code = new HotSpotNmethod(javaMethod, hsailCode.getName(), false, true);
        HotSpotCompiledNmethod compiled = new HotSpotCompiledNmethod(getTarget(), javaMethod, compilationResult);
        CodeInstallResult result = getRuntime().getCompilerToVM().installCode(compiled, code, null);
        if (result != CodeInstallResult.OK) {
            return null;
        }
        return code;
    }

    private static ExternalCompilationResult merge(CompilationResult hostCode, ExternalCompilationResult hsailCode) {
        ExternalCompilationResult result = new ExternalCompilationResult();

        // from hsail code
        result.setEntryPoint(hsailCode.getEntryPoint());
        result.setId(hsailCode.getId());
        result.setEntryBCI(hsailCode.getEntryBCI());
        assert hsailCode.getMarks().isEmpty();
        assert hsailCode.getExceptionHandlers().isEmpty();
        assert hsailCode.getDataReferences().isEmpty();

        // from host code
        result.setFrameSize(hostCode.getFrameSize());
        result.setCustomStackAreaOffset(hostCode.getCustomStackAreaOffset());
        result.setRegisterRestoreEpilogueOffset(hostCode.getRegisterRestoreEpilogueOffset());
        result.setTargetCode(hostCode.getTargetCode(), hostCode.getTargetCodeSize());
        for (CodeAnnotation annotation : hostCode.getAnnotations()) {
            result.addAnnotation(annotation);
        }
        CompilationResult.Mark[] noMarks = {};
        for (Mark mark : hostCode.getMarks()) {
            result.recordMark(mark.pcOffset, mark.id, noMarks);
        }
        for (ExceptionHandler handler : hostCode.getExceptionHandlers()) {
            result.recordExceptionHandler(handler.pcOffset, handler.handlerPos);
        }
        for (DataPatch patch : hostCode.getDataReferences()) {
            if (patch.data != null) {
                if (patch.inline) {
                    result.recordInlineData(patch.pcOffset, patch.data);
                } else {
                    result.recordDataReference(patch.pcOffset, patch.data);
                }
            }
        }
        for (Infopoint infopoint : hostCode.getInfopoints()) {
            if (infopoint instanceof Call) {
                Call call = (Call) infopoint;
                result.recordCall(call.pcOffset, call.size, call.target, call.debugInfo, call.direct);
            } else {
                result.recordInfopoint(infopoint.pcOffset, infopoint.debugInfo, infopoint.reason);
            }
        }

        // merged
        Assumptions mergedAssumptions = new Assumptions(true);
        if (hostCode.getAssumptions() != null) {
            for (Assumption assumption : hostCode.getAssumptions().getAssumptions()) {
                if (assumption != null) {
                    mergedAssumptions.record(assumption);
                }
            }
        }
        if (hsailCode.getAssumptions() != null) {
            for (Assumption assumption : hsailCode.getAssumptions().getAssumptions()) {
                if (assumption != null) {
                    mergedAssumptions.record(assumption);
                }
            }
        }
        if (!mergedAssumptions.isEmpty()) {
            result.setAssumptions(mergedAssumptions);
        }
        return result;
    }

    public boolean executeKernel(HotSpotInstalledCode kernel, int jobSize, Object[] args) throws InvalidInstalledCodeException {
        if (!deviceInitialized) {
            throw new GraalInternalError("Cannot execute GPU kernel if device is not initialized");
        }
        Object[] oopsSaveArea = new Object[maxDeoptIndex * 16];
        return executeKernel0(kernel, jobSize, args, oopsSaveArea);
    }

    private static native boolean executeKernel0(HotSpotInstalledCode kernel, int jobSize, Object[] args, Object[] oopsSave) throws InvalidInstalledCodeException;

    /**
     * Use the HSAIL register set when the compilation target is HSAIL.
     */
    @Override
    public FrameMap newFrameMap(RegisterConfig registerConfig) {
        return new HSAILFrameMap(getCodeCache(), registerConfig);
    }

    @Override
    public LIRGenerator newLIRGenerator(CallingConvention cc, LIRGenerationResult lirGenRes) {
        return new HSAILHotSpotLIRGenerator(getProviders(), getRuntime().getConfig(), cc, lirGenRes);
    }

    @Override
    public LIRGenerationResult newLIRGenerationResult(LIR lir, FrameMap frameMap, Object stub) {
        return new HSAILHotSpotLIRGenerationResult(lir, frameMap);
    }

    @Override
    public NodeLIRBuilder newNodeLIRGenerator(StructuredGraph graph, LIRGenerator lirGen) {
        return new HSAILHotSpotNodeLIRBuilder(graph, lirGen);
    }

    class HotSpotFrameContext implements FrameContext {

        public boolean hasFrame() {
            return true;
        }

        @Override
        public void enter(CompilationResultBuilder crb) {
            Debug.log("Nothing to do here");
        }

        @Override
        public void leave(CompilationResultBuilder crb) {
            Debug.log("Nothing to do here");
        }
    }

    /**
     * a class to allow us to save lirGen.
     */
    static class HSAILCompilationResultBuilder extends CompilationResultBuilder {
        public HSAILHotSpotLIRGenerationResult lirGenRes;

        public HSAILCompilationResultBuilder(CodeCacheProvider codeCache, ForeignCallsProvider foreignCalls, FrameMap frameMap, Assembler asm, FrameContext frameContext,
                        CompilationResult compilationResult, HSAILHotSpotLIRGenerationResult lirGenRes) {
            super(codeCache, foreignCalls, frameMap, asm, frameContext, compilationResult);
            this.lirGenRes = lirGenRes;
        }
    }

    @Override
    protected Assembler createAssembler(FrameMap frameMap) {
        return new HSAILHotSpotAssembler(getTarget());
    }

    @Override
    public CompilationResultBuilder newCompilationResultBuilder(LIRGenerationResult lirGenRes, CompilationResult compilationResult, CompilationResultBuilderFactory factory) {
        FrameMap frameMap = lirGenRes.getFrameMap();
        Assembler masm = createAssembler(frameMap);
        HotSpotFrameContext frameContext = new HotSpotFrameContext();
        // save lirGen for later use by setHostGraph
        CompilationResultBuilder crb = new HSAILCompilationResultBuilder(getCodeCache(), getForeignCalls(), frameMap, masm, frameContext, compilationResult,
                        (HSAILHotSpotLIRGenerationResult) lirGenRes);
        crb.setFrameSize(frameMap.frameSize());
        return crb;
    }

    @Override
    public void emitCode(CompilationResultBuilder crb, LIR lir, ResolvedJavaMethod method) {
        assert method != null : lir + " is not associated with a method";

        boolean useHSAILDeoptimization = getRuntime().getConfig().useHSAILDeoptimization;

        // Emit the prologue.
        HSAILAssembler asm = (HSAILAssembler) crb.asm;
        asm.emitString0("version 0:95: $full : $large;\n");

        Signature signature = method.getSignature();
        int sigParamCount = signature.getParameterCount(false);
        // We're subtracting 1 because we're not making the final gid as a parameter.

        int nonConstantParamCount = sigParamCount - 1;
        boolean isStatic = (Modifier.isStatic(method.getModifiers()));
        // Determine if this is an object lambda.
        boolean isObjectLambda = true;

        if (signature.getParameterType(nonConstantParamCount, null).getKind() == Kind.Int) {
            isObjectLambda = false;
        } else {
            // Add space for gid int reg.
            nonConstantParamCount++;
        }

        // If this is an instance method, include mappings for the "this" parameter
        // as the first parameter.
        if (!isStatic) {
            nonConstantParamCount++;
        }
        // Add in any "constant" parameters (currently none).
        int totalParamCount = nonConstantParamCount;
        JavaType[] paramtypes = new JavaType[totalParamCount];
        String[] paramNames = new String[totalParamCount];
        int pidx = 0;
        MetaAccessProvider metaAccess = getProviders().getMetaAccess();
        for (int i = 0; i < totalParamCount; i++) {
            if (i == 0 && !isStatic) {
                paramtypes[i] = metaAccess.lookupJavaType(Object.class);
                paramNames[i] = "%_this";
            } else if (i < nonConstantParamCount) {
                if (isObjectLambda && (i == (nonConstantParamCount))) {
                    // Set up the gid register mapping.
                    paramtypes[i] = metaAccess.lookupJavaType(int.class);
                    paramNames[i] = "%_gid";
                } else {
                    paramtypes[i] = signature.getParameterType(pidx++, null);
                    paramNames[i] = "%_arg" + i;
                }
            }
        }

        asm.emitString0("// " + (isStatic ? "static" : "instance") + " method " + method + "\n");
        asm.emitString0("kernel &run ( \n");

        FrameMap frameMap = crb.frameMap;
        RegisterConfig regConfig = frameMap.registerConfig;
        // Build list of param types which does include the gid (for cc register mapping query).
        JavaType[] ccParamTypes = new JavaType[nonConstantParamCount + 1];
        // Include the gid.
        System.arraycopy(paramtypes, 0, ccParamTypes, 0, nonConstantParamCount);

        // Last entry is always int (its register gets used in the workitemabsid instruction)
        // this is true even for object stream labmdas
        if (sigParamCount > 0) {
            ccParamTypes[ccParamTypes.length - 1] = metaAccess.lookupJavaType(int.class);
        }
        CallingConvention cc = regConfig.getCallingConvention(JavaCallee, null, ccParamTypes, getTarget(), false);

        /**
         * Compute the hsail size mappings up to but not including the last non-constant parameter
         * (which is the gid).
         *
         */
        String[] paramHsailSizes = new String[totalParamCount];
        for (int i = 0; i < totalParamCount; i++) {
            String paramtypeStr = paramtypes[i].toString();
            String sizeStr = paramTypeMap.get(paramtypeStr);
            // Catch all for any unmapped paramtype that is u64 (address of an object).
            paramHsailSizes[i] = (sizeStr != null ? sizeStr : "u64");
        }
        // Emit the kernel function parameters.
        for (int i = 0; i < totalParamCount; i++) {
            String str = "align 8 kernarg_" + paramHsailSizes[i] + " " + paramNames[i];

            if (useHSAILDeoptimization || (i != totalParamCount - 1)) {
                str += ",";
            }
            asm.emitString(str);
        }

        if (useHSAILDeoptimization) {
            // add in the deoptInfo parameter
            asm.emitString("kernarg_u64 " + asm.getDeoptInfoName());
        }

        asm.emitString(") {");

        /*
         * End of parameters start of prolog code. Emit the load instructions for loading of the
         * kernel non-constant parameters into registers. The constant class parameters will not be
         * loaded up front but will be loaded as needed.
         */
        for (int i = 0; i < nonConstantParamCount; i++) {
            asm.emitString("ld_kernarg_" + paramHsailSizes[i] + "  " + HSAIL.mapRegister(cc.getArgument(i)) + ", [" + paramNames[i] + "];");
        }

        /*
         * Emit the workitemaid instruction for loading the hidden gid parameter. This is assigned
         * the register as if it were the last of the nonConstant parameters.
         */
        String workItemReg = "$s" + Integer.toString(asRegister(cc.getArgument(nonConstantParamCount)).encoding());
        asm.emitString("workitemabsid_u32 " + workItemReg + ", 0;");

        final int offsetToDeopt = getRuntime().getConfig().hsailDeoptOffset;
        final String deoptInProgressLabel = "@LHandleDeoptInProgress";

        if (useHSAILDeoptimization) {
            AllocatableValue scratch64 = HSAIL.d16.asValue(Kind.Object);
            AllocatableValue scratch32 = HSAIL.s34.asValue(Kind.Int);
            HSAILAddress deoptInfoAddr = new HSAILAddressValue(Kind.Int, scratch64, offsetToDeopt).toAddress();
            asm.emitLoadKernelArg(scratch64, asm.getDeoptInfoName(), "u64");
            asm.emitComment("// Check if a deopt has occurred and abort if true before doing any work");
            asm.emitLoadAcquire(scratch32, deoptInfoAddr);
            asm.emitCompare(Kind.Int, scratch32, Constant.forInt(0), "ne", false, false);
            asm.cbr(deoptInProgressLabel);
        }

        /*
         * Note the logic used for this spillseg size is to leave space and then go back and patch
         * in the correct size once we have generated all the instructions. This should probably be
         * done in a more robust way by implementing something like asm.insertString.
         */
        int spillsegDeclarationPosition = asm.position() + 1;
        String spillsegTemplate = "align 4 spill_u8 %spillseg[123456];";
        asm.emitString(spillsegTemplate);
        // Emit object array load prologue here.
        if (isObjectLambda) {
            boolean useCompressedOops = getRuntime().getConfig().useCompressedOops;
            final int arrayElementsOffset = HotSpotGraalRuntime.getArrayBaseOffset(Kind.Object);
            String iterationObjArgReg = HSAIL.mapRegister(cc.getArgument(nonConstantParamCount - 1));
            // iterationObjArgReg will be the highest $d register in use (it is the last parameter)
            // so tempReg can be the next higher $d register
            String tmpReg = "$d" + (asRegister(cc.getArgument(nonConstantParamCount - 1)).encoding() + 1);
            // Convert gid to long.
            asm.emitString("cvt_u64_s32 " + tmpReg + ", " + workItemReg + "; // Convert gid to long");
            // Adjust index for sizeof ref. Where to pull this size from?
            asm.emitString("mul_u64 " + tmpReg + ", " + tmpReg + ", " + (useCompressedOops ? 4 : 8) + "; // Adjust index for sizeof ref");
            // Adjust for actual data start.
            asm.emitString("add_u64 " + tmpReg + ", " + tmpReg + ", " + arrayElementsOffset + "; // Adjust for actual elements data start");
            // Add to array ref ptr.
            asm.emitString("add_u64 " + tmpReg + ", " + tmpReg + ", " + iterationObjArgReg + "; // Add to array ref ptr");
            // Load the object into the parameter reg.
            if (useCompressedOops) {

                // Load u32 into the d 64 reg since it will become an object address
                asm.emitString("ld_global_u32 " + tmpReg + ", " + "[" + tmpReg + "]" + "; // Load compressed ptr from array");

                long narrowOopBase = getRuntime().getConfig().narrowOopBase;
                long narrowOopShift = getRuntime().getConfig().narrowOopShift;

                if (narrowOopBase == 0 && narrowOopShift == 0) {
                    // No more calculation to do, mov to target register
                    asm.emitString("mov_b64 " + iterationObjArgReg + ", " + tmpReg + "; // no shift or base addition");
                } else {
                    if (narrowOopBase == 0) {
                        asm.emitString("shl_u64 " + iterationObjArgReg + ", " + tmpReg + ", " + narrowOopShift + "; // do narrowOopShift");
                    } else if (narrowOopShift == 0) {
                        // not sure if we ever get add with 0 shift but just in case
                        asm.emitString("cmp_eq_b1_u64  $c0, " + tmpReg + ", 0x0; // avoid add if compressed is null");
                        asm.emitString("add_u64 " + iterationObjArgReg + ", " + tmpReg + ", " + narrowOopBase + "; // add narrowOopBase");
                        asm.emitString("cmov_b64 " + iterationObjArgReg + ", $c0, 0x0, " + iterationObjArgReg + "; // avoid add if compressed is null");
                    } else {
                        asm.emitString("cmp_eq_b1_u64  $c0, " + tmpReg + ", 0x0; // avoid shift-add if compressed is null");
                        asm.emitString("mad_u64 " + iterationObjArgReg + ", " + tmpReg + ", " + (1 << narrowOopShift) + ", " + narrowOopBase + "; // shift and add narrowOopBase");
                        asm.emitString("cmov_b64 " + iterationObjArgReg + ", $c0, 0x0, " + iterationObjArgReg + "; // avoid shift-add if compressed is null");
                    }
                }

            } else {
                asm.emitString("ld_global_u64 " + iterationObjArgReg + ", " + "[" + tmpReg + "]" + "; // Load from array element into parameter reg");
            }
        }
        // Prologue done, Emit code for the LIR.
        crb.emit(lir);
        // Now that code is emitted go back and figure out what the upper Bound stack size was.
        long maxStackSize = ((HSAILAssembler) crb.asm).upperBoundStackSize();
        String spillsegStringFinal;
        if (maxStackSize == 0) {
            // If no spilling, get rid of spillseg declaration.
            char[] array = new char[spillsegTemplate.length()];
            Arrays.fill(array, ' ');
            spillsegStringFinal = new String(array);
        } else {
            spillsegStringFinal = spillsegTemplate.replace("123456", String.format("%6d", maxStackSize));
        }
        asm.emitString(spillsegStringFinal, spillsegDeclarationPosition);
        // Emit the epilogue.

        // TODO: keep track of whether we need it
        if (useHSAILDeoptimization) {
            final int offsetToDeoptSaveStates = getRuntime().getConfig().hsailSaveStatesOffset0;
            final int sizeofKernelDeopt = getRuntime().getConfig().hsailSaveStatesOffset1 - getRuntime().getConfig().hsailSaveStatesOffset0;
            final int offsetToNeverRanArray = getRuntime().getConfig().hsailNeverRanArrayOffset;
            final int offsetToDeoptNextIndex = getRuntime().getConfig().hsailDeoptNextIndexOffset;
            final int offsetToDeoptimizationWorkItem = getRuntime().getConfig().hsailDeoptimizationWorkItem;
            final int offsetToDeoptimizationReason = getRuntime().getConfig().hsailDeoptimizationReason;
            final int offsetToDeoptimizationFrame = getRuntime().getConfig().hsailDeoptimizationFrame;
            final int offsetToFramePc = getRuntime().getConfig().hsailFramePcOffset;
            final int offsetToNumSaves = getRuntime().getConfig().hsailFrameNumSRegOffset;
            final int offsetToSaveArea = getRuntime().getConfig().hsailFrameSaveAreaOffset;

            AllocatableValue scratch64 = HSAIL.d16.asValue(Kind.Object);
            AllocatableValue cuSaveAreaPtr = HSAIL.d17.asValue(Kind.Object);
            AllocatableValue waveMathScratch1 = HSAIL.d18.asValue(Kind.Object);
            AllocatableValue waveMathScratch2 = HSAIL.d19.asValue(Kind.Object);

            AllocatableValue actionAndReasonReg = HSAIL.s32.asValue(Kind.Int);
            AllocatableValue codeBufferOffsetReg = HSAIL.s33.asValue(Kind.Int);
            AllocatableValue scratch32 = HSAIL.s34.asValue(Kind.Int);
            AllocatableValue workidreg = HSAIL.s35.asValue(Kind.Int);
            AllocatableValue dregOopMapReg = HSAIL.s39.asValue(Kind.Int);

            HSAILAddress deoptNextIndexAddr = new HSAILAddressValue(Kind.Int, scratch64, offsetToDeoptNextIndex).toAddress();
            HSAILAddress neverRanArrayAddr = new HSAILAddressValue(Kind.Int, scratch64, offsetToNeverRanArray).toAddress();

            // The just-started lanes that see the deopt flag will jump here
            asm.emitString0(deoptInProgressLabel + ":\n");
            asm.emitLoad(Kind.Object, waveMathScratch1, neverRanArrayAddr);
            asm.emitWorkItemAbsId(workidreg);
            asm.emitConvert(waveMathScratch2, workidreg, Kind.Object, Kind.Int);
            asm.emit("add", waveMathScratch1, waveMathScratch1, waveMathScratch2);
            HSAILAddress neverRanStoreAddr = new HSAILAddressValue(Kind.Byte, waveMathScratch1, 0).toAddress();
            asm.emitStore(Kind.Byte, Constant.forInt(1), neverRanStoreAddr);
            asm.emitString("ret;");

            // The deoptimizing lanes will jump here
            asm.emitString0(asm.getDeoptLabelName() + ":\n");
            String labelExit = asm.getDeoptLabelName() + "_Exit";

            HSAILAddress deoptInfoAddr = new HSAILAddressValue(Kind.Int, scratch64, offsetToDeopt).toAddress();
            asm.emitLoadKernelArg(scratch64, asm.getDeoptInfoName(), "u64");

            // Set deopt occurred flag
            asm.emitMov(Kind.Int, scratch32, Constant.forInt(1));
            asm.emitStoreRelease(scratch32, deoptInfoAddr);

            asm.emitComment("// Determine next deopt save slot");
            asm.emitAtomicAdd(scratch32, deoptNextIndexAddr, Constant.forInt(1));
            // scratch32 now holds next index to use
            // set error condition if no room in save area
            asm.emitComment("// assert room to save deopt");
            asm.emitCompare(Kind.Int, scratch32, Constant.forInt(maxDeoptIndex), "lt", false, false);
            asm.cbr("@L_StoreDeopt");
            // if assert fails, store a guaranteed negative workitemid in top level deopt occurred
            // flag
            asm.emitWorkItemAbsId(scratch32);
            asm.emit("mad", scratch32, scratch32, Constant.forInt(-1), Constant.forInt(-1));
            asm.emitStore(scratch32, deoptInfoAddr);
            asm.emitString("ret;");

            asm.emitString0("@L_StoreDeopt" + ":\n");

            // Store deopt for this workitem into its slot in the HSAILComputeUnitSaveStates array

            asm.emitComment("// Convert id's for ptr math");
            asm.emitConvert(cuSaveAreaPtr, scratch32, Kind.Object, Kind.Int);
            asm.emitComment("// multiply by sizeof KernelDeoptArea");
            asm.emit("mul", cuSaveAreaPtr, cuSaveAreaPtr, Constant.forInt(sizeofKernelDeopt));
            asm.emitComment("// Add computed offset to deoptInfoPtr base");
            asm.emit("add", cuSaveAreaPtr, cuSaveAreaPtr, scratch64);
            // Add offset to _deopt_save_states[0]
            asm.emit("add", scratch64, cuSaveAreaPtr, Constant.forInt(offsetToDeoptSaveStates));

            HSAILAddress workItemAddr = new HSAILAddressValue(Kind.Int, scratch64, offsetToDeoptimizationWorkItem).toAddress();
            HSAILAddress actionReasonStoreAddr = new HSAILAddressValue(Kind.Int, scratch64, offsetToDeoptimizationReason).toAddress();

            asm.emitComment("// Get _deopt_info._first_frame");
            asm.emit("add", waveMathScratch1, scratch64, Constant.forInt(offsetToDeoptimizationFrame));
            // Now scratch64 is the _deopt_info._first_frame
            HSAILAddress pcStoreAddr = new HSAILAddressValue(Kind.Int, waveMathScratch1, offsetToFramePc).toAddress();
            HSAILAddress regCountsAddr = new HSAILAddressValue(Kind.Int, waveMathScratch1, offsetToNumSaves).toAddress();
            HSAILAddress dregOopMapAddr = new HSAILAddressValue(Kind.Int, waveMathScratch1, offsetToNumSaves + 2).toAddress();

            asm.emitComment("// store deopting workitem");
            asm.emitWorkItemAbsId(scratch32);
            asm.emitStore(Kind.Int, scratch32, workItemAddr);
            asm.emitComment("// store actionAndReason");
            asm.emitStore(Kind.Int, actionAndReasonReg, actionReasonStoreAddr);
            asm.emitComment("// store PC");
            asm.emitStore(Kind.Int, codeBufferOffsetReg, pcStoreAddr);
            asm.emitComment("// store regCounts");
            asm.emitStore(Kind.Short, Constant.forInt(32 + (16 << 8) + (0 << 16)), regCountsAddr);
            asm.emitComment("// store dreg ref map bits");
            asm.emitStore(Kind.Short, dregOopMapReg, dregOopMapAddr);

            // get the union of registers needed to be saved at the infopoints
            // usedRegs array assumes d15 has the highest register number we wish to save
            // and initially has all registers as false
            boolean[] infoUsedRegs = new boolean[HSAIL.d15.number + 1];
            List<Infopoint> infoList = crb.compilationResult.getInfopoints();
            for (Infopoint info : infoList) {
                BytecodeFrame frame = info.debugInfo.frame();
                for (int i = 0; i < frame.numLocals + frame.numStack; i++) {
                    Value val = frame.values[i];
                    if (isLegal(val) && isRegister(val)) {
                        Register reg = asRegister(val);
                        infoUsedRegs[reg.number] = true;
                    }
                }
            }

            // loop storing each of the 32 s registers that are used by infopoints
            // we always store in a fixed location, even if some registers are not stored
            asm.emitComment("// store used s regs");
            int ofst = offsetToSaveArea;
            for (Register sreg : HSAIL.sRegisters) {
                if (infoUsedRegs[sreg.number]) {
                    Kind kind = Kind.Int;
                    HSAILAddress addr = new HSAILAddressValue(kind, waveMathScratch1, ofst).toAddress();
                    AllocatableValue sregValue = sreg.asValue(kind);
                    asm.emitStore(kind, sregValue, addr);
                }
                ofst += 4;
            }

            // loop storing each of the 16 d registers that are used by infopoints
            asm.emitComment("// store used d regs");
            for (Register dreg : HSAIL.dRegisters) {
                if (infoUsedRegs[dreg.number]) {
                    Kind kind = Kind.Long;
                    HSAILAddress addr = new HSAILAddressValue(kind, waveMathScratch1, ofst).toAddress();
                    AllocatableValue dregValue = dreg.asValue(kind);
                    asm.emitStore(kind, dregValue, addr);
                }
                ofst += 8;
            }

            // for now, ignore saving the spill variables but that would come here

            asm.emitString0(labelExit + ":\n");

            // and emit the return
            crb.frameContext.leave(crb);
            asm.exit();
        } else {
            // Deoptimization is explicitly off, so emit simple return
            asm.emitString0(asm.getDeoptLabelName() + ":\n");
            asm.emitComment("// No deoptimization");
            asm.emitString("ret;");
        }

        asm.emitString0("}; \n");

        ExternalCompilationResult compilationResult = (ExternalCompilationResult) crb.compilationResult;
        HSAILHotSpotLIRGenerationResult lirGenRes = ((HSAILCompilationResultBuilder) crb).lirGenRes;
        compilationResult.setHostGraph(prepareHostGraph(method, lirGenRes.getDeopts(), getProviders(), getRuntime().getConfig()));
    }

    private static StructuredGraph prepareHostGraph(ResolvedJavaMethod method, List<DeoptimizeOp> deopts, HotSpotProviders providers, HotSpotVMConfig config) {
        if (deopts.isEmpty()) {
            return null;
        }
        StructuredGraph hostGraph = new StructuredGraph(method, -2);
        ParameterNode deoptId = hostGraph.unique(new ParameterNode(0, StampFactory.intValue()));
        ParameterNode hsailFrame = hostGraph.unique(new ParameterNode(1, StampFactory.forKind(providers.getCodeCache().getTarget().wordKind)));
        ParameterNode reasonAndAction = hostGraph.unique(new ParameterNode(2, StampFactory.intValue()));
        ParameterNode speculation = hostGraph.unique(new ParameterNode(3, StampFactory.object()));
        AbstractBeginNode[] branches = new AbstractBeginNode[deopts.size() + 1];
        int[] keys = new int[deopts.size()];
        int[] keySuccessors = new int[deopts.size() + 1];
        double[] keyProbabilities = new double[deopts.size() + 1];
        int i = 0;
        Collections.sort(deopts, new Comparator<DeoptimizeOp>() {
            public int compare(DeoptimizeOp o1, DeoptimizeOp o2) {
                return o1.getCodeBufferPos() - o2.getCodeBufferPos();
            }
        });
        for (DeoptimizeOp deopt : deopts) {
            keySuccessors[i] = i;
            keyProbabilities[i] = 1.0 / deopts.size();
            keys[i] = deopt.getCodeBufferPos();
            assert keys[i] >= 0;
            branches[i] = createHostDeoptBranch(deopt, hsailFrame, reasonAndAction, speculation, providers, config);

            i++;
        }
        keyProbabilities[deopts.size()] = 0; // default
        keySuccessors[deopts.size()] = deopts.size();
        branches[deopts.size()] = createHostCrashBranch(hostGraph, deoptId);
        IntegerSwitchNode switchNode = hostGraph.add(new IntegerSwitchNode(deoptId, branches, keys, keyProbabilities, keySuccessors));
        StartNode start = hostGraph.start();
        start.setNext(switchNode);
        /*
         * printf.setNext(printf2); printf2.setNext(switchNode);
         */
        hostGraph.setGuardsStage(GuardsStage.AFTER_FSA);
        return hostGraph;
    }

    private static AbstractBeginNode createHostCrashBranch(StructuredGraph hostGraph, ValueNode deoptId) {
        VMErrorNode vmError = hostGraph.add(new VMErrorNode("Error in HSAIL deopt. DeoptId=%d", deoptId));
        // ConvertNode.convert(hostGraph, Kind.Long, deoptId)));
        vmError.setNext(hostGraph.add(new ReturnNode(ConstantNode.defaultForKind(hostGraph.method().getSignature().getReturnKind(), hostGraph))));
        return BeginNode.begin(vmError);
    }

    private static AbstractBeginNode createHostDeoptBranch(DeoptimizeOp deopt, ParameterNode hsailFrame, ValueNode reasonAndAction, ValueNode speculation, HotSpotProviders providers,
                    HotSpotVMConfig config) {
        BeginNode branch = hsailFrame.graph().add(new BeginNode());
        DynamicDeoptimizeNode deoptimization = hsailFrame.graph().add(new DynamicDeoptimizeNode(reasonAndAction, speculation));
        deoptimization.setStateBefore(createFrameState(deopt.getFrameState().topFrame, hsailFrame, providers, config));
        branch.setNext(deoptimization);
        return branch;
    }

    private static FrameState createFrameState(BytecodeFrame lowLevelFrame, ParameterNode hsailFrame, HotSpotProviders providers, HotSpotVMConfig config) {
        StructuredGraph hostGraph = hsailFrame.graph();
        ValueNode[] locals = new ValueNode[lowLevelFrame.numLocals];
        for (int i = 0; i < lowLevelFrame.numLocals; i++) {
            locals[i] = getNodeForValueFromFrame(lowLevelFrame.getLocalValue(i), hsailFrame, hostGraph, providers, config);
        }
        List<ValueNode> stack = new ArrayList<>(lowLevelFrame.numStack);
        for (int i = 0; i < lowLevelFrame.numStack; i++) {
            stack.add(getNodeForValueFromFrame(lowLevelFrame.getStackValue(i), hsailFrame, hostGraph, providers, config));
        }
        ValueNode[] locks = new ValueNode[lowLevelFrame.numLocks];
        MonitorIdNode[] monitorIds = new MonitorIdNode[lowLevelFrame.numLocks];
        for (int i = 0; i < lowLevelFrame.numLocks; i++) {
            HotSpotMonitorValue lockValue = (HotSpotMonitorValue) lowLevelFrame.getLockValue(i);
            locks[i] = getNodeForValueFromFrame(lockValue, hsailFrame, hostGraph, providers, config);
            monitorIds[i] = getMonitorIdForHotSpotMonitorValueFromFrame(lockValue, hsailFrame, hostGraph);
        }
        FrameState frameState = hostGraph.add(new FrameState(lowLevelFrame.getMethod(), lowLevelFrame.getBCI(), locals, stack, locks, monitorIds, lowLevelFrame.rethrowException, false));
        if (lowLevelFrame.caller() != null) {
            frameState.setOuterFrameState(createFrameState(lowLevelFrame.caller(), hsailFrame, providers, config));
        }
        return frameState;
    }

    @SuppressWarnings({"unused"})
    private static MonitorIdNode getMonitorIdForHotSpotMonitorValueFromFrame(HotSpotMonitorValue lockValue, ParameterNode hsailFrame, StructuredGraph hsailGraph) {
        if (lockValue.isEliminated()) {
            return null;
        }
        throw GraalInternalError.unimplemented();
    }

    private static ValueNode getNodeForValueFromFrame(Value localValue, ParameterNode hsailFrame, StructuredGraph hostGraph, HotSpotProviders providers, HotSpotVMConfig config) {
        ValueNode valueNode;
        if (localValue instanceof Constant) {
            valueNode = ConstantNode.forConstant((Constant) localValue, providers.getMetaAccess(), hostGraph);
        } else if (localValue instanceof VirtualObject) {
            throw GraalInternalError.unimplemented();
        } else if (localValue instanceof StackSlot) {
            throw GraalInternalError.unimplemented();
        } else if (localValue instanceof HotSpotMonitorValue) {
            HotSpotMonitorValue hotSpotMonitorValue = (HotSpotMonitorValue) localValue;
            return getNodeForValueFromFrame(hotSpotMonitorValue.getOwner(), hsailFrame, hostGraph, providers, config);
        } else if (localValue instanceof RegisterValue) {
            RegisterValue registerValue = (RegisterValue) localValue;
            int regNumber = registerValue.getRegister().number;
            valueNode = getNodeForRegisterFromFrame(regNumber, localValue.getKind(), hsailFrame, hostGraph, providers, config);
        } else if (Value.ILLEGAL.equals(localValue)) {
            valueNode = null;
        } else {
            throw GraalInternalError.shouldNotReachHere();
        }
        return valueNode;
    }

    private static ValueNode getNodeForRegisterFromFrame(int regNumber, Kind valueKind, ParameterNode hsailFrame, StructuredGraph hostGraph, HotSpotProviders providers, HotSpotVMConfig config) {
        ValueNode valueNode;
        LocationNode location;
        if (regNumber >= HSAIL.s0.number && regNumber <= HSAIL.s31.number) {
            int intSize = providers.getCodeCache().getTarget().arch.getSizeInBytes(Kind.Int);
            long offset = config.hsailFrameSaveAreaOffset + intSize * (regNumber - HSAIL.s0.number);
            location = ConstantLocationNode.create(FINAL_LOCATION, valueKind, offset, hostGraph);
        } else if (regNumber >= HSAIL.d0.number && regNumber <= HSAIL.d15.number) {
            int longSize = providers.getCodeCache().getTarget().arch.getSizeInBytes(Kind.Long);
            long offset = config.hsailFrameSaveAreaOffset + longSize * (regNumber - HSAIL.d0.number);
            LocationNode numSRegsLocation = ConstantLocationNode.create(FINAL_LOCATION, Kind.Byte, config.hsailFrameNumSRegOffset, hostGraph);
            ValueNode numSRegs = hostGraph.unique(new FloatingReadNode(hsailFrame, numSRegsLocation, null, StampFactory.forInteger(8, false)));
            numSRegs = SignExtendNode.convert(numSRegs, StampFactory.forKind(Kind.Byte));
            location = IndexedLocationNode.create(FINAL_LOCATION, valueKind, offset, numSRegs, hostGraph, 4);
        } else {
            throw GraalInternalError.shouldNotReachHere("unknown hsail register: " + regNumber);
        }
        valueNode = hostGraph.unique(new FloatingReadNode(hsailFrame, location, null, StampFactory.forKind(valueKind)));
        return valueNode;
    }

}
