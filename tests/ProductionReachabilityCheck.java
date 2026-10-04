import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * Compiler-bound production audit. Tests cannot keep an unused App API alive.
 * Run after a real Android build, with its complete javac/bootstrap/R classpath:
 * java tests/ProductionReachabilityCheck.java PROJECT_ROOT CLASSPATH_FILE
 *
 * Finds unreferenced declarations, exact unused constructor overloads, fields
 * whose values are never read, and unused imports. Android callbacks and XML
 * entry points are recognized by their actual contracts, without name-based
 * exemptions for arbitrary public methods or an allowlist of dead members.
 */
public final class ProductionReachabilityCheck {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    private static final class XmlEntries {
        final Set<String> components = new HashSet<>();
        final Set<String> views = new HashSet<>();
        final Set<String> clicks = new HashSet<>();
    }

    private static final class Imported {
        final String name, position;
        final boolean member, wildcard;
        final TypeElement staticOwner;
        boolean used, duplicate;
        Imported(ImportTree tree, String position, Elements elements) {
            name = tree.getQualifiedIdentifier().toString();
            member = tree.isStatic(); wildcard = name.endsWith(".*");
            this.position = position;
            staticOwner = member ? elements.getTypeElement(name.substring(0, name.lastIndexOf('.'))) : null;
        }
        void reference(Element element, Elements elements) {
            if (member && (element.getKind() == ElementKind.FIELD || element.getKind() == ElementKind.METHOD
                    || element.getKind() == ElementKind.ENUM_CONSTANT || element instanceof TypeElement)) {
                Element owner = element.getEnclosingElement();
                if (owner instanceof TypeElement) {
                    String prefix = ((TypeElement) owner).getQualifiedName().toString();
                    used |= wildcard ? name.equals(prefix + ".*") : name.equals(prefix + "." + element.getSimpleName());
                }
                if (!used && staticOwner != null && (wildcard || name.endsWith("." + element.getSimpleName()))) {
                    for (Element inherited : elements.getAllMembers(staticOwner)) if (inherited.equals(element)) { used = true; break; }
                }
            } else if (!member && element instanceof TypeElement) {
                TypeElement type = (TypeElement) element;
                if (!wildcard) used |= name.equals(type.getQualifiedName().toString());
                else {
                    Element owner = type.getEnclosingElement();
                    String prefix = owner instanceof TypeElement ? ((TypeElement) owner).getQualifiedName().toString()
                            : elements.getPackageOf(type).getQualifiedName().toString();
                    used |= name.equals(prefix + ".*");
                }
            }
        }
    }

    private static final class Audit {
        final Path root;
        final Trees trees;
        final Types types;
        final Elements elements;
        final XmlEntries xml;
        final Map<Element, String> declarations = new LinkedHashMap<>();
        final Map<Element, Integer> reads = new HashMap<>(), writes = new HashMap<>();
        final Set<Element> contracts = new HashSet<>();
        final List<Imported> imports = new ArrayList<>();

        Audit(Path root, JavacTask task, XmlEntries xml) {
            this.root = root; trees = Trees.instance(task); types = task.getTypes(); elements = task.getElements(); this.xml = xml;
        }

