package com.aismartstore.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/03-dev/specs/audit-logging.md §5 "Test lưới an toàn" (Task 11): every mutating
 * {@code @RestController} handler ({@code @PostMapping}/{@code @PutMapping}/{@code @PatchMapping}/
 * {@code @DeleteMapping}) must either reach {@link AuditService#record} or {@link
 * AuditService#recordAsync} somewhere in its static call graph, or be named in {@link #ALLOWLIST}
 * with a reason. This replaces the old guarantee that {@code AuditInterceptor} audited every
 * request automatically (it now only does 401/403/5xx fallback, see AuditInterceptor.afterCompletion) -
 * this test is what stands in for that on the 2xx/success path going forward.
 *
 * <p><b>Method: static bytecode call-graph reachability, not live HTTP requests.</b> The spec (§5)
 * allows either "run a real request through every route via Testcontainers" or "static call-graph
 * analysis (controller -> service)". The live-request route was rejected for Task 11 originally
 * because reaching a genuine 2xx on all ~56 mutating endpoints needs realistic preconditions
 * (existing rows, valid multipart bodies, matching roles) for every one of them - a separate,
 * large effort with its own risk of asserting against fabricated/contrived state. Static analysis
 * answers the actual question ("does this handler's code call AuditService.record anywhere,
 * regardless of which branch") without needing to execute anything, using {@code
 * org.springframework.asm} (already on the classpath via spring-core - no new dependency).
 *
 * <p><b>How the call graph is built</b> (see {@link CallGraph}): every {@code .class} file under
 * {@code com.aismartstore.backend} is parsed once; each method's {@code invoke*} instructions become
 * edges {@code caller -> callee} keyed by (owner internal name, method name) - descriptor is
 * deliberately ignored so overloads merge, which only makes the graph more permissive, never less.
 * Two bridges are added over that raw edge set so real dispatch patterns in this codebase resolve
 * correctly:
 * <ul>
 *   <li><b>Interface dispatch:</b> a call site's bytecode owner is the static type at the call site
 *       (e.g. {@code SearchService.reindex()}, not {@code SearchServiceImpl.reindex()}). For every
 *       interface implemented by a scanned class, traversal also follows the same method name into
 *       each known implementor. Checked against this codebase: only {@code
 *       DigitizationRunLifecycleService} and {@code SearchService} are non-repository interfaces with
 *       app-code implementors (one each) - Spring Data repository interfaces have no bytecode
 *       implementor among our scanned classes, so they're correctly dead ends.</li>
 *   <li><b>Same-class lambdas:</b> a lambda body compiles to a synthetic {@code lambda$...} method on
 *       the *enclosing* class, invoked indirectly via {@code invokedynamic} - there is no direct
 *       {@code invoke*} edge from the enclosing method to it. Traversal treats every {@code lambda$}
 *       method as reachable from any method already reached in the same class. This is a deliberate
 *       over-approximation (grep confirmed no {@code auditService::record} bare method references
 *       exist today, which bytecode-wise would need the same treatment but aren't covered by this
 *       heuristic) - it can only make the graph more permissive, never hide a real gap by being too
 *       strict.</li>
 * </ul>
 *
 * <p><b>What a "pass" does and doesn't prove:</b> reachability doesn't distinguish which branch the
 * call sits in (e.g. a {@code record()} call only in a {@code catch} block still counts) - proving
 * the 2xx path specifically calls audit would need live execution per branch. What this does prove,
 * matching the spec's own wording ("gọi AuditService.record* đâu đó trong call chain"): the handler's
 * implementation has *some* audit instrumentation wired in, so a brand new mutating route that has
 * none at all (the actual failure mode this guards against - a copy-pasted controller method that
 * forgot the audit call entirely) fails loudly instead of silently shipping.
 */
class AuditInstrumentationCoverageTest {

    private static final String AUDIT_SERVICE_INTERNAL_NAME = "com/aismartstore/backend/audit/AuditService";

    /**
     * Routes confirmed by hand to be legitimately exempt, with the reason. Key format:
     * {@code "<ControllerSimpleName>#<methodName>"}. Every mutating handler NOT listed here must have
     * AuditService.record/recordAsync reachable in its call graph, or the test below fails and names
     * it - add here (with a real reason) or wire up audit logging, don't silence by other means.
     */
    private static final Map<String, String> ALLOWLIST = new TreeMap<>(Map.of(
            "UploadSessionController#putChunk",
            "Raw byte-range PUT, called many times per file (once per chunk - a single large file "
                    + "can mean dozens of calls). The meaningful business events around it are already "
                    + "audited at their own boundaries: session open (ingest.upload_session.init / "
                    + "personal.upload_session.init, see UploadSessionService) and the resulting document "
                    + "creation on complete() (inside DocumentsService.ingestBatchFile, reached from "
                    + "UploadSessionService.complete()). An audit_log row per chunk would be pure noise "
                    + "with no security/compliance signal beyond what init/complete already capture.",
            "NotificationsController#markRead",
            "Marks the caller's own private notification as read - not visible to anyone else, "
                    + "changes no one else's data, and is invoked on ordinary UI interaction (every "
                    + "notification a user opens). No FR-09/FR-10 audit trail value, and logging it would "
                    + "add a high-frequency, zero-signal row for every notification click.",
            "DocumentsController#trashPreview",
            "delete-restore-trash-01-xoa-mem-thung-rac.md §3: a POST purely because the body carries "
                    + "a documentIds array (too large for a query string), not because it mutates "
                    + "anything - it only reads document_group_share counts to help the FE warn the user "
                    + "before they confirm POST /documents/trash (which IS audited, per document, via "
                    + "TrashService#trash). No state changes here, so no audit signal to record.",
            "IngestController#nameCheck",
            "SPEC-folder-upload-display-name BE-5: a POST only because the body carries up to 1000 file names; "
                    + "read-only lookup of which names are taken (no state change, no file content, names are "
                    + "not logged). Rate limited to 30/min/user; the uploads that follow are audited per file.",
            "IngestController#fromPersonalNameCheck",
            "SPEC-folder-upload-display-name BE-5: a POST only because the body carries up to 1000 file names; "
                    + "read-only lookup of which names are taken (no state change, no file content, names are "
                    + "not logged). Rate limited to 30/min/user; the uploads that follow are audited per file.",
            "PersonalController#nameCheck",
            "SPEC-folder-upload-display-name BE-5: a POST only because the body carries up to 1000 file names; "
                    + "read-only lookup of which names are taken (no state change, no file content, names are "
                    + "not logged). Rate limited to 30/min/user; the uploads that follow are audited per file."));

    @Test
    void everyMutatingRestEndpointEitherAuditsOrIsExplicitlyAllowlisted() throws IOException {
        Map<String, byte[]> appClassBytes = readAppClassBytes();
        CallGraph graph = CallGraph.build(appClassBytes);

        List<String> uninstrumented = new ArrayList<>();
        List<String> unknownAllowlistEntries = new ArrayList<>(ALLOWLIST.keySet());
        List<String> allMutatingRoutes = new ArrayList<>();

        int loadFailures = 0;
        int controllerCount = 0;
        for (Map.Entry<String, byte[]> entry : appClassBytes.entrySet()) {
            String internalName = entry.getKey();
            Class<?> clazz = tryLoad(internalName.replace('/', '.'));
            if (clazz == null) {
                loadFailures++;
                continue;
            }
            if (!clazz.isAnnotationPresent(RestController.class)) {
                continue;
            }
            controllerCount++;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!isMutatingHandler(method)) {
                    continue;
                }
                String routeKey = clazz.getSimpleName() + "#" + method.getName();
                allMutatingRoutes.add(routeKey);
                unknownAllowlistEntries.remove(routeKey);
                if (ALLOWLIST.containsKey(routeKey)) {
                    continue;
                }
                boolean audited = graph.canReach(internalName, method.getName(), AUDIT_SERVICE_INTERNAL_NAME, "record")
                        || graph.canReach(internalName, method.getName(), AUDIT_SERVICE_INTERNAL_NAME, "recordAsync");
                if (!audited) {
                    uninstrumented.add(routeKey);
                }
            }
        }

        // Anti-tautology guard: if the classpath/reflection scan above silently found zero
        // mutating handlers (e.g. a build layout change breaks readAppClassBytes()'s resource
        // lookup), every assertion below would vacuously pass and this test would stop meaning
        // anything without ever going red. 56 is the actual count in this codebase as of writing
        // (grep -rc '@PostMapping|@PutMapping|@PatchMapping|@DeleteMapping'); floored generously
        // below that to tolerate future refactors without becoming its own maintenance burden.
        assertThat(allMutatingRoutes.size())
                .as("Sanity check: expected to find dozens of mutating @RestController handlers via "
                        + "reflection, found %s (%s). classesScanned=%s loadFailures=%s controllerCount=%s "
                        + "If this is unexpectedly low, the scan is broken, not the codebase - see "
                        + "readAppClassBytes()/tryLoad().",
                        allMutatingRoutes.size(), allMutatingRoutes, appClassBytes.size(), loadFailures,
                        controllerCount)
                .isGreaterThanOrEqualTo(40);

        assertThat(unknownAllowlistEntries)
                .as("ALLOWLIST entries that no longer match any mutating @RestController method - "
                        + "remove them (the route was deleted/renamed, or audit logging is now wired up).")
                .isEmpty();
        assertThat(uninstrumented)
                .as("Mutating @RestController endpoints whose static call graph never reaches "
                        + "AuditService.record/recordAsync, and aren't in ALLOWLIST above (see class "
                        + "javadoc). Either wire audit logging for these routes, or add them to "
                        + "ALLOWLIST with a reason.")
                .isEmpty();
    }

    private static boolean isMutatingHandler(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            Class<? extends Annotation> type = annotation.annotationType();
            if (type == PostMapping.class
                    || type == PutMapping.class
                    || type == PatchMapping.class
                    || type == DeleteMapping.class) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> tryLoad(String className) {
        try {
            // initialize=false: we only need annotations/method signatures, not static <clinit>
            // side effects (some of these classes wire up JPA/Jackson state we don't want to trigger).
            return Class.forName(className, false, AuditInstrumentationCoverageTest.class.getClassLoader());
        } catch (Throwable t) {
            // Synthetic types (e.g. package-info, some inner/lambda classes without a loadable
            // enclosing chain in this reflective context) - not controllers, safe to skip.
            return null;
        }
    }

    private static Map<String, byte[]> readAppClassBytes() throws IOException {
        // Deliberately NOT classLoader.getResource("com/aismartstore/backend"): src/test/java mirrors
        // the same package tree (this very class is under com.aismartstore.backend.audit), so
        // target/test-classes/com/aismartstore/backend exists too - getResource() returns whichever
        // classpath entry the loader happens to search first, which in practice was test-classes,
        // silently scanning zero @RestController classes (they're all under src/main). Anchoring on
        // AuditService's own code-source location instead always lands in target/classes, since
        // AuditService itself only exists there.
        URI classesRootUri;
        try {
            classesRootUri = AuditService.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        } catch (Exception e) {
            throw new IOException("Could not resolve AuditService's code source location", e);
        }
        Path classesRoot = Path.of(classesRootUri);
        assertThat(Files.isDirectory(classesRoot))
                .as("Expected AuditService's code source (%s) to be an exploded classes directory "
                        + "(true for `mvn test`/`verify`) - got something else, this scan doesn't "
                        + "support running against a packaged jar.", classesRoot)
                .isTrue();
        Path root = classesRoot.resolve("com/aismartstore/backend");
        Map<String, byte[]> result = new HashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                String internalName = "com/aismartstore/backend/"
                        + relative.substring(0, relative.length() - ".class".length());
                result.put(internalName, Files.readAllBytes(file));
            }
        }
        assertThat(result).as("Expected to find at least one compiled class under com/aismartstore/backend")
                .isNotEmpty();
        return result;
    }

    /**
     * Caller -> callee reachability over the app's own bytecode. See class javadoc for the two
     * bridges (interface dispatch, same-class lambdas) layered on top of raw {@code invoke*} edges.
     */
    private static final class CallGraph {
        private final Map<String, Set<String>> edgesByNode;
        private final Map<String, List<String>> implsByInterface;
        private final Map<String, List<String>> lambdaNodesByOwner;

        private CallGraph(
                Map<String, Set<String>> edgesByNode,
                Map<String, List<String>> implsByInterface,
                Map<String, List<String>> lambdaNodesByOwner) {
            this.edgesByNode = edgesByNode;
            this.implsByInterface = implsByInterface;
            this.lambdaNodesByOwner = lambdaNodesByOwner;
        }

        static CallGraph build(Map<String, byte[]> classBytesByInternalName) {
            Map<String, Set<String>> edgesByNode = new HashMap<>();
            Map<String, List<String>> implsByInterface = new HashMap<>();
            Map<String, List<String>> lambdaNodesByOwner = new HashMap<>();

            for (byte[] bytes : classBytesByInternalName.values()) {
                ClassReader reader = new ClassReader(bytes);
                String[] ownerHolder = new String[1];
                reader.accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(
                                    int version, int access, String name, String signature,
                                    String superName, String[] interfaces) {
                                ownerHolder[0] = name;
                                for (String itf : interfaces) {
                                    implsByInterface.computeIfAbsent(itf, k -> new ArrayList<>()).add(name);
                                }
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String methodName, String descriptor,
                                    String signature, String[] exceptions) {
                                String owner = ownerHolder[0];
                                String node = owner + "#" + methodName;
                                Set<String> callees = edgesByNode.computeIfAbsent(node, k -> new HashSet<>());
                                if (methodName.startsWith("lambda$")) {
                                    lambdaNodesByOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(node);
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode, String calleeOwner, String calleeName,
                                            String calleeDescriptor, boolean isInterface) {
                                        callees.add(calleeOwner + "#" + calleeName);
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }

            return new CallGraph(edgesByNode, implsByInterface, lambdaNodesByOwner);
        }

        boolean canReach(String startOwner, String startMethod, String targetOwner, String targetMethod) {
            String start = startOwner + "#" + startMethod;
            String target = targetOwner + "#" + targetMethod;
            Set<String> visited = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            visited.add(start);
            queue.add(start);

            while (!queue.isEmpty()) {
                String node = queue.poll();
                if (node.equals(target)) {
                    return true;
                }

                for (String callee : edgesByNode.getOrDefault(node, Set.of())) {
                    enqueueIfNew(callee, visited, queue);
                    int hashIdx = callee.indexOf('#');
                    String calleeOwner = callee.substring(0, hashIdx);
                    String calleeMethod = callee.substring(hashIdx + 1);
                    for (String implOwner : implsByInterface.getOrDefault(calleeOwner, List.of())) {
                        enqueueIfNew(implOwner + "#" + calleeMethod, visited, queue);
                    }
                }

                String owner = node.substring(0, node.indexOf('#'));
                for (String lambdaNode : lambdaNodesByOwner.getOrDefault(owner, List.of())) {
                    enqueueIfNew(lambdaNode, visited, queue);
                }
            }
            return false;
        }

        private static void enqueueIfNew(String node, Set<String> visited, Deque<String> queue) {
            if (visited.add(node)) {
                queue.add(node);
            }
        }
    }
}
