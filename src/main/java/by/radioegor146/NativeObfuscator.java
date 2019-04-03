package by.radioegor146;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 *
 * @author radioegor146
 */

public class NativeObfuscator {
    
    private static final String[] CPP_TYPES = new String[] {
        "void", // 0
        "jboolean", // 1
        "jchar", // 2
        "jbyte", // 3
        "jshort", // 4
        "jint", // 5
        "jfloat", // 6
        "jlong", // 7
        "jdouble", // 8
        "jobject", // 9
        "jarray", // 10
        "jmethod" // 11
    };
    
    private static final Properties CPP_SNIPPETS = new Properties();
    
    private static String escapeString(String value) {
        return value.replace("\\", "\\\\").replace("\b", "\\b").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r").replace("\f", "\\f").replace("\"", "\\\"");
    }
    
    private static StringBuilder nativeMethodsSb = new StringBuilder();
    
    private static String visitMethod(ClassNode classNode, MethodNode methodNode, int index) {
        if (((methodNode.access & Opcodes.ACC_ABSTRACT) > 0) || ((methodNode.access & Opcodes.ACC_NATIVE) > 0))
            return "";
        if (methodNode.tryCatchBlocks.size() > 0)
            Type.getArgumentTypes(methodNode.desc);
        StringBuilder outputSb = new StringBuilder();
        outputSb.append("// ").append(methodNode.name).append(methodNode.desc).append("\n");
        String methodName = classNode.name + "/";
        switch (methodNode.name) {
            case "<init>":
                methodName += "native_special_init";
                break;
            case "<clinit>":
                methodName += "native_special_clinit";
                break;
            default:
                methodName += "native_" + methodNode.name;
                break;
        }
        methodName += index;
        methodName = "__NGEN_" + methodName.replace("/", "_");
        Matcher m = Pattern.compile("([^a-zA-Z_0-9])").matcher(methodName);
        StringBuffer sb = new StringBuffer(methodName.length());
        while (m.find()) 
            m.appendReplacement(sb, String.valueOf((int) m.group(1).charAt(0)));
        m.appendTail(sb);
        methodName = sb.toString();
        nativeMethodsSb
                .append("    { \"")
                .append(escapeString(methodNode.name))
                .append("\", ").append("\"")
                .append(escapeString(methodNode.desc))
                .append("\", ")
                .append("(void *)&")
                .append(methodName)
                .append(" },\n");
        int returnTypeSort = Type.getReturnType(methodNode.desc).getSort();
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
        Type[] args = Type.getArgumentTypes(methodNode.desc);
        if (args.length > 0)
            outputSb.append(", ");
        for (int i = 0; i < args.length; i++)
            outputSb.append(CPP_TYPES[args[i].getSort()]).append(" ").append("arg").append(i).append(i == args.length - 1 ? "" : ", ");
        outputSb.append(") {").append("\n");
        if (methodNode.maxStack > 0)
            outputSb.append("    ").append("jvm_stack<").append(methodNode.maxStack).append("> cstack;").append("\n");
        if (methodNode.maxLocals > 0)
            outputSb.append("    ").append("local_vars<").append(methodNode.maxLocals).append("> clocals;").append("\n").append("\n");
        int localIndex = 0;
        if (((methodNode.access & Opcodes.ACC_STATIC) == 0)) {
            outputSb.append("    ").append(CPP_SNIPPETS.getProperty("LOCAL_LOAD_ARG_" + 9).replace("$index", String.valueOf(localIndex)).replace("$arg", "obj")).append("\n");
            localIndex++;
        }   
        for (int i = 0; i < args.length; i++) {
            outputSb.append("    ").append(CPP_SNIPPETS.getProperty("LOCAL_LOAD_ARG_" + args[i].getSort()).replace("$index", String.valueOf(localIndex)).replace("$arg", "arg" + i)).append("\n");
            localIndex++;
        }
        outputSb.append("\n");
        HashSet<TryCatchBlockNode> currentTryCatches = new HashSet<>(); 
        for (int insnIndex = 0; insnIndex < methodNode.instructions.size(); insnIndex++) {
            AbstractInsnNode insnNode = methodNode.instructions.get(insnIndex);
            switch (insnNode.getType()) {
                case AbstractInsnNode.LABEL:
                    outputSb.append(((LabelNode)insnNode).getLabel()).append(": ;").append("\n");
                    methodNode.tryCatchBlocks.stream().filter((node) -> (node.start.equals(insnNode))).forEachOrdered((node) -> {
                        currentTryCatches.add(node);
                    });
                    methodNode.tryCatchBlocks.stream().filter((node) -> (node.end.equals(insnNode))).forEachOrdered((node) -> {
                        currentTryCatches.remove(node);
                    });
                    break;
                case AbstractInsnNode.LINE:
                    outputSb.append("    ").append("// Line ").append(((LineNumberNode)insnNode).line).append(":").append("\n");
                    break;
                case AbstractInsnNode.FRAME:
                    break;
                default:
                    StringBuilder tryCatch = new StringBuilder().append("\n");
                    tryCatch.append("    ").append(CPP_SNIPPETS.getProperty("TRYCATCH_START").replace("$rettype", CPP_TYPES[returnTypeSort]).replace("$handle", String.valueOf(currentTryCatches.size() > 0))).append("\n");
                    for (TryCatchBlockNode tryCatchBlock : currentTryCatches) {
                        if (tryCatchBlock.type == null) {
                            tryCatch.append("    ").append(CPP_SNIPPETS.getProperty("TRYCATCH_ANY_L")
                                .replace("$rettype", CPP_TYPES[returnTypeSort])
                                .replace("$handler_block", tryCatchBlock.handler.getLabel().toString())).append("\n");
                            break;
                        } else {
                            tryCatch.append("    ").append(CPP_SNIPPETS.getProperty("TRYCATCH_CHECK")
                                .replace("$rettype", CPP_TYPES[returnTypeSort])
                                .replace("$exception_class", escapeString(tryCatchBlock.type))
                                .replace("$handler_block", tryCatchBlock.handler.getLabel().toString())).append("\n");
                            break;
                        }
                    }
                    tryCatch.append("    ").append(CPP_SNIPPETS.getProperty("TRYCATCH_END").replace("$rettype", CPP_TYPES[returnTypeSort]));
                    outputSb.append("    ");
                    String insnName = INSTRUCTIONS.getOrDefault(insnNode.getOpcode(), "NOTFOUND");
                    HashMap<String, String> props = new HashMap<>();
                    props.put("trycatchhandler", tryCatch.toString());
                    props.put("rettype", CPP_TYPES[returnTypeSort]);
                    if (insnNode instanceof FieldInsnNode) {
                        insnName += "_" + Type.getType(((FieldInsnNode) insnNode).desc).getSort();
                        props.put("desc", escapeString(((FieldInsnNode) insnNode).desc));
                        props.put("name", escapeString(((FieldInsnNode) insnNode).name));
                        props.put("class", escapeString(((FieldInsnNode) insnNode).owner));
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
                        //throw new UnsupportedOperationException("insn not found: " + insnName + " " + insnNode);
                    }
                    if (insnNode instanceof JumpInsnNode) {
                        props.put("label", String.valueOf(((JumpInsnNode) insnNode).label.getLabel()));
                    }
                    if (insnNode instanceof LdcInsnNode) {
                        Object cst = ((LdcInsnNode) insnNode).cst;
                        props.put("cst", String.valueOf(((LdcInsnNode) insnNode).cst));
                        if (cst instanceof java.lang.String) {
                            insnName += "_STRING";
                            props.put("cst", escapeString(String.valueOf(((LdcInsnNode) insnNode).cst)));
                        } else if (cst instanceof java.lang.Integer) {
                            insnName += "_INT";
                        } else if (cst instanceof java.lang.Long) {
                            insnName += "_LONG";
                        } else if (cst instanceof java.lang.Float) {
                            insnName += "_FLOAT";
                        } else if (cst instanceof java.lang.Double) {
                            insnName += "_DOUBLE";
                        } else if (cst instanceof org.objectweb.asm.Type) {
                            insnName += "_CLASS";
                            props.put("cst", escapeString(String.valueOf(((LdcInsnNode) insnNode).cst)));
                        } else {
                            throw new UnsupportedOperationException();
                        }
                    }
                    if (insnNode instanceof LookupSwitchInsnNode) {
                        outputSb.append(CPP_SNIPPETS.getProperty("LOOKUPSWITCH_START")).append("\n");
                        for (int switchIndex = 0; switchIndex < ((LookupSwitchInsnNode) insnNode).labels.size(); switchIndex++)
                            outputSb.append("    ").append("    ").append(CPP_SNIPPETS.getProperty("LOOKUPSWITCH_PART")
                                    .replace("$key", String.valueOf(((LookupSwitchInsnNode) insnNode).keys.get(switchIndex)))
                                    .replace("$label", String.valueOf(((LookupSwitchInsnNode) insnNode).labels.get(switchIndex).getLabel()))).append("\n");
                        outputSb.append("    ").append(CPP_SNIPPETS.getProperty("LOOKUPSWITCH_END")).append("\n");
                        continue;
                    }
                    if (insnNode instanceof MethodInsnNode) {
                        Type returnType = Type.getReturnType(((MethodInsnNode) insnNode).desc);
                        Type[] argTypes = Type.getArgumentTypes(((MethodInsnNode) insnNode).desc);
                        insnName += "_" + returnType.getSort();
                        StringBuilder argsBuilder = new StringBuilder();
                        List<Integer> argOffsets = new ArrayList<>();
                        List<Integer> argSorts = new ArrayList<>();
                        int stackOffset = 0;
                        for (int argIndex = argTypes.length - 1; argIndex >= 0; argIndex--) {
                            int currentOffset = stackOffset + argTypes[argIndex].getSize() - 1;
                            stackOffset += argTypes[argIndex].getSize();
                            argOffsets.add(currentOffset);
                            argSorts.add(argTypes[argIndex].getSort());
                        }
                        Collections.reverse(argOffsets);
                        if (insnNode.getOpcode() == Opcodes.INVOKEINTERFACE || insnNode.getOpcode() == Opcodes.INVOKESPECIAL || insnNode.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                            int objectOffset = (argOffsets.size() > 0 ? argOffsets.get(argOffsets.size() - 1) : -1) + 1;
                            stackOffset++;
                            for (int i = 0; i < argOffsets.size(); i++)
                                argsBuilder.append(", ").append(CPP_SNIPPETS.getProperty("INVOKE_ARG_" + argSorts.get(i)).replace("$index", String.valueOf(argOffsets.get(i) - stackOffset)));
                            outputSb.append(CPP_SNIPPETS.getProperty("INVOKE_POPCNT").replace("$count", String.valueOf(stackOffset))).append(" ");
                            props.put("class", escapeString(((MethodInsnNode) insnNode).owner));
                            props.put("object_offset", String.valueOf(objectOffset - stackOffset));
                            props.put("name", escapeString(((MethodInsnNode) insnNode).name));
                            props.put("desc", escapeString(((MethodInsnNode) insnNode).desc));
                            props.put("args", argsBuilder.toString());
                        } else {
                            for (int i = 0; i < argOffsets.size(); i++)
                                argsBuilder.append(", ").append(CPP_SNIPPETS.getProperty("INVOKE_ARG_" + argSorts.get(i)).replace("$index", String.valueOf(argOffsets.get(i) - stackOffset)));
                            outputSb.append(CPP_SNIPPETS.getProperty("INVOKE_POPCNT").replace("$count", String.valueOf(stackOffset))).append(" ");
                            props.put("class", escapeString(((MethodInsnNode) insnNode).owner));
                            props.put("name", escapeString(((MethodInsnNode) insnNode).name));
                            props.put("desc", escapeString(((MethodInsnNode) insnNode).desc));
                            props.put("args", argsBuilder.toString());
                        }
                    }
                    if (insnNode instanceof MultiANewArrayInsnNode) {
                        props.put("count", String.valueOf(((MultiANewArrayInsnNode) insnNode).dims));
                        props.put("desc", escapeString(((MultiANewArrayInsnNode) insnNode).desc));
                    }
                    if (insnNode instanceof TableSwitchInsnNode) {
                        outputSb.append(CPP_SNIPPETS.getProperty("TABLESWITCH_START")).append("\n");
                        for (int switchIndex = 0; switchIndex < ((TableSwitchInsnNode) insnNode).labels.size(); switchIndex++)
                            outputSb.append("    ").append("    ").append(CPP_SNIPPETS.getProperty("TABLESWITCH_PART")
                                    .replace("$index", String.valueOf(((TableSwitchInsnNode) insnNode).min + switchIndex))
                                    .replace("$label", String.valueOf(((TableSwitchInsnNode) insnNode).labels.get(switchIndex).getLabel()))).append("\n");
                        outputSb.append("    ").append(CPP_SNIPPETS.getProperty("TABLESWITCH_END")).append("\n");
                        continue;
                    }
                    if (insnNode instanceof TypeInsnNode) {
                        props.put("desc", escapeString(((TypeInsnNode) insnNode).desc));
                    }
                    if (insnNode instanceof VarInsnNode) {
                        props.put("var", String.valueOf(((VarInsnNode) insnNode).var));
                    }
                    String cppCode = CPP_SNIPPETS.getProperty(insnName);
                    if (cppCode == null) {
                        outputSb.append("// ").append("insn not found: ").append(insnName).append(" ").append(insnNode).append("\n");
                    } else {
                        for (Map.Entry<String, String> prop : props.entrySet()) 
                            cppCode = cppCode.replace("$" + prop.getKey(), prop.getValue());
                        outputSb.append(cppCode);
                    }
                    outputSb.append("\n");
                    break;
            }
        }
        outputSb.append("    return (").append(CPP_TYPES[returnTypeSort]).append(") 0;").append("\n");
        outputSb.append("}").append("\n");
        return outputSb.toString();
    }
    
    private static final HashMap<Integer, String> INSTRUCTIONS = new HashMap<>();
    
    /**
     * @param args the command line arguments
     * @throws java.io.IOException
     * @throws java.lang.IllegalAccessException
     */
    public static void main(String[] args) throws IOException, IllegalArgumentException, IllegalAccessException {        
        for (Field f : Opcodes.class.getFields())
            INSTRUCTIONS.put((int) f.get(null), f.getName());
        CPP_SNIPPETS.load(NativeObfuscator.class.getClassLoader().getResourceAsStream("cppsnippets.properties"));
        byte[] bytes = Files.readAllBytes(Paths.get(args[0]));
        ClassReader classReader = new ClassReader(bytes);
        ClassNode classNode = new ClassNode(Opcodes.ASM7);
        classReader.accept(classNode, 0);
        StringBuilder outputFile = new StringBuilder();
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(NativeObfuscator.class.getClassLoader().getResourceAsStream("header.h"), "UTF-8"))) {	
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                outputFile.append(line).append("\n");
            }
	}
        outputFile.append("// ").append(classNode.name).append("\n");
        for (int i = 0; i < classNode.methods.size(); i++)
            outputFile.append(visitMethod(classNode, classNode.methods.get(i), i)).append("\n");
        outputFile.append("static JNINativeMethod __current_methods[] = {\n");
        outputFile.append(nativeMethodsSb);
        outputFile.append("};\n\n");
        outputFile.append("void RegisterCurrentNatives(JNIEnv *env) {\n");
        outputFile.append("    env->RegisterNatives(env->FindClass(\"").append(escapeString(classNode.name)).append("\"), __current_methods, sizeof(__current_methods) / sizeof(__current_methods[0]));\n");
        outputFile.append("}\n");
        Files.write(Paths.get(args[1]), outputFile.toString().getBytes());
    }
    
}
