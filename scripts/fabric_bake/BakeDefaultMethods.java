// minecraft-web by chiikabu (https://github.com/chiikabu)

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public final class BakeDefaultMethods {
    private static final class Info {
        String name, parent;
        List<String> interfaces = List.of();
        boolean isInterface;
        final Map<String, Boolean> methods = new LinkedHashMap<>();
        Map<String, String> impl;
        Set<String> visited;
    }

    private final Path target;
    private final List<ZipFile> lookup = new ArrayList<>();
    private final List<Path> lookupDirs = new ArrayList<>();
    private final Map<String, Info> infos = new HashMap<>();
    private final Set<String> missing = new HashSet<>();
    private final Map<String, ClassNode> patched = new TreeMap<>();
    private final Map<String, List<String>> bridges = new TreeMap<>();
    private final Set<String> done = new HashSet<>();
    private int unresolved;

    private BakeDefaultMethods(Path target, List<Path> lookupRoots) throws IOException {
        this.target = target;
        for (Path root : lookupRoots) {
            if (Files.isDirectory(root)) lookupDirs.add(root);
            else lookup.add(new ZipFile(root.toFile()));
        }
    }

    private byte[] bytes(String name) throws IOException {
        String entry = name + ".class";
        Path local = target.resolve(entry);
        if (Files.isRegularFile(local)) return Files.readAllBytes(local);
        for (Path dir : lookupDirs) {
            Path file = dir.resolve(entry);
            if (Files.isRegularFile(file)) return Files.readAllBytes(file);
        }
        for (ZipFile zip : lookup) {
            var found = zip.getEntry(entry);
            if (found != null) {
                try (InputStream in = zip.getInputStream(found)) { return in.readAllBytes(); }
            }
        }
        try (InputStream in = ClassLoader.getSystemResourceAsStream(entry)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private Info info(String name) {
        if (name == null || missing.contains(name)) return null;
        Info known = infos.get(name);
        if (known != null) return known;
        try {
            byte[] data = bytes(name);
            if (data == null) {
                missing.add(name);
                return null;
            }
            ClassNode node = new ClassNode();
            new ClassReader(data).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return infoOf(node);
        } catch (IOException | RuntimeException error) {
            missing.add(name);
            return null;
        }
    }

    private Info infoOf(ClassNode node) {
        Info info = new Info();
        info.name = node.name;
        info.parent = node.superName;
        info.interfaces = node.interfaces;
        info.isInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0
                    || method.name.startsWith("<")) continue;
            info.methods.put(method.name + method.desc, (method.access & Opcodes.ACC_ABSTRACT) != 0);
        }
        infos.put(node.name, info);
        return info;
    }

    private boolean model(Info cls) {
        if (cls.impl != null) return true;
        Info parent = cls.parent == null ? null : info(cls.parent);
        if (cls.parent != null && (parent == null || !model(parent))) return false;
        Map<String, String> impl = new HashMap<>();
        Set<String> visited = new HashSet<>();
        if (parent != null) {
            impl.putAll(parent.impl);
            visited.addAll(parent.visited);
        }
        for (var method : cls.methods.entrySet()) {
            if (!method.getValue()) impl.put(method.getKey(), cls.name);
        }
        Map<String, String> fromInterfaces = new LinkedHashMap<>();
        for (String itf : cls.interfaces) {
            if (!fill(itf, impl, visited, fromInterfaces)) return false;
        }
        impl.putAll(fromInterfaces);
        cls.impl = impl;
        cls.visited = visited;
        return true;
    }

    private boolean fill(String name, Map<String, String> impl, Set<String> visited, Map<String, String> result) {
        if (!visited.add(name)) return true;
        Info itf = info(name);
        if (itf == null) return false;
        for (String parent : itf.interfaces) {
            if (!fill(parent, impl, visited, result)) return false;
        }
        for (var method : itf.methods.entrySet()) {
            if (!method.getValue() && impl.get(method.getKey()) == null) result.put(method.getKey(), name);
        }
        return true;
    }

    private boolean isSubInterface(String sub, String sup) {
        if (sub.equals(sup)) return true;
        Info info = info(sub);
        if (info == null) return false;
        for (String parent : info.interfaces) {
            if (isSubInterface(parent, sup)) return true;
        }
        return false;
    }

    private void collectInterfaces(String name, Set<String> into) {
        Info info = info(name);
        if (info == null || !into.add(name)) return;
        for (String parent : info.interfaces) collectInterfaces(parent, into);
    }

    private String jvmChoice(Info cls, String key) {
        for (Info c = cls; c != null; c = info(c.parent)) {
            if (!c.isInterface) {
                Boolean isAbstract = c.methods.get(key);
                if (isAbstract != null) return isAbstract ? null : c.name;
            }
        }
        Set<String> all = new HashSet<>();
        for (Info c = cls; c != null; c = info(c.parent)) {
            for (String itf : c.interfaces) collectInterfaces(itf, all);
        }
        List<String> owners = new ArrayList<>();
        for (String itf : all) {
            if (info(itf).methods.containsKey(key)) owners.add(itf);
        }
        owners.removeIf(owner -> owners.stream().anyMatch(other -> !other.equals(owner) && isSubInterface(other, owner)));
        if (owners.size() != 1 || info(owners.get(0)).methods.get(key)) return null;
        return owners.get(0);
    }

    private static boolean jdk(String name) {
        return name.startsWith("java/") || name.startsWith("javax/") || name.startsWith("jdk/") || name.startsWith("sun/");
    }

    private void process(String name) throws IOException {
        if (!done.add(name)) return;
        Info cls = info(name);
        if (cls == null || cls.isInterface) return;
        if (cls.parent != null && Files.isRegularFile(target.resolve(cls.parent + ".class"))) process(cls.parent);
        if (!model(cls)) return;
        Set<String> all = new HashSet<>();
        for (Info c = cls; c != null; c = info(c.parent)) {
            for (String itf : c.interfaces) collectInterfaces(itf, all);
        }
        Set<String> keys = new java.util.TreeSet<>();
        for (String itf : all) {
            for (var method : info(itf).methods.entrySet()) {
                if (!method.getValue()) keys.add(method.getKey());
            }
        }
        for (String key : keys) {
            String wanted = jvmChoice(cls, key);
            if (wanted == null || jdk(wanted) || !info(wanted).isInterface || wanted.equals(cls.impl.get(key))) continue;
            String via = null;
            for (String direct : cls.interfaces) {
                if (isSubInterface(direct, wanted)) {
                    via = direct;
                    break;
                }
            }
            if (via == null) {
                unresolved++;
                System.out.println("UNBRIDGED " + name + "." + key + " wants " + wanted + " but TeaVM links " + cls.impl.get(key));
                continue;
            }
            ClassNode node = patched.get(name);
            if (node == null) {
                node = new ClassNode();
                new ClassReader(bytes(name)).accept(node, 0);
                patched.put(name, node);
            }
            int paren = key.indexOf('(');
            String method = key.substring(0, paren), desc = key.substring(paren);
            MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, method, desc, null, null);
            bridge.visitVarInsn(Opcodes.ALOAD, 0);
            int slot = 1;
            for (Type argument : Type.getArgumentTypes(desc)) {
                bridge.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot);
                slot += argument.getSize();
            }
            bridge.visitMethodInsn(Opcodes.INVOKESPECIAL, via, method, desc, true);
            Type result = Type.getReturnType(desc);
            bridge.visitInsn(result.getOpcode(Opcodes.IRETURN));
            bridge.visitMaxs(Math.max(slot, result.getSize()), slot);
            node.methods.add(bridge);
            bridges.computeIfAbsent(name, n -> new ArrayList<>()).add(key + " -> " + via + " (" + wanted
                + " instead of " + cls.impl.get(key) + ")");
            cls.methods.put(key, false);
            cls.impl.put(key, name);
        }
    }

    public static void main(String[] args) throws IOException {
        boolean report = args.length > 0 && args[0].equals("--report");
        int first = report ? 1 : 0;
        if (args.length <= first) throw new IllegalArgumentException("usage: BakeDefaultMethods [--report] <classes dir> [lookup jar or dir...]");
        Path target = Path.of(args[first]);
        List<Path> lookup = new ArrayList<>();
        for (int i = first + 1; i < args.length; i++) lookup.add(Path.of(args[i]));
        var tool = new BakeDefaultMethods(target, lookup);
        List<String> names = new ArrayList<>();
        try (var paths = Files.walk(target)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Symlink class input: " + path);
                String relative = target.relativize(path).toString().replace('\\', '/');
                names.add(relative.substring(0, relative.length() - ".class".length()));
            }
        }
        for (String name : names) tool.process(name);
        int methods = 0;
        for (var entry : tool.bridges.entrySet()) {
            for (String line : entry.getValue()) System.out.println("BRIDGE " + entry.getKey() + "." + line);
            methods += entry.getValue().size();
        }
        if (!report) {
            for (var entry : tool.patched.entrySet()) {
                ClassWriter writer = new ClassWriter(0);
                entry.getValue().accept(writer);
                Files.write(target.resolve(entry.getKey() + ".class"), writer.toByteArray());
            }
        }
        System.out.println("DEFAULT_METHODS " + (report ? "found " : "bridged ") + methods + " in " + tool.bridges.size()
            + " of " + names.size() + " classes, " + tool.unresolved + " unbridged");
    }
}
