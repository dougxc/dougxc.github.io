package java.util.stream;

import java.util.Spliterators;
import java.util.Spliterator;
import java.util.concurrent.CountedCompleter;
import java.util.function.Supplier;

import com.amd.sumatra.SumatraUtils;
import java.util.function.IntConsumer;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.Class;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

import com.amd.sumatra.Sumatra;
import com.amd.sumatra.SumatraFactory;

import java.util.concurrent.ConcurrentHashMap;

public class PipelineInfo {

    // implemented by an ArrayList of PipelineInfo.Entry elements
    private ArrayList<Entry> plist = new ArrayList<>();

    public void add(Entry entry) {
        plist.add(entry);
    }

    public Entry get(int n) {
        return plist.get(n);
    }

    public int size() {
        return plist.size();
    }

    public void show() {
        int opNum = 0;
        for (Entry entry : plist) {
            opNum++;
            System.out.println("Op #" + opNum + entry);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PipelineInfo)) {
            return false;
        }
        PipelineInfo otherInfo = (PipelineInfo) other;
        return (plist.equals(otherInfo.plist));
    }

    @Override
    public int hashCode() {
        return plist.hashCode();
    }

    static public enum OpType {
        UNKNOWN, FILTER, PEEK, FOREACH
    };

    static public enum DataType {
        UNKNOWN, INT, OBJ
    };

    public static class Entry {
        public OpType op;               // what kind of op
        public DataType dtype;          // what type of data
        public Class<?> lambdaClass;    // class of lambda to call
        public Object lambdaObj;      // actual lambda object from this capture

        Entry(String klassName) {
            // hack alert, there should be a way to do this that is not dependent on the
            // order of the innerclass declarations
            if (klassName.endsWith("IntPipeline$9$1")) {
                setOpData(OpType.FILTER, DataType.INT);
            } else if (klassName.endsWith("IntPipeline$10$1")) {
                setOpData(OpType.PEEK, DataType.INT);
            } else if (klassName.endsWith("ReferencePipeline$2$1")) {
                setOpData(OpType.FILTER, DataType.OBJ);
            } else if (klassName.endsWith("ReferencePipeline$11$1")) {
                setOpData(OpType.PEEK, DataType.OBJ);
            } else if (klassName.contains("ForEachOps$ForEachOp$")) {
                setOpData(OpType.FOREACH, DataType.UNKNOWN);
            } else {
                System.out.println("WARNING: unmatched class name " + klassName + " in PipelineInfo.Entry constructor");
                setOpData(OpType.UNKNOWN, DataType.UNKNOWN);
            }
        }

        private void setOpData(OpType _op, DataType _dtype) {
            op = _op;
            dtype = _dtype;
        }

        @Override
        public boolean equals(Object other) {
            // on purpose we say that there are equal ignoring the lambdaObj fields
            if (!(other instanceof Entry)) {
                return false;
            }
            Entry otherEntry = (Entry) other;
            return ((op.equals(otherEntry.op)) && (dtype.equals(otherEntry.dtype)) && (lambdaClass.equals(otherEntry.lambdaClass)));
        }

        @Override
        public int hashCode() {
            // on purpose we don't hash in the lambdaObj field
            int hashOp = op.hashCode();
            int hashDtype = dtype.hashCode();
            int hashClass = lambdaClass.hashCode();
            return (hashOp + hashDtype + hashClass) * hashClass + hashOp;
        }

        public String toString() {
            return (", op=" + op + ", dtype=" + dtype + ", lambdaObj=" + lambdaObj);
        }
    }

    static Field findFieldStartsWith(Class<?> klass, String startStr) {
        Field[] fields = klass.getDeclaredFields();
        for (Field f : fields) {
            if (f.getName().startsWith(startStr)) {
                return f;
            }
        }
        // if we got this far, no such field
        return null;
    }

    static boolean pipelineDebugPrint = Boolean.getBoolean("pipelineDebugPrint");

    static void debugPrint(String str) {
        if (pipelineDebugPrint) {
            System.out.println(str);
        }
    }

    public static <P_IN> PipelineInfo deducePipeline(Sink<P_IN> sink) {
        PipelineInfo info = new PipelineInfo();
        try {
            Object curSink = sink;
            int opNum = 1;
            while (curSink != null) {
                Class<?> curSinkKlass = curSink.getClass();
                String sinkKlassName = curSinkKlass.getName();
                Entry entry = new Entry(sinkKlassName);

                // val field is in this$1 for peek, filter etc
                Field thisField = findFieldStartsWith(curSinkKlass, "this$1");
                Field curSinkLambdaField = null;
                Object curSinkLambda = null;
                if (thisField != null) {
                    // long thisOffset = UnsafeWrapper.objectFieldOffset(thisField);
                    // Get pipeline object from sink
                    // Object pipeline = UnsafeWrapper.getObject(curSink, thisOffset);
                    Object pipeline = SumatraUtils.getFieldFromObject(thisField, curSink);
// System.out.println("deducePipeline: " + curSinkKlass.getName()
// + "... thisOffset= " + thisOffset
// + "... pipeline= " + pipeline);
                    // lambda is in val$cap$0 or arg$1
                    curSinkLambdaField = findFieldStartsWith(pipeline.getClass(), "val$");
                    // long offset = UnsafeWrapper.objectFieldOffset(curSinkLambdaField);
                    // curSinkLambda = UnsafeWrapper.getObject(pipeline, offset);
                    curSinkLambda = SumatraUtils.getFieldFromObject(curSinkLambdaField, pipeline);
                } else {
                    // It is a java.util.stream.ForEachOps$ForEachOp
// System.out.println("curSinkKlass = " + curSinkKlass.getName());
                    curSinkLambdaField = findFieldStartsWith(curSinkKlass, "consumer");
                    if (curSinkLambdaField != null) {
                        // long offset = UnsafeWrapper.objectFieldOffset(curSinkLambdaField);
                        // curSinkLambda = UnsafeWrapper.getObject(curSink, offset);
                        curSinkLambda = SumatraUtils.getFieldFromObject(curSinkLambdaField, curSink);
                    } else {
                        System.out.println("WARNING: Did not find consumer field in " + curSink);
                    }
                }

                if (curSinkLambda == null) {
                    System.out.println("WARNING: cannot determine lambda from " + curSink);
                } else {
                    entry.lambdaObj = curSinkLambda;
                    entry.lambdaClass = curSinkLambda.getClass();
// System.out.println("entry.lambdaObj: " + entry.lambdaObj
// + " ... entry.lambdaClass=" + entry.lambdaClass);
                }

                // to move to the next downstream entry
                Class<?> curSinkSupKlass = curSinkKlass.getSuperclass();
                Field downstreamField = findFieldStartsWith(curSinkSupKlass, "downstream");
                Object downstream;
                if (downstreamField != null) {
                    Class<?> downstreamKlass = downstreamField.getType();
                    downstream = downstreamField.get(curSink);
                } else {
                    downstream = null;   // at the terminal op
                }
                info.add(entry);

                // for next iteration
                curSink = downstream;
                opNum++;
            }

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return info;
    }

    public static boolean isRangeIntSpliterator(Spliterator sp) {
        return (sp instanceof Streams.RangeIntSpliterator);
    }

    static final ConcurrentHashMap<PipelineInfo, Object> pipelineKernels = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<PipelineInfo, Boolean> haveGoodPipelineKernel = new ConcurrentHashMap<>();

    static boolean neverRevertToJava = Boolean.getBoolean("com.amd.sumatra.neverRevertToJava");

    static Sumatra sumatra = SumatraFactory.getSumatra();

    public static <P_OUT> void revertParallelForEachJava(Spliterator sp, PipelineHelper<P_OUT> helper, Object forEachOpInstance) {
        ((ForEachOps.ForEachOp) forEachOpInstance).evaluateParallelForEachJava(helper, sp);
    }

    static final String[] arrayBasedCollectionSpliteratorNames = {"java.util.ArrayList$ArrayListSpliterator", "java.util.Vector$VectorSpliterator"};

    static ArrayList<String> arrayBasedCollectionSpliterators;

    static {
        arrayBasedCollectionSpliterators = new ArrayList<String>(arrayBasedCollectionSpliteratorNames.length);
        for (int i = 0; i < arrayBasedCollectionSpliteratorNames.length; i++) {
            arrayBasedCollectionSpliterators.add(arrayBasedCollectionSpliteratorNames[i]);
        }
    }

    static <S> Spliterator offloadableSpliterator(Spliterator sp) {
        // don't have to check parallel, we got here from parallelForEach
        // any array spliterator is legal, I guess

        if (isArraySpliterator(sp)) {
            return sp;
        } else if (PipelineInfo.isRangeIntSpliterator(sp)) {
            // certain kinds of intRangeSpliterators are ok
            Streams.RangeIntSpliterator risp = (Streams.RangeIntSpliterator) sp;
            if (risp.getFrom() == 0) {
                return sp;
            }
        } else if (arrayBasedCollectionSpliterators.contains(sp.getClass().getName())) {
            return sp;
        }
        // if we got this far, it's not OK
        return null;
    }

    static final String NEWLINE = System.getProperty("line.separator");
    static final String REVERT_MSG = "WARNING: reverting to java, offload kernel could not be created." + NEWLINE + "Check HSAIL tools are on your PATH and LD_LIBRARY_PATH" + NEWLINE +
                    "and on the sun.boot.library.path";

    static public <P_IN, P_OUT> void offloadPipeline(Spliterator sp, PipelineHelper<P_OUT> helper, Sink<P_IN> sink, Object forEachOpInstance) {

        PipelineInfo pipelineInfo = null;
        Boolean haveKernel;
        boolean isObjectLambda = false;
        boolean success = false;
        try {
            pipelineInfo = PipelineInfo.deducePipeline(sink);
            Object okraKernel = null;
            isObjectLambda = !PipelineInfo.isRangeIntSpliterator(sp);
            // for now we only handle pipeline sizes of 1
            if (pipelineInfo.size() == 1) {
                Object consumer = pipelineInfo.get(0).lambdaObj;
                Class consumerClass = consumer.getClass();
                haveKernel = haveGoodPipelineKernel.get(pipelineInfo);
                if (haveKernel == null) {
                    okraKernel = sumatra.createKernel(consumerClass);
                    if (okraKernel != null) {
                        // Store result for later call like kernels
                        pipelineKernels.put(pipelineInfo, okraKernel);
                        haveGoodPipelineKernel.put(pipelineInfo, true);
                    } else {
                        System.out.println(REVERT_MSG);
                        haveGoodPipelineKernel.put(pipelineInfo, false);
                    }
                } else if (haveKernel == true) {
                    okraKernel = pipelineKernels.get(pipelineInfo);
                }

                // now if we have a kernel, dispatch to it
                if (okraKernel != null) {
                    // try to dispatch
                    // Extract actual args from Consumer
                    // Push args using okra api

                    Field[] fields = consumerClass.getDeclaredFields();
                    ArrayList<Object> args = new ArrayList<Object>();
                    int argIndex = 0;
                    for (Field f : fields) {
                        // logger.info("... " + f);
                        args.add(SumatraUtils.getFieldFromObject(f, consumer));
                    }

                    // Secretly pass in the source array reference, each element
                    // will be retrieved using the workitem id
                    if (isObjectLambda) {
                        Field f;
                        String spName = sp.getClass().getName();
                        if (arrayBasedCollectionSpliterators.contains(spName) == true) {
                            Field listField = sp.getClass().getDeclaredField("list");
                            AbstractList list = (AbstractList) SumatraUtils.getFieldFromObject(listField, sp);
                            // We want ArrayList.elementData
                            f = list.getClass().getDeclaredField("elementData");
                            args.add(SumatraUtils.getFieldFromObject(f, list));
                        } else {
                            // We want ArraySpliterator.array
                            f = sp.getClass().getDeclaredField("array");
                            args.add(SumatraUtils.getFieldFromObject(f, sp));
                        }
                    }
                    success = sumatra.dispatchKernel(okraKernel, (int) sp.estimateSize(), args.toArray());
                }
            }
        } catch (Exception e) {
            System.err.println(e);
            e.printStackTrace();

            if (pipelineInfo != null) {
                haveGoodPipelineKernel.put(pipelineInfo, false);
            }

        } catch (UnsatisfiedLinkError e) {
            System.err.println(e);
            e.printStackTrace();

            if (pipelineInfo != null) {
                haveGoodPipelineKernel.put(pipelineInfo, false);
            }
        }

        // check success flag, if false revert to java
        if (!success) {
            forEachOpenCLPipelineRevertToJava(sp, helper, forEachOpInstance);
        }
    }

    static <P_OUT> void forEachOpenCLPipelineRevertToJava(Spliterator sp, PipelineHelper<P_OUT> helper, Object forEachOpInstance) {

        // the following flag would normally be set in testing mode
        // when we want to consider the need to revert to java a failure
        if (neverRevertToJava) {
            // cause an unchecked exception
            throw new RuntimeException("configured to never revert to Java");
        }

        // get here if it is OK to revert to Java
        // call back to the java fork-join version
        // (call back thru PipelineInfo to avoid having to make the target class public)
        PipelineInfo.revertParallelForEachJava(sp, helper, forEachOpInstance);
    }

    // Sumatra Helper
    public static boolean isArraySpliterator(Spliterator sp) {
        String spName = sp.getClass().getName();
        return spName.equals("java.util.Spliterators$ArraySpliterator");
    }

}
