package community.flock.byterails.maven;

import community.flock.byterails.ByterailsRunner;
import community.flock.byterails.model.ConfigException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks the compiled classes of the module against {@code byterails.kts}.
 *
 * <p>Bound to {@code verify} by default. Every module of a multi-module build checks its own
 * classes against the rules file in the multi-module root, so nothing needs to be aggregated. A
 * Maven module that names a byterails {@code module} is checked against the root file plus the
 * rules files of every module in the reactor, which this goal finds through the other modules'
 * plugin configuration.
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class CheckMojo extends AbstractMojo {

    static final String PLUGIN_KEY = "community.flock.byterails:byterails-maven-plugin";

    /** The rules file. Defaults to {@code byterails.kts} in the multi-module root directory. */
    @Parameter(property = "byterails.rulesFile", defaultValue = "${maven.multiModuleProjectDirectory}/byterails.kts")
    private File rulesFile;

    /** The compiled main classes of this module. */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File classesDirectory;

    /**
     * A package every declaration in the rules file is relative to, for example {@code com.acme}.
     * A rule prefix is prefixed too when it points into the declared package tree.
     */
    @Parameter(property = "byterails.basePackage")
    private String basePackage;

    /**
     * The slices of the application: package names relative to the base package. The rules file's
     * {@code slice { }} block is applied to each of them. As a property: {@code -Dbyterails.slices=orders,customers}.
     * On a module, the slices lie under the module and its own rules file's slice block applies to them.
     */
    @Parameter(property = "byterails.slices")
    private List<String> slices;

    /**
     * Rule sets byterails ships, by id, applied on top of the rules file or instead of it:
     * {@code hexagonal} keeps a {@code domain} package free of external dependencies, and
     * {@code hexagonalSpring} declares the hexagonal layout of a sliced Spring Boot service.
     * On a module, the sets apply under the module.
     */
    @Parameter(property = "byterails.defaultRules")
    private List<String> defaultRules;

    /**
     * The byterails module this Maven module is: a package relative to the base package, for example
     * {@code orders}, that this module's classes must live in and no other module may put classes in.
     * The module's own rules file, see {@code moduleRulesFile}, declares what lies under it. Unset by
     * default: the module is then checked against the root rules file alone.
     */
    @Parameter(property = "byterails.module")
    private String module;

    /**
     * The rules file of this byterails module, relative to the module. Defaults to {@code byterails.kts}
     * in the module's directory and may be absent. Ignored without {@code module}.
     */
    @Parameter(property = "byterails.moduleRulesFile", defaultValue = "${project.basedir}/byterails.kts")
    private File moduleRulesFile;

    /** When true, violations are printed and the build stays green. */
    @Parameter(property = "byterails.reportOnly", defaultValue = "false")
    private boolean reportOnly;

    /** Skips the check entirely. */
    @Parameter(property = "byterails.skip", defaultValue = "false")
    private boolean skip;

    /** Where the JSON report is written. */
    @Parameter(defaultValue = "${project.build.directory}/byterails/violations.json")
    private File reportFile;

    /** Where compiled rules files are cached by content hash. */
    @Parameter(defaultValue = "${project.build.directory}/byterails/script-cache")
    private File scriptCacheDir;

    @Parameter(defaultValue = "${project.packaging}", readonly = true)
    private String packaging;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Parameter(defaultValue = "${maven.multiModuleProjectDirectory}", readonly = true)
    private File multiModuleProjectDirectory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("byterails: skipped");
            return;
        }
        if ("pom".equals(packaging)) {
            getLog().debug("byterails: nothing to check in a pom module");
            return;
        }
        if (!classesDirectory.isDirectory()) {
            getLog().info("byterails: no compiled classes in " + classesDirectory + ", nothing to check");
            return;
        }
        String currentModule = module == null || module.trim().isEmpty() ? null : module.trim();
        List<Map<String, Object>> modules = modules(currentModule);
        boolean hasDefaults = defaultRules != null && !defaultRules.isEmpty();
        if (!rulesFile.isFile() && !hasDefaults && modules.isEmpty()) {
            throw new MojoFailureException("byterails: rules file " + rulesFile + " does not exist and no defaultRules are set");
        }
        if (currentModule == null && moduleRulesFile != null && moduleRulesFile.isFile() && !sameFile(moduleRulesFile, rulesFile)) {
            throw new MojoFailureException("byterails: " + moduleRulesFile + " is a module rules file, but the module sets no <module> name");
        }
        File rules = rulesFile.isFile() ? rulesFile : null;
        // On a module, its own slices and default rules apply under the module; the root file gets the root project's.
        List<String> rootSlices = slices;
        List<String> rootDefaultRules = defaultRules;
        if (currentModule != null) {
            MavenProject root = rootProject();
            boolean rootIsThisModule = root == project || sameFile(root.getBasedir(), project.getBasedir());
            Xpp3Dom rootConfiguration = rootIsThisModule ? null : configurationOf(root);
            rootSlices = list(rootConfiguration, "slices");
            rootDefaultRules = list(rootConfiguration, "defaultRules");
        }

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(CheckMojo.class.getClassLoader());
        int violations;
        try {
            violations = ByterailsRunner.run(
                    rules, List.of(classesDirectory), reportFile, scriptCacheDir, basePackage, rootSlices, rootDefaultRules,
                    multiModuleProjectDirectory, currentModule, modules, line -> getLog().info(line));
        } catch (ConfigException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new MojoExecutionException("byterails failed: " + e, e);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }

        if (violations > 0 && !reportOnly) {
            String noun = violations == 1 ? "violation" : "violations";
            throw new MojoFailureException("byterails found " + violations + " " + noun + "; see the lines above or " + reportFile);
        }
    }

    /**
     * Every byterails module in the reactor, read from each Maven module's configuration of this plugin,
     * with this module's own settings taken from the parameters so that properties apply to it. Runs
     * that select part of the reactor still see every module, because the whole reactor is loaded.
     */
    private List<Map<String, Object>> modules(String currentModule) {
        List<Map<String, Object>> result = new ArrayList<>();
        boolean currentSeen = false;
        for (MavenProject candidate : reactor()) {
            if (candidate == project || sameFile(candidate.getBasedir(), project.getBasedir())) {
                if (currentModule != null && !currentSeen) {
                    result.add(module(currentModule, moduleRulesFile, slices, defaultRules));
                    currentSeen = true;
                }
                continue;
            }
            Xpp3Dom configuration = configurationOf(candidate);
            String name = value(configuration, "module");
            if (name == null || name.trim().isEmpty()) continue;
            String file = value(configuration, "moduleRulesFile");
            File rules = file == null ? new File(candidate.getBasedir(), "byterails.kts") : new File(file);
            result.add(module(name.trim(), rules, list(configuration, "slices"), list(configuration, "defaultRules")));
        }
        if (currentModule != null && !currentSeen) {
            result.add(module(currentModule, moduleRulesFile, slices, defaultRules));
        }
        return result;
    }

    private static Map<String, Object> module(String name, File rulesFile, List<String> slices, List<String> defaultRules) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", name);
        spec.put("rulesFile", rulesFile != null && rulesFile.isFile() ? rulesFile : null);
        spec.put("slices", slices == null ? List.of() : new ArrayList<>(slices));
        spec.put("defaultRules", defaultRules == null ? List.of() : new ArrayList<>(defaultRules));
        return spec;
    }

    private List<MavenProject> reactor() {
        if (session == null) return List.of(project);
        List<MavenProject> all = session.getAllProjects();
        if (all == null || all.isEmpty()) all = session.getProjects();
        return all == null ? List.of(project) : all;
    }

    /** The project in the multi-module root directory, whose configuration holds the root settings. */
    private MavenProject rootProject() {
        for (MavenProject candidate : reactor()) {
            if (multiModuleProjectDirectory != null && sameFile(candidate.getBasedir(), multiModuleProjectDirectory)) return candidate;
        }
        return session != null && session.getTopLevelProject() != null ? session.getTopLevelProject() : project;
    }

    /** This plugin's configuration in a project's effective POM: the plugin level, then each execution over it. */
    static Xpp3Dom configurationOf(MavenProject candidate) {
        Plugin plugin = candidate.getPlugin(PLUGIN_KEY);
        if (plugin == null) return null;
        Xpp3Dom merged = plugin.getConfiguration() instanceof Xpp3Dom ? new Xpp3Dom((Xpp3Dom) plugin.getConfiguration()) : new Xpp3Dom("configuration");
        for (PluginExecution execution : plugin.getExecutions()) {
            if (execution.getConfiguration() instanceof Xpp3Dom) {
                merged = Xpp3Dom.mergeXpp3Dom(new Xpp3Dom((Xpp3Dom) execution.getConfiguration()), merged);
            }
        }
        return merged;
    }

    static String value(Xpp3Dom configuration, String name) {
        if (configuration == null) return null;
        Xpp3Dom child = configuration.getChild(name);
        return child == null || child.getValue() == null ? null : child.getValue().trim();
    }

    /** A list parameter as {@code <slices><slice>a</slice></slices>} or as the comma-separated text {@code <slices>a,b</slices>}. */
    static List<String> list(Xpp3Dom configuration, String name) {
        List<String> result = new ArrayList<>();
        if (configuration == null) return result;
        Xpp3Dom child = configuration.getChild(name);
        if (child == null) return result;
        if (child.getChildCount() == 0) {
            if (child.getValue() != null && !child.getValue().trim().isEmpty()) {
                result.addAll(Arrays.asList(child.getValue().split(",")));
            }
        } else {
            for (Xpp3Dom item : child.getChildren()) {
                if (item.getValue() != null) result.add(item.getValue().trim());
            }
        }
        return result;
    }

    private static boolean sameFile(File a, File b) {
        if (a == null || b == null) return false;
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (IOException e) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }
}