        void scan(CompilationUnitTree unit) {
            List<Imported> localImports = new ArrayList<>();
            new TreePathScanner<Void, Void>() {
                String position(Tree node) {
                    long start = trees.getSourcePositions().getStartPosition(unit, node);
                    URI uri = unit.getSourceFile().toUri();
                    String source = uri.getScheme().equals("file") ? root.relativize(Paths.get(uri)).toString() : unit.getSourceFile().getName();
                    return source + ":" + unit.getLineMap().getLineNumber(start);
                }

                boolean explicit(Tree node) {
                    return trees.getSourcePositions().getStartPosition(unit, node) >= 0
                            && trees.getSourcePositions().getEndPosition(unit, node) >= 0;
                }

                @Override public Void visitImport(ImportTree node, Void unused) {
                    Imported imported = new Imported(node, position(node), elements);
                    imported.duplicate = localImports.stream().anyMatch(previous -> previous.name.equals(imported.name) && previous.member == imported.member);
                    localImports.add(imported); imports.add(imported);
                    return null; // An import is not a running reference to its target.
                }

                @Override public Void visitClass(ClassTree node, Void unused) {
                    Element element = trees.getElement(getCurrentPath());
                    if (element instanceof TypeElement && node.getSimpleName().length() > 0 && explicit(node)) {
                        TypeElement type = (TypeElement) element;
                        declarations.put(element, position(node));
                        if (xml.components.contains(binaryName(type)) || xml.views.contains(binaryName(type))) contracts.add(type);
                    }
                    return super.visitClass(node, unused);
                }

                @Override public Void visitMethod(MethodTree node, Void unused) {
                    Element element = trees.getElement(getCurrentPath());
                    if (element instanceof ExecutableElement && explicit(node)) {
                        ExecutableElement method = (ExecutableElement) element;
                        TypeElement owner = (TypeElement) method.getEnclosingElement();
                        declarations.put(element, position(node));
                        if (method.getKind() == ElementKind.METHOD) {
                            if (owner.getKind().isInterface() || overrides(method, owner, types, elements)
                                    || xmlClick(method)) contracts.add(method);
                        } else if (method.getKind() == ElementKind.CONSTRUCTOR
                                && (utilityConstructor(method, owner) || xmlConstructor(method, owner))) contracts.add(method);
                    }
                    // Javac inserts real implicit super() calls; scan those even
                    // when their generated constructor is not a source declaration.
                    return super.visitMethod(node, unused);
                }

                @Override public Void visitVariable(VariableTree node, Void unused) {
                    Element element = trees.getElement(getCurrentPath());
                    if (element != null && element.getKind() == ElementKind.FIELD && explicit(node)) {
                        declarations.put(element, position(node));
                        if (node.getInitializer() != null) increment(writes, element);
                    }
                    return super.visitVariable(node, unused);
                }

                void reference(Tree node, boolean shortName) {
                    Element element = trees.getElement(getCurrentPath());
                    if (element == null) return;
                    if (shortName) for (Imported imported : localImports) imported.reference(element, elements);
                    TreePath parentPath = getCurrentPath().getParentPath();
                    Tree parent = parentPath == null ? null : parentPath.getLeaf();
                    boolean write = parent instanceof AssignmentTree && ((AssignmentTree) parent).getVariable() == node;
                    boolean selfUpdate = parent instanceof CompoundAssignmentTree
                            && ((CompoundAssignmentTree) parent).getVariable() == node;
                    if (parent instanceof UnaryTree) {
                        Tree.Kind kind = parent.getKind();
                        selfUpdate |= kind == Tree.Kind.PREFIX_INCREMENT || kind == Tree.Kind.POSTFIX_INCREMENT
                                || kind == Tree.Kind.PREFIX_DECREMENT || kind == Tree.Kind.POSTFIX_DECREMENT;
                    }
                    if (write || selfUpdate) increment(writes, element);
                    // A standalone field++/field+=x only updates discarded state.
                    boolean updateResultUsed = selfUpdate && parentPath.getParentPath() != null
                            && parentPath.getParentPath().getLeaf().getKind() != Tree.Kind.EXPRESSION_STATEMENT;
                    if (!(element instanceof TypeElement) && !write && (!selfUpdate || updateResultUsed)) increment(reads, element);
                    TypeElement source = enclosingType(getCurrentPath());
                    if (element instanceof TypeElement) readType((TypeElement) element, source);
                    else if (element.getEnclosingElement() instanceof TypeElement) readType((TypeElement) element.getEnclosingElement(), source);
                }

                @Override public Void visitIdentifier(IdentifierTree node, Void unused) {
                    reference(node, true); return super.visitIdentifier(node, unused);
                }
                @Override public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                    reference(node, false); return super.visitMemberSelect(node, unused);
                }
                @Override public Void visitMemberReference(MemberReferenceTree node, Void unused) {
                    reference(node, false); return super.visitMemberReference(node, unused);
                }
                @Override public Void visitNewClass(NewClassTree node, Void unused) {
                    Element constructor = trees.getElement(getCurrentPath());
                    if (constructor != null) {
                        increment(reads, constructor);
                        if (constructor.getEnclosingElement() instanceof TypeElement)
                            readType((TypeElement) constructor.getEnclosingElement(), enclosingType(getCurrentPath()));
                    }
                    return super.visitNewClass(node, unused);
                }
            }.scan(unit, null);
        }

