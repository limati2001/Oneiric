package org.mcphackers.mcp.tools.injector;

import org.mcphackers.rdi.injector.data.ClassStorage;
import org.mcphackers.rdi.injector.data.Mappings;
import org.mcphackers.rdi.injector.transform.Injection;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class StringLiteralRemapper implements Injection {

    private final Mappings mappings;
    private final String[] packagePrefixes;

    private ClassStorage storage;

    private Map<String, String> uniqueMethodNameMap;
    private Map<String, String> uniqueFieldNameMap;

    private Map<String, String> nameDescToObfMethodMap;
    private Map<String, String> nameDescToObfFieldMap;

    public StringLiteralRemapper(Mappings mappings, String[] packagePrefixes) {
        this.mappings = mappings;
        this.packagePrefixes = normalize(packagePrefixes);
    }

    private static String[] normalize(String[] raw) {
        if (raw == null) return new String[0];
        java.util.List<String> out = new java.util.ArrayList<>(raw.length);
        for (String s : raw) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isEmpty()) continue;
            int eq = t.indexOf('=');
            if (eq >= 0) t = t.substring(eq + 1).trim();
            if (t.isEmpty()) continue;
            t = t.replace('.', '/');
            if (!t.endsWith("/")) t = t + "/";
            out.add(t);
        }
        return out.toArray(new String[0]);
    }

    @Override
    public void transform(ClassStorage storage) {
        if (packagePrefixes.length == 0) return;
        if (mappings == null || mappings.classes == null || mappings.classes.isEmpty()) {

            return;
        }

        int totalClasses = storage.getClasses().size();
        StringBuilder prefixes = new StringBuilder();
        for (int i = 0; i < packagePrefixes.length; i++) {
            if (i > 0) prefixes.append(", ");
            prefixes.append(packagePrefixes[i]);
        }
        System.out.println("[stringremap] " + totalClasses + " class(es) in storage; "
                + "prefix(es): " + prefixes);

        this.storage = storage;
        this.uniqueMethodNameMap = buildUniqueNameIndex(true);
        this.uniqueFieldNameMap  = buildUniqueNameIndex(false);
        this.nameDescToObfMethodMap = buildNameDescIndex(true);
        this.nameDescToObfFieldMap  = buildNameDescIndex(false);
        try {
            int classesProcessed = 0;
            int rewrites = 0;
            for (ClassNode cls : storage.getClasses()) {
                if (!isTarget(cls.name)) continue;
                classesProcessed++;
                if (cls.methods == null) continue;
                for (MethodNode m : cls.methods) {

                    rewrites += rewriteAsmNodeConstructors(m);
                    rewrites += rewriteReflectionLookups(m);
                    rewrites += rewriteAsmNodeNameChecks(m);

                    rewrites += rewriteMethod(m);
                }
            }

            System.out.println("[stringremap] processed " + classesProcessed
                    + " class(es), " + rewrites + " string literal(s) rewritten");
        } finally {
            this.storage = null;
            this.uniqueMethodNameMap = null;
            this.uniqueFieldNameMap = null;
            this.nameDescToObfMethodMap = null;
            this.nameDescToObfFieldMap = null;
        }
    }

    private Map<String, String> buildNameDescIndex(boolean methods) {
        Map<String, String> result = new HashMap<>();
        Set<String> conflicts = new HashSet<>();
        for (ClassNode cls : storage.getClasses()) {
            if (methods) {
                if (cls.methods == null) continue;
                for (MethodNode m : cls.methods) {
                    if ("<init>".equals(m.name) || "<clinit>".equals(m.name)) continue;
                    String obf = mappings.methods.optGet(cls.name, m.desc, m.name);
                    if (obf == null || obf.equals(m.name)) continue;
                    indexUnique(result, conflicts, m.name + "|" + m.desc, obf);
                }
            } else {
                if (cls.fields == null) continue;
                for (FieldNode f : cls.fields) {
                    String obf = mappings.fields.optGet(cls.name, f.desc, f.name);
                    if (obf == null || obf.equals(f.name)) continue;
                    indexUnique(result, conflicts, f.name + "|" + f.desc, obf);
                }
            }
        }
        return result;
    }

    private Map<String, String> buildUniqueNameIndex(boolean methods) {
        Map<String, String> result = new HashMap<>();
        Set<String> conflicts = new HashSet<>();
        for (ClassNode cls : storage.getClasses()) {
            if (methods) {
                if (cls.methods == null) continue;
                for (MethodNode m : cls.methods) {
                    if ("<init>".equals(m.name) || "<clinit>".equals(m.name)) continue;
                    String obf = mappings.methods.optGet(cls.name, m.desc, m.name);
                    if (obf == null || obf.equals(m.name)) continue;
                    indexUnique(result, conflicts, m.name, obf);
                }
            } else {
                if (cls.fields == null) continue;
                for (FieldNode f : cls.fields) {
                    String obf = mappings.fields.optGet(cls.name, f.desc, f.name);
                    if (obf == null || obf.equals(f.name)) continue;
                    indexUnique(result, conflicts, f.name, obf);
                }
            }
        }
        return result;
    }

    private static void indexUnique(Map<String, String> result, Set<String> conflicts,
                                     String deobf, String obf) {
        if (conflicts.contains(deobf)) return;
        String existing = result.get(deobf);
        if (existing == null) {
            result.put(deobf, obf);
        } else if (!existing.equals(obf)) {

            result.remove(deobf);
            conflicts.add(deobf);
        }
    }

    private int rewriteMethod(MethodNode method) {
        if (method.instructions == null || method.instructions.size() == 0) return 0;

        int changed = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof LdcInsnNode)) continue;
            LdcInsnNode ldc = (LdcInsnNode) insn;
            if (!(ldc.cst instanceof String)) continue;

            String original = (String) ldc.cst;
            String rewritten = remapString(original);
            if (rewritten != null && !rewritten.equals(original)) {
                ldc.cst = rewritten;
                changed++;
            }
        }
        return changed;
    }

    private String remapString(String s) {
        if (s == null || s.isEmpty()) return null;

        char first = s.charAt(0);
        if (first == '(' || first == '[' || (first == 'L' && s.endsWith(";"))) {
            String d = remapDescriptor(s);
            if (d != null && !d.equals(s)) return d;

            return null;
        }

        String slashHit = mappings.classes.get(s);
        if (slashHit != null) return slashHit;

        if (s.indexOf('.') >= 0 && s.indexOf('/') < 0) {
            String slashed = s.replace('.', '/');
            String dottedHit = mappings.classes.get(slashed);
            if (dottedHit != null) return dottedHit.replace('/', '.');
        }

        return null;
    }

    private static final String FIELD_INSN_NODE  = "org/objectweb/asm/tree/FieldInsnNode";
    private static final String METHOD_INSN_NODE = "org/objectweb/asm/tree/MethodInsnNode";

    private static final String CTOR_DESC_3STR =
            "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";

    private static final String CTOR_DESC_3STR_BOOL =
            "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V";

    private int rewriteAsmNodeConstructors(MethodNode method) {
        if (method.instructions == null || method.instructions.size() == 0) return 0;
        int rewrites = 0;

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.INVOKESPECIAL) continue;
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if (!"<init>".equals(call.name)) continue;

            boolean isField  = FIELD_INSN_NODE.equals(call.owner)
                    && CTOR_DESC_3STR.equals(call.desc);
            boolean isMethod = METHOD_INSN_NODE.equals(call.owner)
                    && (CTOR_DESC_3STR.equals(call.desc) || CTOR_DESC_3STR_BOOL.equals(call.desc));
            if (!isField && !isMethod) continue;

            AbstractInsnNode cursor = previousReal(insn);
            if (isMethod && CTOR_DESC_3STR_BOOL.equals(call.desc)) {
                cursor = previousReal(cursor);
            }
            LdcInsnNode descLdc  = asStringLdc(cursor);
            LdcInsnNode nameLdc  = asStringLdc(descLdc  != null ? previousReal(descLdc)  : null);
            LdcInsnNode ownerLdc = asStringLdc(nameLdc  != null ? previousReal(nameLdc)  : null);
            if (descLdc == null || nameLdc == null || ownerLdc == null) continue;

            String owner = (String) ownerLdc.cst;
            String name  = (String) nameLdc.cst;
            String desc  = (String) descLdc.cst;

            String mappedOwner = mappings.classes.get(owner);
            if (mappedOwner == null) continue;

            String mappedName = isField
                    ? mappings.fields.optGet(owner, desc, name)
                    : mappings.methods.optGet(owner, desc, name);
            String mappedDesc = remapDescriptor(desc);

            ownerLdc.cst = mappedOwner;
            rewrites++;
            if (mappedName != null && !mappedName.equals(name)) {
                nameLdc.cst = mappedName;
                rewrites++;
            }
            if (mappedDesc != null && !mappedDesc.equals(desc)) {
                descLdc.cst = mappedDesc;
                rewrites++;
            }
        }
        return rewrites;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        if (insn == null) return null;
        AbstractInsnNode prev = insn.getPrevious();
        while (prev != null && prev.getOpcode() < 0) {
            prev = prev.getPrevious();
        }
        return prev;
    }

    private static LdcInsnNode asStringLdc(AbstractInsnNode insn) {
        if (!(insn instanceof LdcInsnNode)) return null;
        LdcInsnNode ldc = (LdcInsnNode) insn;
        return ldc.cst instanceof String ? ldc : null;
    }

    private static Type asTypeLdc(AbstractInsnNode insn) {
        if (!(insn instanceof LdcInsnNode)) return null;
        LdcInsnNode ldc = (LdcInsnNode) insn;
        return ldc.cst instanceof Type ? (Type) ldc.cst : null;
    }

    private static final String FIELD_LOOKUP_RET = ")Ljava/lang/reflect/Field;";

    private int rewriteReflectionLookups(MethodNode method) {
        if (method.instructions == null || method.instructions.size() == 0) return 0;
        int rewrites = 0;

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if (!isFieldLookup(call)) continue;

            LdcInsnNode nameLdc = asStringLdc(previousReal(call));
            if (nameLdc == null) continue;

            String owner = resolveClassRef(previousReal(nameLdc));
            if (owner == null) continue;

            if (!mappings.classes.containsKey(owner)) continue;

            String name = (String) nameLdc.cst;
            String desc = findFieldDesc(owner, name);
            if (desc == null) continue;

            String mapped = mappings.fields.optGet(owner, desc, name);
            if (mapped != null && !mapped.equals(name)) {
                nameLdc.cst = mapped;
                rewrites++;
            }
        }
        return rewrites;
    }

    private static boolean isFieldLookup(MethodInsnNode call) {
        if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && "java/lang/Class".equals(call.owner)
                && ("getDeclaredField".equals(call.name) || "getField".equals(call.name))
                && "(Ljava/lang/String;)Ljava/lang/reflect/Field;".equals(call.desc)) {
            return true;
        }
        return call.desc != null
                && call.desc.startsWith("(Ljava/lang/Class;Ljava/lang/String;)")
                && call.desc.endsWith(FIELD_LOOKUP_RET);
    }

    private String resolveClassRef(AbstractInsnNode insn) {
        if (insn == null) return null;
        Type direct = asTypeLdc(insn);
        if (direct != null) return direct.getInternalName();

        if (insn instanceof VarInsnNode && insn.getOpcode() == Opcodes.ALOAD) {
            int slot = ((VarInsnNode) insn).var;
            for (AbstractInsnNode prev = previousReal(insn); prev != null; prev = previousReal(prev)) {
                if (prev instanceof VarInsnNode
                        && prev.getOpcode() == Opcodes.ASTORE
                        && ((VarInsnNode) prev).var == slot) {
                    Type stored = asTypeLdc(previousReal(prev));
                    return stored != null ? stored.getInternalName() : null;
                }
            }
        }
        return null;
    }

    private String findFieldDesc(String ownerInternalName, String fieldName) {
        if (storage == null) return null;
        String current = ownerInternalName;

        while (current != null) {
            ClassNode cls = storage.getClass(current);
            if (cls == null) break;
            if (cls.fields != null) {
                for (FieldNode f : cls.fields) {
                    if (fieldName.equals(f.name)) return f.desc;
                }
            }
            current = cls.superName;

            if (current == null || "java/lang/Object".equals(current)) break;
        }
        return null;
    }

    private static final String ASM_TREE_PKG = "org/objectweb/asm/tree/";
    private static final String STRING_OWNER = "java/lang/String";
    private static final String STRING_EQUALS_DESC = "(Ljava/lang/Object;)Z";
    private static final String STRING_EQUALS_IGNORE_CASE_DESC = "(Ljava/lang/String;)Z";

    private int rewriteAsmNodeNameChecks(MethodNode method) {
        if (method.instructions == null || method.instructions.size() == 0) return 0;
        int rewrites = 0;

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (isStringEqualsCall(insn)) {
                rewrites += rewriteAsmNodeNameCheck(insn);
            }
        }
        return rewrites;
    }

    private int rewriteAsmNodeNameCheck(AbstractInsnNode insn) {
        LdcInsnNode nameLdc = asStringLdc(previousReal(insn));
        if (nameLdc == null) return 0;
        AbstractInsnNode getField = previousReal(nameLdc);
        Boolean isMethodName = isAsmTreeNameGetField(getField);
        if (isMethodName == null) return 0;

        String origName = (String) nameLdc.cst;

        LdcInsnNode descLdc = findFollowingDescCheck(insn, isMethodName);
        if (descLdc != null) {
            String key = origName + "|" + descLdc.cst;
            Map<String, String> combined = isMethodName ? nameDescToObfMethodMap : nameDescToObfFieldMap;
            String obf = combined.get(key);
            if (obf != null && !obf.equals(origName)) {
                nameLdc.cst = obf;
                return 1;
            }
        }

        Map<String, String> index = isMethodName ? uniqueMethodNameMap : uniqueFieldNameMap;
        String mapped = index.get(origName);
        if (mapped == null || mapped.equals(origName)) return 0;
        nameLdc.cst = mapped;
        return 1;
    }

    private static boolean isStringEqualsCall(AbstractInsnNode insn) {
        if (!(insn instanceof MethodInsnNode)) return false;
        MethodInsnNode call = (MethodInsnNode) insn;
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && STRING_OWNER.equals(call.owner)
                && (("equals".equals(call.name) && STRING_EQUALS_DESC.equals(call.desc))
                        || ("equalsIgnoreCase".equals(call.name) && STRING_EQUALS_IGNORE_CASE_DESC.equals(call.desc)));
    }

    private LdcInsnNode findFollowingDescCheck(AbstractInsnNode start, boolean methodNameCheck) {
        int hops = 0;
        for (AbstractInsnNode cur = start.getNext(); cur != null && hops < 30; cur = cur.getNext()) {
            int op = cur.getOpcode();
            if (op == Opcodes.IRETURN || op == Opcodes.RETURN || op == Opcodes.ATHROW) return null;
            if (op < 0) continue;
            hops++;

            if (isStringEqualsCall(cur)) {
                LdcInsnNode candidate = asStringLdc(previousReal(cur));
                if (candidate == null) continue;
                AbstractInsnNode getField = previousReal(candidate);
                if (!isAsmTreeDescGetField(getField, methodNameCheck)) continue;

                return candidate;
            }
        }
        return null;
    }

    private static boolean isAsmTreeDescGetField(AbstractInsnNode insn, boolean methodContext) {
        if (!(insn instanceof FieldInsnNode)) return false;
        FieldInsnNode get = (FieldInsnNode) insn;
        if (get.getOpcode() != Opcodes.GETFIELD) return false;
        if (!"desc".equals(get.name)) return false;
        if (!"Ljava/lang/String;".equals(get.desc)) return false;
        if (get.owner == null || !get.owner.startsWith(ASM_TREE_PKG)) return false;

        String simpleName = get.owner.substring(ASM_TREE_PKG.length());
        if (methodContext) {
            return "MethodNode".equals(simpleName) || "MethodInsnNode".equals(simpleName);
        }
        return "FieldNode".equals(simpleName) || "FieldInsnNode".equals(simpleName);
    }

    private static Boolean isAsmTreeNameGetField(AbstractInsnNode insn) {
        if (!(insn instanceof FieldInsnNode)) return null;
        FieldInsnNode get = (FieldInsnNode) insn;
        if (get.getOpcode() != Opcodes.GETFIELD) return null;
        if (!"name".equals(get.name)) return null;
        if (!"Ljava/lang/String;".equals(get.desc)) return null;
        if (get.owner == null || !get.owner.startsWith(ASM_TREE_PKG)) return null;

        String simpleName = get.owner.substring(ASM_TREE_PKG.length());
        if ("FieldNode".equals(simpleName) || "FieldInsnNode".equals(simpleName)) {
            return Boolean.FALSE;
        }

        return Boolean.TRUE;
    }

    private String remapDescriptor(String desc) {
        StringBuilder out = new StringBuilder(desc.length());
        int i = 0;
        int n = desc.length();
        while (i < n) {
            char c = desc.charAt(i);

            if (c != 'L') {
                out.append(c);
                i++;
                continue;
            }
            int semi = desc.indexOf(';', i + 1);
            if (semi < 0) return null;
            String cls = desc.substring(i + 1, semi);
            String mapped = mappings.classes.get(cls);
            out.append('L').append(mapped != null ? mapped : cls).append(';');
            i = semi + 1;
        }
        return out.toString();
    }

    private boolean isTarget(String internalName) {
        if (internalName == null) return false;
        for (String prefix : packagePrefixes) {
            if (internalName.startsWith(prefix)) return true;
        }
        return false;
    }
}
