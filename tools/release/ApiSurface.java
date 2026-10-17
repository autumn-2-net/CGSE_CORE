import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/** Binary API inventory without initializing any engine class. Run with JDK 17. */
class ApiSurface {
    private static final int FLAGS = Modifier.PUBLIC | Modifier.PROTECTED | Modifier.STATIC
            | Modifier.FINAL | Modifier.ABSTRACT | Modifier.INTERFACE;

    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        var lines = new TreeSet<String>();
        try (var archive = new JarFile(jar.toFile());
             var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            for (var entry : Collections.list(archive.entries())) {
                if (!entry.getName().endsWith(".class")) continue;
                String name = entry.getName().replace('/', '.').replaceAll("\\.class$", "");
                Class<?> type = Class.forName(name, false, loader);
                if (!exported(type)) continue;
                lines.add("T " + name + " " + (type.getModifiers() & FLAGS) + " "
                        + (type.getSuperclass() == null ? "-" : type.getSuperclass().getName()) + " "
                        + Arrays.stream(type.getInterfaces()).map(Class::getName).sorted().collect(Collectors.joining(",")));
                for (var field : type.getDeclaredFields()) if (visible(field.getModifiers()))
                    lines.add("F " + name + " " + field.getName() + ":" + field.getType().getName() + " " + (field.getModifiers() & FLAGS));
                for (var method : type.getDeclaredMethods()) if (visible(method.getModifiers()))
                    lines.add("M " + name + " " + method.getName() + signature(method.getParameterTypes())
                            + ":" + method.getReturnType().getName() + " " + (method.getModifiers() & FLAGS));
                for (var ctor : type.getDeclaredConstructors()) if (visible(ctor.getModifiers()))
                    lines.add("C " + name + " " + signature(ctor.getParameterTypes()) + " " + (ctor.getModifiers() & FLAGS));
            }
        }
        Files.write(Path.of(args[1]), lines.stream().map(String::stripTrailing).toList(), StandardCharsets.UTF_8);
    }

    private static boolean visible(int flags) {
        return Modifier.isPublic(flags) || Modifier.isProtected(flags);
    }

    private static boolean exported(Class<?> type) {
        return visible(type.getModifiers()) && (type.getEnclosingClass() == null || exported(type.getEnclosingClass()));
    }

    private static String signature(Class<?>[] parameters) {
        return "(" + Arrays.stream(parameters).map(Class::getName).collect(Collectors.joining(",")) + ")";
    }
}