        TypeElement enclosingType(TreePath path) {
            for (TreePath current = path; current != null; current = current.getParentPath()) if (current.getLeaf() instanceof ClassTree) {
                Element element = trees.getElement(current);
                if (element instanceof TypeElement) return (TypeElement) element;
            }
            return null;
        }

        void readType(TypeElement type, TypeElement source) {
            Set<Element> lexicalOwners = new HashSet<>();
            for (Element owner = source; owner != null; owner = owner.getEnclosingElement())
                if (owner instanceof TypeElement) lexicalOwners.add(owner);
            for (Element current = type; current instanceof TypeElement; current = current.getEnclosingElement())
                if (!lexicalOwners.contains(current)) increment(reads, current);
        }

        String binaryName(TypeElement type) { return elements.getBinaryName(type).toString(); }

        boolean xmlConstructor(ExecutableElement method, TypeElement owner) {
            if (!method.getModifiers().contains(Modifier.PUBLIC)) return false;
            if (xml.components.contains(binaryName(owner)) && method.getParameters().isEmpty()) return true;
            return xml.views.contains(binaryName(owner)) && parameters(method, "android.content.Context", "android.util.AttributeSet");
        }

        boolean xmlClick(ExecutableElement method) {
            return xml.clicks.contains(method.getSimpleName().toString()) && method.getModifiers().contains(Modifier.PUBLIC)
                    && !method.getModifiers().contains(Modifier.STATIC) && method.getReturnType().toString().equals("void")
                    && parameters(method, "android.view.View");
        }

