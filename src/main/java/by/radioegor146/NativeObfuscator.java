package by.radioegor146;

import by.radioegor146.source.CMakeFilesBuilder;
import by.radioegor146.source.ClassSourceBuilder;
import by.radioegor146.source.MainSourceBuilder;
import by.radioegor146.source.StringPool;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import ru.gravit.launchserver.asm.ClassMetadataReader;
import ru.gravit.launchserver.asm.SafeClassWriter;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.Attributes.Name;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class NativeObfuscator {

    private static final Map<Integer, String> INSTRUCTIONS = new HashMap<>();
    private static final Properties CPP_SNIPPETS = new Properties();
    private static final String[] CPP_TYPES = {
        "void", // 0
        "jboolean", // 1
        "jchar", // 2
        "jbyte", // 3
        "jshort", // 4
        "jint", // 5
        "jfloat", // 6
        "jlong", // 7
        "jdouble", // 8
        "jarray", // 9
        "jobject", // 10
        "jobject" // 11
    };

    private static final String[] JAVA_DESCRIPTORS = {
        "V", // 0
        "Z", // 1
        "C", // 2
        "B", // 3
        "S", // 4
        "I", // 5
        "F", // 6
        "J", // 7
        "D", // 8
        "Ljava/lang/Object;", // 9
        "Ljava/lang/Object;", // 10
        "Ljava/lang/Object;" // 11
    };

    private static final int[] TYPE_TO_STACK = {
        1,
        1,
        1,
        1,
        1,
        1,
        1,
        2,
        2,
        0,
        0,
        0
    };

    private static final int[] STACK_TO_STACK = {
        1,
        1,
        1,
        2,
        2,
        0,
        0,
        0,
        0
    };

    static {
        try {
            for (Field f : Opcodes.class.getFields()) {
                INSTRUCTIONS.put((int) f.get(null), f.getName());
            }
            CPP_SNIPPETS.load(NativeObfuscator.class.getClassLoader().getResourceAsStream("sources/cppsnippets.properties"));
        } catch (IllegalArgumentException | IllegalAccessException | IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private StringPool stringPool = new StringPool();
    private InterfaceStaticClassProvider staticClassProvider;

    private NodeCache<String> cachedClasses = new NodeCache<>("(cclasses[%d])");
    private NodeCache<CachedMethodInfo> cachedMethods = new NodeCache<>("(cmethods[%d].load())");
    private NodeCache<CachedFieldInfo> cachedFields = new NodeCache<>("(cfields[%d].load())");

    private StringBuilder nativeMethodsSb;
    private Map<String, InvokeDynamicInsnNode> invokeDynamics = new HashMap<>();

    private String dynamicStringPoolFormat(String key, Map<String, String> tokens) {
        String value = CPP_SNIPPETS.getProperty(key);
        if (value == null) {
            throw new RuntimeException(key + " not found");
        }
        String[] stringVars = CPP_SNIPPETS.getProperty(key + "_S_VARS") == null || CPP_SNIPPETS.getProperty(key + "_S_VARS").isEmpty() ? new String[0] : CPP_SNIPPETS.getProperty(key + "_S_VARS").split(",");
        HashMap<String, String> vars = new HashMap<>();
        for (String var : stringVars) {
            if (var.startsWith("#")) {
                vars.put(var, CPP_SNIPPETS.getProperty(key + "_S_CONST_" + var.substring(1)));
            } else if (var.startsWith("$")) {
                vars.put(var, tokens.get(var.substring(1)));
            } else {
                throw new RuntimeException("Unknown format modifier: " + var);
            }
        }
        vars.entrySet().stream().filter((var) -> (var.getValue() == null)).forEachOrdered((var) -> {
            throw new RuntimeException(key + " - " + var.getKey() + " is null");
        });
        HashMap<String, String> replaceTokens = new HashMap<>();
        vars.forEach((key1, value1) -> replaceTokens.put(key1, stringPool.get(value1)));
        tokens.forEach((key1, value1) -> {
            if (!replaceTokens.containsKey("$" + key1)) {
                replaceTokens.put("$" + key1, value1);
            }
        });
        return Util.dynamicRawFormat(value, replaceTokens);
    }

    private String visitMethod(ClassNode classNode, MethodNode methodNode, int index) {
        if (((methodNode.access & Opcodes.ACC_ABSTRACT) > 0) || ((methodNode.access & Opcodes.ACC_NATIVE) > 0)) {
            return "";
        }
        if (methodNode.name.equals("<init>")) {
            return "";
        }
        StringBuilder outputSb = new StringBuilder("// ");
        outputSb.append(methodNode.name).append(methodNode.desc).append("\n");
        String methodName = "";
        MethodNode proxifiedResult;
        switch (methodNode.name) {
            case "<init>":
                proxifiedResult = new MethodNode(Opcodes.ASM7, Opcodes.ACC_NATIVE | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, "native_special_init" + index, methodNode.desc, methodNode.signature, new String[0]);
                classNode.methods.add(proxifiedResult);
                methodName += "native_special_init";
                break;
            case "<clinit>":
                proxifiedResult = new MethodNode(Opcodes.ASM7, Opcodes.ACC_NATIVE | Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "native_special_clinit" + index, methodNode.desc, methodNode.signature, new String[0]);
                classNode.methods.add(proxifiedResult);
                methodName += "native_special_clinit";
                break;
            default:
                proxifiedResult = methodNode;
                methodNode.access |= Opcodes.ACC_NATIVE;
                methodName += "native_" + methodNode.name;
                break;
        }
        methodName += index;
        methodName = "__ngen_" + methodName.replace("/", "_");
        methodName = Util.escapeCppNameString(methodName);

        int returnTypeSort = Type.getReturnType(methodNode.desc).getSort();
        Type[] args = Type.getArgumentTypes(methodNode.desc);
        MethodNode nativeMethod = null;
        if ((classNode.access & Opcodes.ACC_INTERFACE) > 0) {
            if ((methodNode.access & Opcodes.ACC_STATIC) == 0) {
                List<Type> argsList = new ArrayList<>();
                argsList.add(Type.getType(JAVA_DESCRIPTORS[Type.OBJECT]));
                argsList.addAll(Arrays.asList(args));
                args = argsList.toArray(new Type[0]);
            }

            StringBuilder resultProcType = new StringBuilder("(");
            for (Type t : args) {
                resultProcType.append(JAVA_DESCRIPTORS[t.getSort()]);
            }
            resultProcType.append(")").append(JAVA_DESCRIPTORS[returnTypeSort]);

            String outerJavaMethodName = String.format("iface_static_%d_%d", currentClassId, index);
            nativeMethod = new MethodNode(Opcodes.ASM7, Opcodes.ACC_NATIVE | Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, outerJavaMethodName, resultProcType.toString(), null, new String[0]);

            String methodSource = String.format("            { (char *)%s, (char *)%s, (void *)&%s },\n",
                    stringPool.get(outerJavaMethodName), stringPool.get(resultProcType.toString()), methodName);

            staticClassProvider.addMethod(nativeMethod, methodSource);
        } else {
            nativeMethodsSb
                    .append("            { (char *)")
                    .append(stringPool.get(proxifiedResult.name))
                    .append(", (char *)")
                    .append(stringPool.get(methodNode.desc))
                    .append(", (void *)&")
                    .append(methodName)
                    .append(" },\n");
        }
        outputSb
                .append(CPP_TYPES[returnTypeSort])
                .append(" ")
                .append("JNICALL")
                .append(" ")
                .append(methodName)
                .append("(")
                .append("JNIEnv *env")
                .append(", ")
                .append(((methodNode.access & Opcodes.ACC_STATIC) > 0) ? "jclass clazz" : "jobject obj");
        if (args.length > 0) {
            outputSb.append(", ");
        }
        for (int i = 0; i < args.length; i++) {
            outputSb.append(CPP_TYPES[args[i].getSort()]).append(" ").append("arg").append(i).append(i == args.length - 1 ? "" : ", ");
        }
        outputSb.append(") {").append("\n");
        if (methodNode.maxStack > 0) {
            outputSb.append("    ").append("utils::jvm_stack<").append(methodNode.maxStack).append("> cstack;").append("\n");
        }
        if (methodNode.maxLocals > 0) {
            outputSb.append("    ").append("utils::local_vars<").append(methodNode.maxLocals).append("> clocals;").append("\n");
        }
        outputSb.append("    ").append("std::unordered_set<jobject> refs;").append("\n");
        outputSb.append("\n");
        int localIndex = 0;
        if (((methodNode.access & Opcodes.ACC_STATIC) == 0)) {
            outputSb.append("    ").append(dynamicStringPoolFormat("LOCAL_LOAD_ARG_" + 9,
                    Util.createMap(
                            "index", localIndex,
                            "arg", "obj"
                    ))).append("\n");
            localIndex++;
        }
        for (int i = 0; i < args.length; i++) {
            outputSb.append("    ").append(dynamicStringPoolFormat("LOCAL_LOAD_ARG_" + args[i].getSort(),
                    Util.createMap(
                            "index", localIndex,
                            "arg", "arg" + i
                    ))).append("\n");
            localIndex += args[i].getSize();
        }
        outputSb.append("\n");
        List<TryCatchBlockNode> currentTryCatches = new ArrayList<>();
        int currentLine = -1;
        int invokeSpecialId = -1;
        List<Integer> currentStack = new ArrayList<>();
        List<Integer> currentLocals = new ArrayList<>();
        if ((methodNode.access & Opcodes.ACC_STATIC) == 0) {
            currentLocals.add(TYPE_TO_STACK[Type.OBJECT]);
        }
        for (Type localArg : args) {
            currentLocals.add(TYPE_TO_STACK[localArg.getSort()]);
        }
        for (int insnIndex = 0; insnIndex < methodNode.instructions.size(); insnIndex++) {
            if (methodNode.name.equals("<init>") && invokeSpecialId < 0) {
                if (methodNode.instructions.get(insnIndex).getOpcode() == Opcodes.INVOKESPECIAL) {
                    invokeSpecialId = insnIndex;
                }
                continue;
            }
            AbstractInsnNode insnNode = methodNode.instructions.get(insnIndex);
            switch (insnNode.getType()) {
                case AbstractInsnNode.LABEL:
                    outputSb.append(((LabelNode) insnNode).getLabel()).append(": ;").append("\n");
                    Util.reverse(methodNode.tryCatchBlocks.stream().filter((node) -> (node.start.equals(insnNode)))).forEachOrdered(currentTryCatches::add);
                    methodNode.tryCatchBlocks.stream().filter((node) -> (node.end.equals(insnNode))).forEachOrdered(currentTryCatches::remove);
                    break;
                case AbstractInsnNode.LINE:
                    outputSb.append("    ").append("// Line ").append(((LineNumberNode) insnNode).line).append(":").append("\n");
                    currentLine = ((LineNumberNode) insnNode).line;
                    break;
                case AbstractInsnNode.FRAME:
                    FrameNode frameNode = (FrameNode) insnNode;
                    switch (frameNode.type) {
                        case Opcodes.F_APPEND:
                            frameNode.local.forEach((local) -> {
                                if (local instanceof String) {
                                    currentLocals.add(TYPE_TO_STACK[Type.OBJECT]);
                                } else if (local instanceof LabelNode) {
                                    currentLocals.add(TYPE_TO_STACK[Type.OBJECT]);
                                } else {
                                    currentLocals.add(STACK_TO_STACK[(int) local]);
                                }
                            });
                            break;
                        case Opcodes.F_CHOP:
                            frameNode.local.forEach((_item) -> {
                                currentLocals.remove(currentLocals.size() - 1);
                            });
                            currentStack.clear();
                            break;
                        case Opcodes.F_NEW:
                        case Opcodes.F_FULL:
                            currentLocals.clear();
                            currentStack.clear();
                            frameNode.local.forEach((local) -> {
                                if (local instanceof String) {
                                    currentLocals.add(TYPE_TO_STACK[Type.OBJECT]);
                                } else if (local instanceof LabelNode) {
                                    currentLocals.add(TYPE_TO_STACK[Type.OBJECT]);
                                } else {
                                    currentLocals.add(STACK_TO_STACK[(int) local]);
                                }
                            });
                            frameNode.stack.forEach((stack) -> {
                                if (stack instanceof String) {
                                    currentStack.add(TYPE_TO_STACK[Type.OBJECT]);
                                } else if (stack instanceof LabelNode) {
                                    currentStack.add(TYPE_TO_STACK[Type.OBJECT]);
                                } else {
                                    currentStack.add(STACK_TO_STACK[(int) stack]);
                                }
                            });
                            break;
                        case Opcodes.F_SAME:
                            break;
                        case Opcodes.F_SAME1:
                            if (frameNode.stack.get(0) instanceof String) {
                                currentStack.add(TYPE_TO_STACK[Type.OBJECT]);
                            } else if (frameNode.stack.get(0) instanceof LabelNode) {
                                currentStack.add(TYPE_TO_STACK[Type.OBJECT]);
                            } else {
                                currentStack.add(STACK_TO_STACK[(int) frameNode.stack.get(0)]);
                            }
                            break;
                    }
                    if (currentStack.stream().anyMatch(x -> x == 0)) {
                        int currentSp = 0;
                        outputSb.append("    ");
                        for (int type : currentStack) {
                            if (type == 0) {
                                outputSb.append("refs.erase(cstack.refs[").append(currentSp).append("]); ");
                            }
                            currentSp += Math.max(1, type);
                        }
                        outputSb.append("\n");
                    }
                    if (currentLocals.stream().anyMatch(x -> x == 0)) {
                        int currentLp = 0;
                        outputSb.append("    ");
                        for (int type : currentLocals) {
                            if (type == 0) {
                                outputSb.append("refs.erase(clocals.refs[").append(currentLp).append("]); ");
                            }
                            currentLp += Math.max(1, type);
                        }
                        outputSb.append("\n");
                    }
                    outputSb.append("    utils::clear_refs(env, refs);\n");
                    break;
                default:
                    StringBuilder tryCatch = new StringBuilder("\n");
                    if (currentTryCatches.size() > 0) {
                        tryCatch.append("    ").append(dynamicStringPoolFormat("TRYCATCH_START", Util.createMap())).append("\n");
                        for (int i = currentTryCatches.size() - 1; i >= 0; i--) {
                            TryCatchBlockNode tryCatchBlock = currentTryCatches.get(i);
                            if (tryCatchBlock.type == null) {
                                tryCatch.append("    ").append(dynamicStringPoolFormat("TRYCATCH_ANY_L", Util.createMap(
                                        "rettype", CPP_TYPES[returnTypeSort],
                                        "handler_block", tryCatchBlock.handler.getLabel().toString()
                                ))).append("\n");
                                break;
                            } else {
                                tryCatch.append("    ").append(dynamicStringPoolFormat("TRYCATCH_CHECK", Util.createMap(
                                        "rettype", CPP_TYPES[returnTypeSort],
                                        "exception_class_ptr", cachedClasses.getPointer(tryCatchBlock.type),
                                        "handler_block", tryCatchBlock.handler.getLabel().toString()
                                ))).append("\n");
                            }
                        }
                        tryCatch.append("    ").append(dynamicStringPoolFormat("TRYCATCH_END", Util.createMap("rettype", CPP_TYPES[returnTypeSort])));
                    } else {
                        tryCatch.append("    ").append(dynamicStringPoolFormat("TRYCATCH_EMPTY", Util.createMap("rettype", CPP_TYPES[returnTypeSort])));
                    }
                    outputSb.append("    ");
                    String insnName = INSTRUCTIONS.getOrDefault(insnNode.getOpcode(), "NOTFOUND");
                    HashMap<String, String> props = new HashMap<>();
                    props.put("line", String.valueOf(currentLine));
                    props.put("trycatchhandler", tryCatch.toString());
                    props.put("rettype", CPP_TYPES[returnTypeSort]);
                    String trimmedTryCatchBlock = tryCatch.toString().trim().replace("\n", " ");
                    if (insnNode instanceof FieldInsnNode) {
                        insnName += "_" + Type.getType(((FieldInsnNode) insnNode).desc).getSort();
                        if (insnNode.getOpcode() == Opcodes.GETSTATIC || insnNode.getOpcode() == Opcodes.PUTSTATIC) {
                            props.put("class_ptr", cachedClasses.getPointer(((FieldInsnNode) insnNode).owner));
                        }
                        int fieldId = cachedFields.getId(new CachedFieldInfo(
                                ((FieldInsnNode) insnNode).owner,
                                ((FieldInsnNode) insnNode).name,
                                ((FieldInsnNode) insnNode).desc,
                                insnNode.getOpcode() == Opcodes.GETSTATIC || insnNode.getOpcode() == Opcodes.PUTSTATIC));

                        outputSb.append("if (!cfields[")
                                .append(fieldId)
                                .append("].load()) { cfields[")
                                .append(fieldId)
                                .append("].store(env->Get")
                                .append((insnNode.getOpcode() == Opcodes.GETSTATIC || insnNode.getOpcode() == Opcodes.PUTSTATIC) ? "Static" : "")
                                .append("FieldID(")
                                .append(cachedClasses.getPointer(((FieldInsnNode) insnNode).owner))
                                .append(", ")
                                .append(stringPool.get(((FieldInsnNode) insnNode).name))
                                .append(", ")
                                .append(stringPool.get(((FieldInsnNode) insnNode).desc))
                                .append(")); ")
                                .append(trimmedTryCatchBlock)
                                .append("  } ");
                        props.put("fieldid", cachedFields.getPointer(new CachedFieldInfo(
                                ((FieldInsnNode) insnNode).owner,
                                ((FieldInsnNode) insnNode).name,
                                ((FieldInsnNode) insnNode).desc,
                                insnNode.getOpcode() == Opcodes.GETSTATIC || insnNode.getOpcode() == Opcodes.PUTSTATIC
                        )));
                    }
                    if (insnNode instanceof IincInsnNode) {
                        props.put("incr", String.valueOf(((IincInsnNode) insnNode).incr));
                        props.put("var", String.valueOf(((IincInsnNode) insnNode).var));
                    }
                    if (insnNode instanceof IntInsnNode) {
                        props.put("operand", String.valueOf(((IntInsnNode) insnNode).operand));
                        if (insnNode.getOpcode() == Opcodes.NEWARRAY) {
                            insnName += "_" + ((IntInsnNode) insnNode).operand;
                        }
                    }
                    if (insnNode instanceof InvokeDynamicInsnNode) {
                        String indyMethodName = "invokedynamic$" + methodNode.name + "$" + invokeDynamics.size();
                        invokeDynamics.put(indyMethodName, (InvokeDynamicInsnNode) insnNode);
                        Type returnType = Type.getReturnType(((InvokeDynamicInsnNode) insnNode).desc);
                        Type[] argTypes = Type.getArgumentTypes(((InvokeDynamicInsnNode) insnNode).desc);
                        insnName = "INVOKESTATIC_" + returnType.getSort();
                        StringBuilder argsBuilder = new StringBuilder();
                        List<Integer> argOffsets = new ArrayList<>();
                        List<Integer> argSorts = new ArrayList<>();
                        int stackOffset = -1;
                        for (Type argType : argTypes) {
                            int currentOffset = stackOffset;
                            stackOffset -= argType.getSize();
                            argOffsets.add(currentOffset);
                            argSorts.add(argType.getSort());
                        }
                        for (int i = 0; i < argOffsets.size(); i++) {
                            argsBuilder.append(", ").append(dynamicStringPoolFormat("INVOKE_ARG_" + argSorts.get(i), Util.createMap("index", String.valueOf(argOffsets.get(i)))));
                        }
                        outputSb.append(dynamicStringPoolFormat("INVOKE_POPCNT", Util.createMap("count", String.valueOf(-stackOffset - 1)))).append(" ");
                        props.put("class_ptr", cachedClasses.getPointer(classNode.name));
                        int methodId = cachedMethods.getId(new CachedMethodInfo(
                                classNode.name,
                                indyMethodName,
                                ((InvokeDynamicInsnNode) insnNode).desc,
                                true
                        ));
                        outputSb.append("if (!cmethods[")
                                .append(methodId)
                                .append("].load()) { cmethods[")
                                .append(methodId)
                                .append("].store(env->GetStaticMethodID(")
                                .append(cachedClasses.getPointer(classNode.name))
                                .append(", ")
                                .append(stringPool.get(indyMethodName))
                                .append(", ")
                                .append(stringPool.get(((InvokeDynamicInsnNode) insnNode).desc))
                                .append(")); ")
                                .append(trimmedTryCatchBlock)
                                .append("  } ");
                        props.put("methodid", cachedMethods.getPointer(new CachedMethodInfo(
                                classNode.name,
                                indyMethodName,
                                ((InvokeDynamicInsnNode) insnNode).desc,
                                true
                        )));
                        props.put("args", argsBuilder.toString());
                    }
                    if (insnNode instanceof JumpInsnNode) {
                        props.put("label", String.valueOf(((JumpInsnNode) insnNode).label.getLabel()));
                    }
                    if (insnNode instanceof LdcInsnNode) {
                        Object cst = ((LdcInsnNode) insnNode).cst;
                        if (cst instanceof java.lang.String) {
                            insnName += "_STRING";
                            props.put("cst", String.valueOf(((LdcInsnNode) insnNode).cst));
                        } else if (cst instanceof java.lang.Integer) {
                            insnName += "_INT";
                            props.put("cst", String.valueOf(((LdcInsnNode) insnNode).cst));
                        } else if (cst instanceof java.lang.Long) {
                            insnName += "_LONG";
                            long cstVal = (long) cst;
                            if (cstVal == Long.MIN_VALUE) {
                                props.put("cst", "(jlong) 9223372036854775808ULL");
                            } else {
                                props.put("cst", ((LdcInsnNode) insnNode).cst + "LL");
                            }
                        } else if (cst instanceof java.lang.Float) {
                            insnName += "_FLOAT";
                            props.put("cst", String.valueOf(((LdcInsnNode) insnNode).cst));
                            float cstVal = (float) cst;
                            if (cst.toString().equals("NaN")) {
                                props.put("cst", "NAN");
                            } else if (cstVal == Float.POSITIVE_INFINITY) {
                                props.put("cst", "HUGE_VALF");
                            } else if (cstVal == Float.NEGATIVE_INFINITY) {
                                props.put("cst", "-HUGE_VALF");
                            }
                        } else if (cst instanceof java.lang.Double) {
                            insnName += "_DOUBLE";
                            props.put("cst", String.valueOf(((LdcInsnNode) insnNode).cst));
                            double cstVal = (double) cst;
                            if (cst.toString().equals("NaN")) {
                                props.put("cst", "NAN");
                            } else if (cstVal == Double.POSITIVE_INFINITY) {
                                props.put("cst", "HUGE_VAL");
                            } else if (cstVal == Double.NEGATIVE_INFINITY) {
                                props.put("cst", "-HUGE_VAL");
                            }
                        } else if (cst instanceof org.objectweb.asm.Type) {
                            insnName += "_CLASS";
                            props.put("cst_ptr", cachedClasses.getPointer(((LdcInsnNode) insnNode).cst.toString()));
                        } else {
                            throw new UnsupportedOperationException();
                        }
                    }
                    if (insnNode instanceof LookupSwitchInsnNode) {
                        outputSb.append(dynamicStringPoolFormat("LOOKUPSWITCH_START", Util.createMap())).append("\n");
                        for (int switchIndex = 0; switchIndex < ((LookupSwitchInsnNode) insnNode).labels.size(); switchIndex++) {
                            outputSb.append("    ").append("    ").append(dynamicStringPoolFormat("LOOKUPSWITCH_PART", Util.createMap(
                                    "key", String.valueOf(((LookupSwitchInsnNode) insnNode).keys.get(switchIndex)),
                                    "label", String.valueOf(((LookupSwitchInsnNode) insnNode).labels.get(switchIndex).getLabel())
                            ))).append("\n");
                        }
                        outputSb.append("    ").append("    ").append(dynamicStringPoolFormat("LOOKUPSWITCH_DEFAULT", Util.createMap(
                                "label", String.valueOf(((LookupSwitchInsnNode) insnNode).dflt.getLabel())
                        ))).append("\n");
                        outputSb.append("    ").append(dynamicStringPoolFormat("LOOKUPSWITCH_END", Util.createMap())).append("\n");
                        continue;
                    }
                    if (insnNode instanceof MethodInsnNode) {
                        Type returnType = Type.getReturnType(((MethodInsnNode) insnNode).desc);
                        Type[] argTypes = Type.getArgumentTypes(((MethodInsnNode) insnNode).desc);
                        insnName += "_" + returnType.getSort();
                        StringBuilder argsBuilder = new StringBuilder();
                        List<Integer> argOffsets = new ArrayList<>();
                        List<Integer> argSorts = new ArrayList<>();
                        int stackOffset = -1;
                        for (Type argType : argTypes) {
                            int currentOffset = stackOffset;
                            stackOffset -= argType.getSize();
                            argOffsets.add(currentOffset);
                            argSorts.add(argType.getSort());
                        }
                        if (insnNode.getOpcode() == Opcodes.INVOKEINTERFACE || insnNode.getOpcode() == Opcodes.INVOKESPECIAL || insnNode.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                            for (int i = 0; i < argOffsets.size(); i++) {
                                argsBuilder.append(", ").append(dynamicStringPoolFormat("INVOKE_ARG_" + argSorts.get(i), Util.createMap("index", String.valueOf(argOffsets.get(i) - 1))));
                            }
                            if (stackOffset != 0) {
                                outputSb.append(dynamicStringPoolFormat("INVOKE_POPCNT", Util.createMap("count", String.valueOf(-stackOffset)))).append(" ");
                            }
                            if (insnNode.getOpcode() == Opcodes.INVOKESPECIAL) {
                                props.put("class_ptr", cachedClasses.getPointer(((MethodInsnNode) insnNode).owner));
                            }
                            int methodId = cachedMethods.getId(new CachedMethodInfo(
                                    ((MethodInsnNode) insnNode).owner,
                                    ((MethodInsnNode) insnNode).name,
                                    ((MethodInsnNode) insnNode).desc,
                                    false
                            ));
                            outputSb.append("if (!cmethods[")
                                    .append(methodId)
                                    .append("].load()) { cmethods[")
                                    .append(methodId)
                                    .append("].store(env->GetMethodID(")
                                    .append(cachedClasses.getPointer(((MethodInsnNode) insnNode).owner))
                                    .append(", ")
                                    .append(stringPool.get(((MethodInsnNode) insnNode).name))
                                    .append(", ")
                                    .append(stringPool.get(((MethodInsnNode) insnNode).desc))
                                    .append(")); ")
                                    .append(trimmedTryCatchBlock)
                                    .append("  } ");
                            props.put("methodid", cachedMethods.getPointer(new CachedMethodInfo(
                                    ((MethodInsnNode) insnNode).owner,
                                    ((MethodInsnNode) insnNode).name,
                                    ((MethodInsnNode) insnNode).desc,
                                    false
                            )));
                            props.put("object_offset", "-1");
                            props.put("args", argsBuilder.toString());
                        } else {
                            for (int i = 0; i < argOffsets.size(); i++) {
                                argsBuilder.append(", ").append(dynamicStringPoolFormat("INVOKE_ARG_" + argSorts.get(i), Util.createMap("index", String.valueOf(argOffsets.get(i)))));
                            }
                            if (-stackOffset - 1 != 0) {
                                outputSb.append(dynamicStringPoolFormat("INVOKE_POPCNT", Util.createMap("count", String.valueOf(-stackOffset - 1)))).append(" ");
                            }
                            props.put("class_ptr", cachedClasses.getPointer(((MethodInsnNode) insnNode).owner));
                            int methodId = cachedMethods.getId(new CachedMethodInfo(
                                    ((MethodInsnNode) insnNode).owner,
                                    ((MethodInsnNode) insnNode).name,
                                    ((MethodInsnNode) insnNode).desc,
                                    true
                            ));
                            outputSb.append("if (!cmethods[")
                                    .append(methodId)
                                    .append("].load()) { cmethods[")
                                    .append(methodId)
                                    .append("].store(env->GetStaticMethodID(")
                                    .append(cachedClasses.getPointer(((MethodInsnNode) insnNode).owner))
                                    .append(", ")
                                    .append(stringPool.get(((MethodInsnNode) insnNode).name))
                                    .append(", ")
                                    .append(stringPool.get(((MethodInsnNode) insnNode).desc))
                                    .append(")); ")
                                    .append(trimmedTryCatchBlock)
                                    .append("  } ");
                            props.put("methodid", cachedMethods.getPointer(new CachedMethodInfo(
                                    ((MethodInsnNode) insnNode).owner,
                                    ((MethodInsnNode) insnNode).name,
                                    ((MethodInsnNode) insnNode).desc,
                                    true
                            )));
                            props.put("args", argsBuilder.toString());
                        }
                    }
                    if (insnNode instanceof MultiANewArrayInsnNode) {
                        props.put("count", String.valueOf(((MultiANewArrayInsnNode) insnNode).dims));
                        props.put("desc", ((MultiANewArrayInsnNode) insnNode).desc);
                    }
                    if (insnNode instanceof TableSwitchInsnNode) {
                        outputSb.append(dynamicStringPoolFormat("TABLESWITCH_START", Util.createMap())).append("\n");
                        for (int switchIndex = 0; switchIndex < ((TableSwitchInsnNode) insnNode).labels.size(); switchIndex++) {
                            outputSb.append("    ").append("    ").append(dynamicStringPoolFormat("TABLESWITCH_PART", Util.createMap(
                                    "index", String.valueOf(((TableSwitchInsnNode) insnNode).min + switchIndex),
                                    "label", String.valueOf(((TableSwitchInsnNode) insnNode).labels.get(switchIndex).getLabel())
                            ))).append("\n");
                        }
                        outputSb.append("    ").append("    ").append(dynamicStringPoolFormat("TABLESWITCH_DEFAULT", Util.createMap(
                                "label", String.valueOf(((TableSwitchInsnNode) insnNode).dflt.getLabel())
                        ))).append("\n");
                        outputSb.append("    ").append(dynamicStringPoolFormat("TABLESWITCH_END", Util.createMap())).append("\n");
                        continue;
                    }
                    if (insnNode instanceof TypeInsnNode) {
                        props.put("desc", (((TypeInsnNode) insnNode).desc));
                        props.put("desc_ptr", cachedClasses.getPointer(((TypeInsnNode) insnNode).desc));
                    }
                    if (insnNode instanceof VarInsnNode) {
                        props.put("var", String.valueOf(((VarInsnNode) insnNode).var));
                    }
                    String cppCode = CPP_SNIPPETS.getProperty(insnName);
                    if (cppCode == null) {
                        throw new RuntimeException("insn not found: " + insnName);
                    } else {
                        cppCode = dynamicStringPoolFormat(insnName, props);
                        outputSb.append(cppCode);
                    }
                    outputSb.append("\n");
                    break;
            }
        }
        outputSb.append("    return (").append(CPP_TYPES[returnTypeSort]).append(") 0;\n");
        outputSb.append("}\n\n");

        methodNode.localVariables.clear();
        methodNode.tryCatchBlocks.clear();

        switch (methodNode.name) {
            case "<init>": {
                InsnList list = new InsnList();
                for (int i = 0; i <= invokeSpecialId; i++) {
                    list.add(methodNode.instructions.get(i));
                }
                list.add(new VarInsnNode(Opcodes.ALOAD, 0));
                int localVarsPosition = 1;
                for (Type arg : args) {
                    list.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), localVarsPosition));
                    localVarsPosition += arg.getSize();
                }
                list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, classNode.name, "native_special_init" + index, methodNode.desc));
                list.add(new InsnNode(Opcodes.RETURN));
                methodNode.instructions = list;
            }
            break;
            case "<clinit>":
                methodNode.instructions.clear();
                methodNode.instructions.add(new LdcInsnNode(currentClassId));
                methodNode.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, nativeDir + "/Loader", "registerNativesForClass", "(I)V"));
                methodNode.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, classNode.name, "native_special_clinit" + index, methodNode.desc));
                if ((classNode.access & Opcodes.ACC_INTERFACE) > 0) {
                    if (nativeMethod == null) {
                        throw new RuntimeException("Native method not created?!");
                    }
                    proxifiedResult.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            staticClassProvider.getCurrentClassName(), nativeMethod.name, nativeMethod.desc));
                    proxifiedResult.instructions.add(new InsnNode(Opcodes.RETURN));
                }
                methodNode.instructions.add(new InsnNode(Opcodes.RETURN));
                break;
            default:
                methodNode.instructions.clear();
                if ((classNode.access & Opcodes.ACC_INTERFACE) > 0) {
                    InsnList list = new InsnList();
                    for (int i = 0; i <= invokeSpecialId; i++) {
                        list.add(methodNode.instructions.get(i));
                    }
                    int localVarsPosition = 0;
                    for (Type arg : args) {
                        list.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), localVarsPosition));
                        localVarsPosition += arg.getSize();
                    }
                    if (nativeMethod == null) {
                        throw new RuntimeException("Native method not created?!");
                    }
                    list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            staticClassProvider.getCurrentClassName(), nativeMethod.name, nativeMethod.desc));
                    list.add(new InsnNode(Type.getReturnType(methodNode.desc).getOpcode(Opcodes.IRETURN)));
                    methodNode.instructions = list;
                }
                break;
        }

        return outputSb.toString();
    }

    private void processIndy(ClassNode classNode, String methodName, InvokeDynamicInsnNode indy) {
        MethodNode indyWrapper = new MethodNode(Opcodes.ASM7, Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_STATIC, methodName, indy.desc, null, new String[0]);
        int localVarsPosition = 0;
        for (Type arg : Type.getArgumentTypes(indy.desc)) {
            indyWrapper.instructions.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), localVarsPosition));
            localVarsPosition += arg.getSize();
        }
        indyWrapper.instructions.add(new InvokeDynamicInsnNode(indy.name, indy.desc, indy.bsm, indy.bsmArgs));
        indyWrapper.instructions.add(new InsnNode(Opcodes.ARETURN));
        classNode.methods.add(indyWrapper);
    }

    private int currentClassId;
    private String nativeDir;

    public void process(Path inputJarPath, Path outputDir, List<Path> libs) throws IOException {
        libs.add(inputJarPath);

        ClassMetadataReader metadataReader = new ClassMetadataReader(libs.stream().map(x -> {
            try {
                return new JarFile(x.toFile());
            } catch (IOException ex) {
                return null;
            }
        }).collect(Collectors.toList()));

        Path cppDir = outputDir.resolve("cpp");
        Path cppOutput = cppDir.resolve("output");
        Files.createDirectories(cppOutput);

        Util.copyResource(Paths.get("sources", "native_jvm.cpp"), cppDir);
        Util.copyResource(Paths.get("sources", "native_jvm.hpp"), cppDir);
        Util.copyResource(Paths.get("sources", "native_jvm_output.hpp"), cppDir);
        Util.copyResource(Paths.get("sources", "string_pool.hpp"), cppDir);

        String projectName = String.format("native_jvm_classes_%s",
                inputJarPath.getFileName().toString().replaceAll("[$#.\\s/]", "_"));

        CMakeFilesBuilder cMakeBuilder = new CMakeFilesBuilder(projectName);
        cMakeBuilder.addMainFile("native_jvm.hpp");
        cMakeBuilder.addMainFile("native_jvm.cpp");
        cMakeBuilder.addMainFile("native_jvm_output.hpp");
        cMakeBuilder.addMainFile("native_jvm_output.cpp");
        cMakeBuilder.addMainFile("string_pool.hpp");
        cMakeBuilder.addMainFile("string_pool.cpp");

        MainSourceBuilder mainSourceBuilder = new MainSourceBuilder();

        File jarFile = inputJarPath.toAbsolutePath().toFile();
        try (JarFile jar = new JarFile(jarFile);
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(outputDir.resolve(jarFile.getName())))) {

            System.out.println("Processing " + jarFile + "...");

            int nativeDirId = IntStream.iterate(0, i -> i + 1)
                    .filter(i -> jar.stream().noneMatch(x -> x.getName().startsWith("native" + i)))
                    .findFirst().orElseThrow(RuntimeException::new);
            nativeDir = "native" + nativeDirId;

            staticClassProvider = new InterfaceStaticClassProvider(nativeDir);

            jar.stream().forEach(entry -> {
                if(entry.getName().equals(JarFile.MANIFEST_NAME)) return;

                try {
                    if (!entry.getName().endsWith(".class")) {
                        Util.writeEntry(jar, out, entry);
                        return;
                    }

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (InputStream in = jar.getInputStream(entry)) {
                        Util.transfer(in, baos);
                    }
                    byte[] src = baos.toByteArray();

                    if (Util.byteArrayToInt(Arrays.copyOfRange(src, 0, 4)) != 0xCAFEBABE) {
                        Util.writeEntry(out, entry.getName(), src);
                        return;
                    }

                    nativeMethodsSb = new StringBuilder();
                    invokeDynamics = new HashMap<>();

                    ClassReader classReader = new ClassReader(src);
                    ClassNode classNode = new ClassNode(Opcodes.ASM7);
                    classReader.accept(classNode, 0);

                    if (classNode.methods.stream().noneMatch(x -> (x.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0 && !x.name.equals("<init>"))) {
                        System.out.println("Skipping " + classNode.name);
                        Util.writeEntry(out, entry.getName(), src);
                        return;
                    }

                    System.out.println("Processing " + classNode.name);
                    if (classNode.methods.stream().noneMatch(x -> x.name.equals("<clinit>"))) {
                        classNode.methods.add(new MethodNode(Opcodes.ASM7, Opcodes.ACC_STATIC, "<clinit>", "()V", null, new String[0]));
                    }

                    staticClassProvider.newClass();

                    cachedClasses.clear();
                    cachedMethods.clear();
                    cachedFields.clear();

                    try (ClassSourceBuilder cppBuilder = new ClassSourceBuilder(cppOutput, classNode.name, stringPool)) {
                        StringBuilder instructions = new StringBuilder();

                        classNode.sourceFile = cppBuilder.getCppFilename();
                        for (int i = 0; i < classNode.methods.size(); i++) {
                            MethodNode method = classNode.methods.get(i);
                            instructions.append(visitMethod(classNode, method, i)
                                    .replace("\n", "\n    "));

                            if((classNode.access & Opcodes.ACC_INTERFACE) > 0) {
                                method.access &= ~Opcodes.ACC_NATIVE;
                            }
                        }

                        invokeDynamics.forEach((key, value) -> processIndy(classNode, key, value));

                        classNode.version = 52;
                        ClassWriter classWriter = new SafeClassWriter(metadataReader,
                                Opcodes.ASM7 | ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                        classNode.accept(classWriter);
                        Util.writeEntry(out, entry.getName(), classWriter.toByteArray());

                        cppBuilder.addHeader(cachedClasses.size(), cachedMethods.size(), cachedFields.size());
                        cppBuilder.addInstructions(instructions.toString());
                        cppBuilder.registerMethods(cachedClasses, nativeMethodsSb.toString(), staticClassProvider);

                        cMakeBuilder.addClassFile("output/" + cppBuilder.getHppFilename());
                        cMakeBuilder.addClassFile("output/" + cppBuilder.getCppFilename());

                        mainSourceBuilder.addHeader(cppBuilder.getHppFilename());
                        mainSourceBuilder.registerClassMethods(currentClassId, cppBuilder.getFilename());
                    }
                    currentClassId++;
                } catch (IOException e1) {
                    e1.printStackTrace(System.err);
                }
            });
            for (ClassNode ifaceStaticClass : staticClassProvider.getReadyClasses()) {
                ClassWriter classWriter = new SafeClassWriter(metadataReader, Opcodes.ASM7 | ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                ifaceStaticClass.accept(classWriter);
                Util.writeEntry(out, ifaceStaticClass.name + ".class", classWriter.toByteArray());
            }

            Manifest mf = jar.getManifest();
            ClassNode loaderClass = new ClassNode();
            loaderClass.sourceFile = "synthetic";
            loaderClass.name = "native" + nativeDirId + "/Loader";
            loaderClass.version = 52;
            loaderClass.superName = "java/lang/Object";
            loaderClass.access = Opcodes.ACC_PUBLIC;
            MethodNode registerNativesForClassMethod = new MethodNode(Opcodes.ASM7, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE, "registerNativesForClass", "(I)V", null, new String[0]);
            loaderClass.methods.add(registerNativesForClassMethod);
            ClassWriter classWriter = new SafeClassWriter(metadataReader, Opcodes.ASM7 | ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            loaderClass.accept(classWriter);
            Util.writeEntry(out, "native" + nativeDirId + "/Loader.class", classWriter.toByteArray());
            System.out.println("Jar file ready!");
            String mainClass = (String) mf.getMainAttributes().get(Name.MAIN_CLASS);
            if (mainClass != null) {
                System.out.println("Creating bootstrap classes...");
                mf.getMainAttributes().put(Name.MAIN_CLASS, "native" + nativeDirId + "/Bootstrap");
                ClassNode bootstrapClass = new ClassNode(Opcodes.ASM7);
                bootstrapClass.sourceFile = "synthetic";
                bootstrapClass.name = "native" + nativeDirId + "/Bootstrap";
                bootstrapClass.version = 52;
                bootstrapClass.superName = "java/lang/Object";
                bootstrapClass.access = Opcodes.ACC_PUBLIC;
                MethodNode mainMethod = new MethodNode(Opcodes.ASM7, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, new String[0]);
                mainMethod.instructions.add(new LdcInsnNode(projectName));
                mainMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "loadLibrary", "(Ljava/lang/String;)V"));
                mainMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                mainMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, mainClass.replace(".", "/"), "main", "([Ljava/lang/String;)V"));
                mainMethod.instructions.add(new InsnNode(Opcodes.RETURN));
                bootstrapClass.methods.add(mainMethod);
                bootstrapClass.accept(classWriter);
                Util.writeEntry(out, "native" + nativeDirId + "/Bootstrap.class", classWriter.toByteArray());
                System.out.println("Created!");
            } else {
                System.out.println("Main-Class not found - no bootstrap classes!");
            }
            out.putNextEntry(new ZipEntry(JarFile.MANIFEST_NAME));
            mf.write(out);
            out.closeEntry();
            metadataReader.close();
        }

        Files.write(cppDir.resolve("string_pool.cpp"), stringPool.build().getBytes(StandardCharsets.UTF_8));

        Files.write(cppDir.resolve("native_jvm_output.cpp"), mainSourceBuilder.build(nativeDir, currentClassId)
                .getBytes(StandardCharsets.UTF_8));

        Files.write(cppDir.resolve("CMakeLists.txt"), cMakeBuilder.build().getBytes(StandardCharsets.UTF_8));
    }

}