        int report() {
            int candidates = 0;
            for (Map.Entry<Element, String> entry : declarations.entrySet()) {
                Element element = entry.getKey();
                if (!contracts.contains(element) && reads.getOrDefault(element, 0) == 0) {
                    String description = element instanceof TypeElement ? ((TypeElement) element).getQualifiedName().toString()
                            : element.getEnclosingElement() + "." + element;
                    System.out.println(entry.getValue() + " unused " + element.getKind().name().toLowerCase() + " " + description
                            + (element.getKind() == ElementKind.FIELD ? " writes=" + writes.getOrDefault(element, 0) : ""));
                    candidates++;
                }
            }
            for (Imported imported : imports) if (!imported.used || imported.duplicate) {
                System.out.println(imported.position + (imported.duplicate ? " duplicate import " : " unused import ")
                        + (imported.member ? "static " : "") + imported.name); candidates++;
            }
            return candidates;
        }
    }

    private static void increment(Map<Element, Integer> counts, Element element) { counts.merge(element, 1, Integer::sum); }

    private static final class Source extends SimpleJavaFileObject {
        final String body;
        Source(String name, String body) {
            super(URI.create("string:///" + name.replace('.', '/') + ".java"), Kind.SOURCE); this.body = body;
        }
        @Override public CharSequence getCharContent(boolean ignore) { return body; }
    }

    /** Real compiler fixtures ensure constructor/override/XML protection is precise. */
    private static void selfCheck(JavaCompiler compiler) throws Exception {
        List<JavaFileObject> sources = Arrays.asList(
                new Source("android.content.Context", "package android.content; public class Context {}"),
                new Source("android.util.AttributeSet", "package android.util; public interface AttributeSet {}"),
                new Source("auditfixture.Fixture", "package auditfixture;\n"
                        + "import java.util.List; import java.util.Set; import java.util.function.IntFunction;\n"
                        + "import static java.util.Collections.emptySet;\n"
                        + "class Base { Base(){} Base(int unused){} void live(){} }\n"
                        + "class Fixture extends Base implements Runnable { int written, returned;\n"
                        + "public Fixture(){super();} Fixture(String used){this();} Fixture(int used){this();} Fixture(boolean unused){super();}\n"
                        + "void dead(){} public void run(){ new Fixture(\"used\"); IntFunction<Fixture> maker=Fixture::new;\n"
                        + "live(); written++; Set<String> set=emptySet(); System.out.println(set.size()+Utility.constant()+ ++returned); maker.apply(1); } }\n"
                        + "class Utility { private Utility(){} static int constant(){return 1;} }\n"
                        + "class XmlView { XmlView(android.content.Context c){} public XmlView(android.content.Context c,android.util.AttributeSet a){}\n"
                        + "XmlView(android.content.Context c,android.util.AttributeSet a,int unused){} }\n"
                        + "class Component { public Component(){} Component(int unused){} }\n"
                        + "class Cycle { static void a(){b();} static void b(){a();} }"));
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    Arrays.asList("-proc:none", "-Xlint:none"), null, sources);
            List<CompilationUnitTree> units = new ArrayList<>(); task.parse().forEach(units::add); task.analyze();
            for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) if (diagnostic.getKind() == Diagnostic.Kind.ERROR)
                throw new AssertionError("Audit fixture did not compile: " + diagnostic);
            XmlEntries xml = new XmlEntries(); xml.components.add("auditfixture.Fixture"); xml.components.add("auditfixture.Component");
            xml.views.add("auditfixture.XmlView");
            Audit audit = new Audit(Paths.get("/").toAbsolutePath(), task, xml);
            for (CompilationUnitTree unit : units) if (unit.getPackageName().toString().equals("auditfixture")) audit.scan(unit);
            Set<String> actual = new HashSet<>();
            for (Element element : audit.declarations.keySet()) if (!audit.contracts.contains(element) && audit.reads.getOrDefault(element, 0) == 0)
                actual.add(element instanceof TypeElement ? element.getSimpleName().toString()
                        : element.getEnclosingElement().getSimpleName() + "." + element);
            Set<String> expected = new HashSet<>(Arrays.asList("Base.Base(int)", "Fixture.Fixture(boolean)", "Fixture.dead()", "Fixture.written",
                    "XmlView.XmlView(android.content.Context)", "XmlView.XmlView(android.content.Context,android.util.AttributeSet,int)", "Component.Component(int)", "Cycle"));
            if (!actual.equals(expected)) throw new AssertionError("Audit lost an exact unused constructor/field/type or misclassified a live callback/entry: " + actual);
            List<String> unusedImports = new ArrayList<>();
            for (Imported imported : audit.imports) if (!imported.used || imported.duplicate) unusedImports.add(imported.name);
            if (!unusedImports.equals(Arrays.asList("java.util.List"))) throw new AssertionError("Import references were counted incorrectly: " + unusedImports);
        }
        System.out.println("PASS audit compiler fixtures: exact overloads, this/super/new/method references, interfaces, XML, field writes and imports");
    }

    private static boolean parameters(ExecutableElement method, String... expected) {
        if (method.getParameters().size() != expected.length) return false;
        for (int i = 0; i < expected.length; i++) if (!method.getParameters().get(i).asType().toString().equals(expected[i])) return false;
        return true;
    }

    private static boolean utilityConstructor(ExecutableElement method, TypeElement owner) {
        if (!method.getModifiers().contains(Modifier.PRIVATE) || !method.getParameters().isEmpty()) return false;
        if (!owner.getSuperclass().toString().equals("java.lang.Object")) return false;
        for (Element member : owner.getEnclosedElements()) {
            if ((member.getKind() == ElementKind.FIELD || member.getKind() == ElementKind.METHOD)
                    && !member.getModifiers().contains(Modifier.STATIC)) return false;
        }
        return true;
    }

    private static boolean overrides(ExecutableElement method, TypeElement owner, Types types, Elements elements) {
        ArrayDeque<TypeMirror> queue = new ArrayDeque<>(types.directSupertypes(owner.asType()));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            TypeMirror mirror = queue.remove();
            if (!seen.add(mirror.toString())) continue;
            Element parent = types.asElement(mirror);
            if (!(parent instanceof TypeElement)) continue;
            for (Element member : parent.getEnclosedElements()) if (member.getKind() == ElementKind.METHOD
                    && elements.overrides(method, (ExecutableElement) member, owner)) return true;
            queue.addAll(types.directSupertypes(mirror));
        }
        return false;
    }

    private static String className(String name, String namespace) {
        return name.startsWith(".") ? namespace + name : name.indexOf('.') < 0 ? namespace + "." + name : name;
    }

    private static XmlEntries xmlEntries(Path root) throws Exception {
        XmlEntries result = new XmlEntries();
        String gradle = Files.readString(root.resolve("app/build.gradle"));
        Matcher namespace = Pattern.compile("(?m)^\\s*namespace\\s*[=]?\\s*['\"]([^'\"]+)['\"]").matcher(gradle);
        if (!namespace.find()) throw new AssertionError("Android namespace is required to resolve XML component names");
        String appNamespace = namespace.group(1);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document manifest = factory.newDocumentBuilder().parse(root.resolve("app/src/main/AndroidManifest.xml").toFile());
        for (String kind : Arrays.asList("application", "activity", "service", "receiver", "provider", "instrumentation")) {
            NodeList nodes = manifest.getElementsByTagName(kind);
            for (int i = 0; i < nodes.getLength(); i++) {
                org.w3c.dom.Element element = (org.w3c.dom.Element) nodes.item(i);
                String name = element.getAttributeNS(ANDROID, "name");
                if (!name.isEmpty()) result.components.add(className(name, appNamespace));
                if (kind.equals("application")) for (String attribute : Arrays.asList("backupAgent", "appComponentFactory")) {
                    name = element.getAttributeNS(ANDROID, attribute);
                    if (!name.isEmpty()) result.components.add(className(name, appNamespace));
                }
            }
        }
        try (var walk = Files.walk(root.resolve("app/src/main/res"))) {
            for (Path file : walk.filter(path -> path.getParent().getFileName().toString().startsWith("layout")
                    && path.toString().endsWith(".xml")).sorted().toList()) {
                Document layout = factory.newDocumentBuilder().parse(file.toFile());
                NodeList nodes = layout.getElementsByTagName("*");
                for (int i = 0; i < nodes.getLength(); i++) {
                    org.w3c.dom.Element element = (org.w3c.dom.Element) nodes.item(i);
                    String tag = element.getTagName(), name = tag.equals("view") ? element.getAttribute("class") : tag;
                    if (name.indexOf('.') >= 0) result.views.add(className(name, appNamespace));
                    if (tag.equals("fragment")) {
                        name = element.getAttributeNS(ANDROID, "name");
                        if (!name.isEmpty()) result.components.add(className(name, appNamespace));
                    }
                    String click = element.getAttributeNS(ANDROID, "onClick");
                    if (!click.isEmpty()) result.clicks.add(click);
                }
            }
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Pass PROJECT_ROOT and the real Android CLASSPATH_FILE");
        Path root = Paths.get(args[0]).toAbsolutePath().normalize();
        String classpath = Files.readString(Paths.get(args[1])).trim();
        if (classpath.isEmpty()) throw new AssertionError("Empty production compile classpath");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new AssertionError("A full JDK is required for compiler-bound symbol analysis");
        selfCheck(compiler);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            List<File> sources = new ArrayList<>();
            try (var walk = Files.walk(root.resolve("app/src/main/java"))) {
                walk.filter(path -> path.toString().endsWith(".java")).sorted().forEach(path -> sources.add(path.toFile()));
            }
            if (sources.isEmpty()) throw new AssertionError("No production Java sources");
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    Arrays.asList("-proc:none", "-source", "8", "-encoding", "UTF-8", "-Xlint:none", "-classpath", classpath),
                    null, manager.getJavaFileObjectsFromFiles(sources));
            List<CompilationUnitTree> units = new ArrayList<>(); task.parse().forEach(units::add); task.analyze();
            for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) if (diagnostic.getKind() == Diagnostic.Kind.ERROR)
                throw new AssertionError("Production source did not resolve against its build classpath: " + diagnostic);
            Audit audit = new Audit(root, task, xmlEntries(root));
            for (CompilationUnitTree unit : units) audit.scan(unit);
            int candidates = audit.report();
            System.out.println("Production audit: " + sources.size() + " Java files, " + audit.declarations.size()
                    + " declarations, " + audit.imports.size() + " imports, " + candidates + " unresolved candidates");
            if (candidates != 0) throw new AssertionError("Review and remove the unused production declarations/imports above");
            System.out.println("PASS compiler-bound production member, constructor, class and import audit");
        }
    }
}
